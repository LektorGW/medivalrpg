// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.kolej.network.*;
class CommissionGraphTest {
    private TrackNode node(String id,int x,int z,boolean station){return new TrackNode(id,id,station?NodeType.STATION:NodeType.JUNCTION,"world",x,65,z,station?1:-1,1);}
    private List<TrackCell> cells(int from,int to){var out=new ArrayList<TrackCell>();for(int x=from;x<=to;x++)out.add(new TrackCell(x,65,0,CellKind.GROUND));return out;}
    private CommissionGraph.Plan plan(List<CommissionGraph.Segment> s){return new CommissionGraph.Plan(1,"world",List.of("physical"),List.of(node("a",0,0,true),node("j",10,0,false),node("b",20,0,true)),s,List.of(List.of("a","b")));}
    @Test void paidPhysicalJobsAndActualBidirectionalTopologyAreRequiredBeforeAudit() {
        var p=plan(List.of(new CommissionGraph.Segment("first","a","j",cells(0,10)),new CommissionGraph.Segment("second","j","b",cells(10,20))));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(new TrackGraph(),p,Set.of()));
        var graph=CommissionGraph.prepare(new TrackGraph(),p,Set.of("physical"));
        assertTrue(graph.segments().stream().noneMatch(TrackSegment::isBuilt));
        assertTrue(graph.routeThrough(List.of("a","b"),s->true,true).isPresent());
        assertTrue(graph.routeThrough(List.of("b","a"),s->true,true).isPresent());
    }
    @Test void overlappingPhysicalRailsCannotUseIndependentSignalResources() {
        var p=plan(List.of(new CommissionGraph.Segment("one","a","b",cells(0,20)),new CommissionGraph.Segment("two","a","b",cells(0,20))));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(new TrackGraph(),p,Set.of("physical")));
    }
    @Test void teleportingTrackAndExistingNodeAliasAreRejected() {
        var broken=plan(List.of(new CommissionGraph.Segment("jump","a","b",List.of(new TrackCell(0,65,0,CellKind.GROUND),new TrackCell(20,65,0,CellKind.GROUND)))));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(new TrackGraph(),broken,Set.of("physical")));
        var existing=new TrackGraph();existing.putNode(node("other",0,0,true));
        var valid=plan(List.of(new CommissionGraph.Segment("line","a","b",cells(0,20))));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(existing,valid,Set.of("physical")));
    }
    @Test void newEdgeCannotDuplicateAnExistingPhysicalTrackUnderAnotherId() {
        var existing=new TrackGraph();existing.putNode(node("a",0,0,true));existing.putNode(node("j",10,0,false));
        var old=new TrackSegment("existing","a","j");old.cells(cells(0,10));old.status(SegmentStatus.BUILT);existing.putSegment(old);
        var p=plan(List.of(new CommissionGraph.Segment("newid","a","b",cells(0,20))));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(existing,p,Set.of("physical")));
        assertTrue(existing.segment("existing").isBuilt());assertNull(existing.segment("newid"));
    }
    @Test void certifiedNodeNeedsEveryRealCompletedJobAndNeverEmptyOrDuplicateProof() {
        assertTrue(RailwayProjects.certifiedReady(List.of("hub1","hub2"),id->true));
        assertFalse(RailwayProjects.certifiedReady(List.of("hub1","hub2"),id->id.equals("hub1")));
        assertFalse(RailwayProjects.certifiedReady(List.of(),id->true));
        assertFalse(RailwayProjects.certifiedReady(null,id->true));
        assertFalse(RailwayProjects.certifiedReady(List.of("hub1","hub1"),id->true));
    }
    @Test void existingPlannedEdgeCannotPretendToCompleteRequestedConnection() {
        var old=new TrackGraph();var j=node("j",10,0,false);var b=node("b",20,0,true);old.putNode(j);old.putNode(b);
        var edge=new TrackSegment("old","j","b");edge.cells(cells(10,20));edge.status(SegmentStatus.PLANNED);old.putSegment(edge);
        var p=new CommissionGraph.Plan(1,"world",List.of("physical"),List.of(node("a",0,0,true),j),List.of(new CommissionGraph.Segment("new","a","j",cells(0,10))),List.of(List.of("a","b")));
        assertThrows(IllegalArgumentException.class,()->CommissionGraph.prepare(old,p,Set.of("physical")));
        edge.status(SegmentStatus.BUILT);assertNotNull(CommissionGraph.prepare(old,p,Set.of("physical")));
    }
}
