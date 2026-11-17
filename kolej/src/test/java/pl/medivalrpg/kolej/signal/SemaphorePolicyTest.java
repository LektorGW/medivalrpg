// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class SemaphorePolicyTest {
    private PhysicalRailGuardTest.Fixture fixture(){return PhysicalRailGuardTest.fixture(PhysicalRailGuardTest.straight(120),false);}
    private SemaphorePolicy.Candidate candidate(Route r,double authority){return new SemaphorePolicy.Candidate("train",r,0,authority,true);}
    @Test void onlyRealExclusiveReservationWithSufficientPhysicalProofCanClear() {
        var f=fixture();var locks=new Interlocking(f.graph()::node,32,12,6);var c=candidate(f.route(),120);
        assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(c)).clear());
        locks.update("train",f.route(),4,-15,150);
        assertTrue(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(c)).clear());
        locks.release("train");assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(c)).clear());
    }
    @Test void insufficientPhysicalProofMissingRailAndUnloadedChunkRemainStop() {
        var f=fixture();var locks=new Interlocking(f.graph()::node,32,12,6);locks.update("train",f.route(),4,-15,150);
        for(String reason:List.of("Brak fizycznej szyny","Chunk niewczytany","Przeszkoda w skrajni")) {
            var proof=f.index().inspect(f.graph(),0,120,r->r.cell().z()==50?reason:null);
            assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(candidate(f.route(),proof.limit()))).clear());
        }
    }
    @Test void stoppedInactiveOrPassedTrainCannotKeepGreen() {
        var f=fixture();var locks=new Interlocking(f.graph()::node,32,12,6);locks.update("train",f.route(),4,-15,150);
        assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(new SemaphorePolicy.Candidate("train",f.route(),0,120,false))).clear());
        assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(new SemaphorePolicy.Candidate("train",f.route(),1,120,true))).clear());
        assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(candidate(f.route(),Double.NaN))).clear());
    }
    @Test void wrongDirectionOrConflictingHolderCannotClear() {
        var f=fixture();var locks=new Interlocking(f.graph()::node,32,12,6);
        var reverse=Route.of(f.graph(),List.of("b","a"),List.of("ab"));locks.update("other",reverse,24,9,150);
        assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(candidate(f.route(),120))).clear());
    }
    @Test void unbuiltMissingAndMalformedRouteStayStopEvenWithOldAuthority() {
        var f=fixture();var locks=new Interlocking(f.graph()::node,32,12,6);locks.update("train",f.route(),4,-15,150);
        var malformed=Route.of(f.graph(),List.of("a","a"),List.of("ab"));assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(candidate(malformed,120))).clear());
        f.graph().segment("ab").status(SegmentStatus.PLANNED);assertFalse(SemaphorePolicy.evaluate("a","ab",f.graph(),locks,List.of(candidate(f.route(),120))).clear());
        assertFalse(SemaphorePolicy.evaluate("missing","ab",f.graph(),locks,List.of()).clear());
    }
    @Test void junctionRequiresActualReservedSwitchShapeRatherThanGraphBuiltFlag() {
        var g=new TrackGraph();g.putNode(new TrackNode("a","a",NodeType.STATION,"world",0,65,0,0,1));g.putNode(new TrackNode("j","j",NodeType.JUNCTION,"world",0,65,10,-1,1));g.putNode(new TrackNode("b","b",NodeType.STATION,"world",10,65,10,1,1));
        var incoming=new TrackSegment("aj","a","j");var out=new TrackSegment("jb","j","b");var a=new ArrayList<TrackCell>();var b=new ArrayList<TrackCell>();
        for(int i=0;i<=10;i++){a.add(new TrackCell(0,65,i,CellKind.GROUND));b.add(new TrackCell(i,65,10,CellKind.GROUND));}
        incoming.cells(a);incoming.status(SegmentStatus.BUILT);out.cells(b);out.status(SegmentStatus.BUILT);g.putSegment(incoming);g.putSegment(out);
        var route=Route.of(g,List.of("a","j","b"),List.of("aj","jb"));var index=PhysicalRailGuard.index(g,route);var locks=new Interlocking(g::node,32,12,6);locks.update("train",route,4,-15,50);
        assertEquals("NORTH_EAST",index.rails().stream().filter(r->"j".equals(r.junction())).findFirst().orElseThrow().shape());
        var wrong=index.inspect(g,0,50,r->"j".equals(r.junction())?"Zwrotnica ustawiona inaczej":null);
        assertFalse(SemaphorePolicy.evaluate("j","jb",g,locks,List.of(candidate(route,wrong.limit()))).clear());
        var correct=index.inspect(g,0,50,r->null);assertTrue(SemaphorePolicy.evaluate("j","jb",g,locks,List.of(candidate(route,correct.limit()))).clear());
        locks.release("train");assertFalse(SemaphorePolicy.evaluate("j","jb",g,locks,List.of(candidate(route,correct.limit()))).clear());
    }
}
