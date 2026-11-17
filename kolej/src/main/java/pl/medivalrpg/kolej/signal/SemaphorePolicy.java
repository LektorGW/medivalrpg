// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import pl.medivalrpg.kolej.network.*;

/** Read-only indication. A model never creates a reservation or changes a switch. */
public final class SemaphorePolicy {
    private SemaphorePolicy(){}
    public record Indication(boolean clear,String train,String reason) {
        public static Indication stop(String reason){return new Indication(false,null,reason);}
    }
    public record Candidate(String id,Route route,double center,double authority,boolean active) {}
    public static Indication evaluate(String node,String outgoing,TrackGraph graph,Interlocking locks,List<Candidate> trains) {
        TrackNode origin=graph.node(node);TrackSegment target=graph.segment(outgoing);
        if(origin==null || target==null || !target.isBuilt() || !target.touches(node))return Indication.stop("Brak poprawnego odcinka semafora");
        String permitted=null;
        try {
            for(Candidate c:trains) {
                if(c==null || !c.active() || c.id()==null || c.route()==null || !Double.isFinite(c.center()) || !Double.isFinite(c.authority()))continue;
                Route r=c.route();int i=r.nodes().indexOf(node);
                if(i<0 || i>=r.legs().size() || r.nodes().lastIndexOf(node)!=i || !outgoing.equals(r.legs().get(i).segmentId()))continue;
                var leg=r.legs().get(i);double at=r.nodeOffset(i);
                if(!target.other(node).equals(r.nodes().get(i+1)) || leg.forward()!=target.from().equals(node) || !Double.isFinite(leg.end()) || leg.length()<=0)continue;
                if(c.center()<0 || c.center()>at+.25 || at-c.center()>128 || c.authority()<leg.end()-1e-6 || c.authority()<=at+1)continue;
                // Require the actual exclusive direction and node claims, not merely an authority cached earlier.
                if(!locks.holders(node).equals(Set.of(c.id())) || !locks.segmentHolders(outgoing).equals(Map.of(c.id(),leg.forward())))continue;
                int entryBlock=leg.forward()?0:(int)Math.floor(Math.max(0,leg.length()-1e-6)/locks.blockLength());
                if(!c.id().equals(locks.blockOwner(outgoing,entryBlock)))continue;
                if(origin.type()==NodeType.JUNCTION && (i==0 || JunctionSwitch.routeShape(graph,r,i).isEmpty()))continue;
                // Every junction leading to the end of this selected outgoing leg must still be exclusively held.
                TrackNode end=graph.node(r.nodes().get(i+1));
                if(end==null || !origin.world().equals(end.world()) || end.type()==NodeType.JUNCTION && !locks.holders(end.id()).equals(Set.of(c.id())))continue;
                if(permitted!=null && !permitted.equals(c.id()))return Indication.stop("Niejednoznaczna rezerwacja przebiegu");
                permitted=c.id();
            }
        }catch(RuntimeException e){return Indication.stop("Nieprawidłowy przebieg semafora");}
        return permitted==null?Indication.stop("Brak aktualnego, fizycznie sprawdzonego przebiegu"):new Indication(true,permitted,"Zarezerwowany i sprawdzony tor");
    }
}
