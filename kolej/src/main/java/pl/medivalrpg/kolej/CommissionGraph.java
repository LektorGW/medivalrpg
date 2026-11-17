// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import pl.medivalrpg.kolej.build.RailGeometry;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.network.*;

/** Pure import validation. Returned graph remains PLANNED until a separate live physical audit succeeds. */
final class CommissionGraph {
    record Segment(String id,String from,String to,List<TrackCell> cells,double speedLimit) {
        Segment(String id,String from,String to,List<TrackCell> cells){this(id,from,to,cells,0);}
    }
    record Plan(int version,String world,List<String> sourceJobs,List<TrackNode> nodes,List<Segment> segments,List<List<String>> connections) {}
    private record Position(int x,int y,int z) {}
    private record Use(String segment,String endpoint) {}
    static TrackGraph prepare(TrackGraph existing,Plan p,Set<String> completedJobs) {
        if(p==null || p.version()!=1 || p.world()==null || p.sourceJobs()==null || p.sourceJobs().isEmpty() || p.sourceJobs().size()>64 || new HashSet<>(p.sourceJobs()).size()!=p.sourceJobs().size()
            || !completedJobs.containsAll(p.sourceJobs()) || p.nodes()==null || p.nodes().isEmpty() || p.nodes().size()>128 || p.segments()==null || p.segments().isEmpty() || p.segments().size()>256 || p.connections()==null || p.connections().isEmpty() || p.connections().size()>64)
            throw new IllegalArgumentException("Niekompletny plan lub roboty fizyczne nie są ukończone");
        TrackGraph graph=new TrackGraph();graph.maxTurnDegrees(existing.maxTurnDegrees());
        existing.nodes().forEach(graph::putNode);existing.segments().forEach(graph::putSegment);
        Set<String> nodeIds=new HashSet<>(),segmentIds=new HashSet<>();Map<Position,String> nodePositions=new HashMap<>();
        for(var n:existing.nodes())if(p.world().equals(n.world()))nodePositions.put(new Position(n.x(),n.y(),n.z()),n.id());
        for(var n:p.nodes()) {
            if(n==null || !RailData.identifier(n.id()) || !p.world().equals(n.world()) || n.type()==null || !nodeIds.add(n.id()) || n.capacity()!=1 || (n.isWaitingPoint() && n.axis()!=0 && n.axis()!=1))throw new IllegalArgumentException("Nieprawidłowy węzeł");
            var old=graph.node(n.id());if(old!=null && !old.equals(n))throw new IllegalArgumentException("Węzeł koliduje z istniejącą siecią");
            String positionOwner=nodePositions.putIfAbsent(new Position(n.x(),n.y(),n.z()),n.id());
            if(positionOwner!=null && !positionOwner.equals(n.id()))throw new IllegalArgumentException("Dwa zasoby grafu na jednej fizycznej zwrotnicy");
            graph.putNode(n);
        }
        Map<Position,Use> used=new HashMap<>();int total=0;
        Set<String> replacing=new HashSet<>();for(var s:p.segments())if(s!=null)replacing.add(s.id());
        for(var s:existing.segments()) {
            var from=existing.node(s.from());if(replacing.contains(s.id()) || from==null || !p.world().equals(from.world()))continue;
            for(int i=0;i<s.cells().size();i++) {
                var c=s.cells().get(i);used.put(new Position(c.x(),c.y(),c.z()),new Use(s.id(),i==0?s.from():i==s.cells().size()-1?s.to():null));
            }
        }

        for(var s:p.segments()) {
            if(s==null || !Double.isFinite(s.speedLimit()) || s.speedLimit()<0 || s.speedLimit()>32 || !RailData.identifier(s.id()) || !segmentIds.add(s.id()) || s.from()==null || s.to()==null || s.from().equals(s.to()) || s.cells()==null || s.cells().size()<2 || (total+=s.cells().size())>32768)throw new IllegalArgumentException("Nieprawidłowy odcinek");
            var a=graph.node(s.from());var b=graph.node(s.to());
            if(a==null || b==null || !p.world().equals(a.world()) || !p.world().equals(b.world()) || !at(s.cells().getFirst(),a) || !at(s.cells().getLast(),b))throw new IllegalArgumentException("Końce toru nie pasują do węzłów");
            var old=graph.segment(s.id());
            if(old!=null && (!old.from().equals(s.from()) || !old.to().equals(s.to()) || !old.cells().equals(s.cells())))throw new IllegalArgumentException("Odcinek koliduje z istniejącą siecią");
            Set<Position> local=new HashSet<>();
            for(int i=0;i<s.cells().size();i++) {
                var c=s.cells().get(i);if(c==null || c.kind()==null || Math.abs((long)c.x())>30000000 || Math.abs((long)c.z())>30000000 || c.y()<-2048 || c.y()>2048)throw new IllegalArgumentException("Nieprawidłowa komórka");
                if(i>0){var prev=s.cells().get(i-1);if(Math.abs(prev.x()-c.x())+Math.abs(prev.z()-c.z())!=1 || Math.abs(prev.y()-c.y())>1)throw new IllegalArgumentException("Nieciągły tor");}
                var prev=s.cells().get(Math.max(0,i-1));var next=s.cells().get(Math.min(s.cells().size()-1,i+1));
                if((prev.y()!=c.y() || next.y()!=c.y()) && prev.x()!=next.x() && prev.z()!=next.z())throw new IllegalArgumentException("Łuk i nachylenie w jednej komórce");
                RailGeometry.shape(prev.x(),prev.y(),prev.z(),c.x(),c.y(),c.z(),next.x(),next.y(),next.z());
                Position key=new Position(c.x(),c.y(),c.z());if(!local.add(key))throw new IllegalArgumentException("Samoprzecięcie toru");
                String endpoint=i==0?s.from():i==s.cells().size()-1?s.to():null;
                Use before=used.putIfAbsent(key,new Use(s.id(),endpoint));
                if(before!=null && (endpoint==null || !endpoint.equals(before.endpoint())))throw new IllegalArgumentException("Wspólny tor ma niezależne zasoby; rozdziel graf na rzeczywistych zwrotnicach");
            }
            var segment=new TrackSegment(s.id(),s.from(),s.to());segment.cells(s.cells());segment.speedLimit(s.speedLimit());segment.status(SegmentStatus.PLANNED);graph.putSegment(segment);
        }
        for(var stops:p.connections()) {
            if(stops==null || stops.size()<2 || stops.size()>32 || stops.stream().anyMatch(id->graph.node(id)==null || !graph.node(id).isWaitingPoint()) || graph.routeThrough(stops,s->segmentIds.contains(s.id()) || s.isBuilt(),true).isEmpty())throw new IllegalArgumentException("Brak przejezdnego połączenia peronów w rzeczywistej geometrii grafu");
            var reversed=new ArrayList<>(stops);Collections.reverse(reversed);
            if(graph.routeThrough(reversed,s->segmentIds.contains(s.id()) || s.isBuilt(),true).isEmpty())throw new IllegalArgumentException("Brak drogi powrotnej");
        }
        return graph;
    }
    private static boolean at(TrackCell c,TrackNode n){return c!=null && c.x()==n.x() && c.y()==n.y() && c.z()==n.z();}
}
