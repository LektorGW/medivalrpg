// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import pl.medivalrpg.kolej.build.RailGeometry;
import pl.medivalrpg.kolej.geom.Vec3;
import pl.medivalrpg.kolej.network.*;

/** Canonical rails mapped monotonically onto the exact two-pass Chaikin path. Never floors the smoothed path. */
public final class PhysicalRailGuard {
    public static final int MAX_ROUTE_CELLS=32_768,MAX_PROBES=256;
    public static final double MAX_AHEAD=224,BUFFER=6;
    public record Rail(TrackCell cell,double offset,String shape,String junction,boolean grade) {}
    public record Result(double limit,String reason,int checked) {public boolean clear(){return reason==null;}}
    @FunctionalInterface public interface Probe {String inspect(Rail rail);}
    private record Sample(Vec3 point,double parameter) {}
    private record Segment(TrackSegment segment,List<TrackCell> cells) {}
    public static final class Index {
        private final List<Rail> rails;
        private final List<Segment> segments;
        private final List<TrackNode> nodes;
        private final double length;
        private Index(List<Rail> rails,List<Segment> segments,List<TrackNode> nodes,double length){this.rails=List.copyOf(rails);this.segments=List.copyOf(segments);this.nodes=List.copyOf(nodes);this.length=length;}
        public List<Rail> rails(){return rails;}
        public boolean current(TrackGraph graph) {
            for(var s:segments)if(graph.segment(s.segment.id())!=s.segment || s.segment.cells()!=s.cells || !s.segment.isBuilt())return false;
            for(var n:nodes)if(!n.equals(graph.node(n.id())))return false;
            return true;
        }
        public Result inspect(TrackGraph graph,double center,double ahead,Probe probe) {
            if(!current(graph) || !Double.isFinite(center) || center<0 || center>length || !Double.isFinite(ahead) || ahead<0)return new Result(Math.max(0,Double.isFinite(center)?center:0),"Nieprawidłowa lub zmieniona trasa",0);
            double horizon=Math.min(length,center+Math.min(MAX_AHEAD,Math.max(16,ahead)));
            int lo=0,hi=rails.size();double from=Math.max(0,center-4);
            while(lo<hi){int mid=(lo+hi)>>>1;if(rails.get(mid).offset()<from)lo=mid+1;else hi=mid;}
            int first=Math.max(0,lo-1),checked=0;double verified=from;
            for(int i=first;i<rails.size();i++) {
                Rail r=rails.get(i);
                // Include the first cell past the horizon: interpolation must not leave an unchecked gap.
                if(checked>=MAX_PROBES)return new Result(Math.max(center,verified-BUFFER),"Limit kontroli toru",checked);
                String failure;
                try{failure=probe.inspect(r);}catch(RuntimeException e){failure="Nie można sprawdzić fizycznego toru";}
                checked++;
                if(failure!=null) {
                    double entry=i==0?0:(rails.get(i-1).offset()+r.offset())*.5;
                    return new Result(Math.max(center,entry-BUFFER),failure,checked);
                }
                verified=r.offset();
                if(r.offset()>=horizon)return new Result(horizon>=length?length:Math.max(center,horizon-BUFFER),null,checked);
            }
            return new Result(length,null,checked);
        }
    }
    public static Index index(TrackGraph graph,Route route) {
        if(route==null || route.legs().isEmpty() || route.nodes().size()!=route.legs().size()+1 || !Double.isFinite(route.length()) || route.length()<=0)throw new IllegalArgumentException("Brak poprawnej trasy fizycznej");
        var cells=new ArrayList<TrackCell>();var offsets=new ArrayList<Double>();var saved=new ArrayList<Segment>();var nodes=new ArrayList<TrackNode>();
        String world=null;
        for(String id:route.nodes()) {
            TrackNode n=graph.node(id);if(n==null || world!=null && !world.equals(n.world()))throw new IllegalArgumentException("Brak węzła lub różne światy trasy");world=n.world();nodes.add(n);
        }
        for(int legIndex=0;legIndex<route.legs().size();legIndex++) {
            var leg=route.legs().get(legIndex);var segment=graph.segment(leg.segmentId());var a=nodes.get(legIndex);var b=nodes.get(legIndex+1);
            if(segment==null || !segment.isBuilt() || !segment.touches(a.id()) || !segment.other(a.id()).equals(b.id()) || leg.forward()!=segment.from().equals(a.id()))throw new IllegalArgumentException("Uszkodzone połączenie trasy");
            List<TrackCell> raw=segment.cells();
            if(raw.size()<2 || cells.size()+raw.size()>MAX_ROUTE_CELLS)throw new IllegalArgumentException("Nieprawidłowa liczba komórek trasy");
            saved.add(new Segment(segment,raw));
            double[] arc=offsets(raw);
            if(Math.abs(arc[arc.length-1]-leg.length())>1e-5)throw new IllegalArgumentException("Zmieniona długość trasy");
            for(int j=0;j<raw.size();j++) {
                int k=leg.forward()?j:raw.size()-1-j;TrackCell c=raw.get(k);
                if(j==0 && !at(c,a) || j==raw.size()-1 && !at(c,b))throw new IllegalArgumentException("Tor nie dochodzi do węzła");
                double offset=leg.start()+(leg.forward()?arc[k]:leg.length()-arc[k]);
                if(j==0 && !cells.isEmpty()) {
                    if(!same(c,cells.getLast()) || Math.abs(offset-offsets.getLast())>1e-5)throw new IllegalArgumentException("Przerwa między odcinkami");continue;
                }
                if(!cells.isEmpty())neighbour(cells.getLast(),c);
                cells.add(c);offsets.add(offset);
            }
        }
        var rails=new ArrayList<Rail>();
        Map<String,String> junctions=new HashMap<>();for(var n:nodes)if(n.type()==NodeType.JUNCTION)junctions.put(key(n.x(),n.y(),n.z()),n.id());
        for(int i=0;i<cells.size();i++) {
            TrackCell c=cells.get(i),prev=cells.get(Math.max(0,i-1)),next=cells.get(Math.min(cells.size()-1,i+1));
            if(i>0 && i<cells.size()-1 && prev.x()==next.x() && prev.z()==next.z())throw new IllegalArgumentException("Zawracający tor");
            boolean grade=prev.y()!=c.y() || next.y()!=c.y();
            if(grade && prev.x()!=next.x() && prev.z()!=next.z())throw new IllegalArgumentException("Łuk na pochyleniu");
            String shape=RailGeometry.shape(prev.x(),prev.y(),prev.z(),c.x(),c.y(),c.z(),next.x(),next.y(),next.z()).toUpperCase(Locale.ROOT);
            rails.add(new Rail(c,offsets.get(i),shape,junctions.get(key(c.x(),c.y(),c.z())),grade));
        }
        return new Index(rails,saved,nodes,route.length());
    }
    private static boolean at(TrackCell c,TrackNode n){return c.x()==n.x() && c.y()==n.y() && c.z()==n.z();}
    private static boolean same(TrackCell a,TrackCell b){return a.x()==b.x() && a.y()==b.y() && a.z()==b.z();}
    private static String key(int x,int y,int z){return x+":"+y+":"+z;}
    private static void neighbour(TrackCell a,TrackCell b){if(Math.abs((long)a.x()-b.x())+Math.abs((long)a.z()-b.z())!=1 || Math.abs((long)a.y()-b.y())>1)throw new IllegalArgumentException("Tor nie jest ciągły lub ma nadmierną pochyłość");}
    static double[] offsets(List<TrackCell> cells) {
        var samples=new ArrayList<Sample>();
        for(int i=0;i<cells.size();i++) {
            if(i>0)neighbour(cells.get(i-1),cells.get(i));var c=cells.get(i);
            samples.add(new Sample(new Vec3(c.x()+.5,c.y()+TrackSegment.RAIL_TOP+(TrackSegment.ascending(cells,i)?.5:0),c.z()+.5),i));
        }
        for(int pass=0;pass<2 && samples.size()>2;pass++) {
            var next=new ArrayList<Sample>();next.add(samples.getFirst());
            for(int i=0;i<samples.size()-1;i++) {
                var a=samples.get(i);var b=samples.get(i+1);
                for(double t:new double[]{.25,.75})next.add(new Sample(a.point.lerp(b.point,t),a.parameter+(b.parameter-a.parameter)*t));
            }
            next.add(samples.getLast());samples=next;
        }
        double[] cumulative=new double[samples.size()];for(int i=1;i<samples.size();i++)cumulative[i]=cumulative[i-1]+samples.get(i).point.distance(samples.get(i-1).point);
        double[] result=new double[cells.size()];int piece=0;
        for(int i=0;i<result.length;i++) {
            while(piece<samples.size()-2 && samples.get(piece+1).parameter<i)piece++;
            var a=samples.get(piece);var b=samples.get(piece+1);double t=(i-a.parameter)/(b.parameter-a.parameter);
            result[i]=cumulative[piece]+t*(cumulative[piece+1]-cumulative[piece]);
        }
        return result;
    }
}
