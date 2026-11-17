// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalRailGuardTest {
    record Fixture(TrackGraph graph,Route route,PhysicalRailGuard.Index index) {}
    static Fixture fixture(List<TrackCell> cells,boolean reverse) {
        TrackGraph g=new TrackGraph();var a=cells.getFirst();var b=cells.getLast();
        g.putNode(new TrackNode("a","a",NodeType.STATION,"world",a.x(),a.y(),a.z(),0,1));
        g.putNode(new TrackNode("b","b",NodeType.STATION,"world",b.x(),b.y(),b.z(),0,1));
        var s=new TrackSegment("ab","a","b");s.cells(cells);s.status(SegmentStatus.BUILT);g.putSegment(s);
        Route r=Route.of(g,reverse?List.of("b","a"):List.of("a","b"),List.of("ab"));
        return new Fixture(g,r,PhysicalRailGuard.index(g,r));
    }
    static List<TrackCell> straight(int length){var cells=new ArrayList<TrackCell>();for(int z=0;z<=length;z++)cells.add(new TrackCell(0,65,z,CellKind.GROUND));return cells;}
    @Test void missingOrdinaryRailStopsBeforeItEvenThoughGraphStillBuilt() {
        var f=fixture(straight(120),false);
        var result=f.index.inspect(f.graph,0,100,r->r.cell().z()==40?"Brak fizycznej szyny":null);
        assertTrue(f.graph.segment("ab").isBuilt());assertEquals(33.5,result.limit(),1e-8);assertFalse(result.clear());assertEquals(41,result.checked());
    }
    @Test void unloadedChunkCannotBeTreatedAsPassableTrack() {
        var f=fixture(straight(120),false);AtomicInteger reads=new AtomicInteger();
        var result=f.index.inspect(f.graph,0,100,r->{reads.incrementAndGet();return r.cell().z()>=32?"Chunk niewczytany":null;});
        assertEquals(25.5,result.limit(),1e-8);assertEquals(33,reads.get());assertFalse(result.clear());
    }
    @Test void reverseRouteFindsTheCorrectPhysicalHoleAndDoesNotReverseShapeNames() {
        var f=fixture(straight(120),true);
        var result=f.index.inspect(f.graph,0,100,r->{assertEquals("NORTH_SOUTH",r.shape());return r.cell().z()==80?"Brak szyny":null;});
        assertEquals(33.5,result.limit(),1e-8);assertEquals(120,f.index.rails().getFirst().cell().z());assertEquals(0,f.index.rails().getFirst().offset());
    }
    @Test void canonicalCurveIsCheckedRatherThanFlooredSmoothedCoordinates() {
        var cells=List.of(cell(0,0),cell(0,1),cell(0,2),cell(1,2),cell(2,2),cell(3,2));var f=fixture(cells,false);var checked=new ArrayList<TrackCell>();
        var result=f.index.inspect(f.graph,0,100,r->{checked.add(r.cell());return null;});
        assertEquals(cells,checked);assertEquals(f.route.length(),result.limit(),1e-8);assertTrue(result.clear());
        assertEquals("NORTH_EAST",f.index.rails().get(2).shape());
        double previous=-1;for(var r:f.index.rails()){assertTrue(r.offset()>previous);previous=r.offset();}
    }
    @Test void slopeCellsFollowExactSmoothedArcLengthsAndAscendingDirections() {
        var cells=List.of(new TrackCell(0,65,0,CellKind.GROUND),new TrackCell(0,65,1,CellKind.GROUND),new TrackCell(0,66,2,CellKind.GROUND),new TrackCell(0,66,3,CellKind.GROUND));
        for(boolean reverse:new boolean[]{false,true}) {
            var f=fixture(cells,reverse);var rail=f.index.rails().stream().filter(r->r.cell().z()==1).findFirst().orElseThrow();
            assertEquals("ASCENDING_SOUTH",rail.shape());assertTrue(rail.grade());
            assertEquals(f.route.length(),f.index.rails().getLast().offset(),1e-8);
            assertTrue(f.index.inspect(f.graph,0,100,r->null).clear());
        }
    }
    @Test void physicalWrongShapeOrHeadroomObstructionCapsAuthority() {
        var f=fixture(straight(80),false);
        for(String issue:List.of("Niezgodne ułożenie szyny","Przeszkoda w skrajni"))assertEquals(13.5,f.index.inspect(f.graph,0,80,r->r.cell().z()==20?issue:null).limit(),1e-8);
    }
    @Test void currentHoleNeverRewindsButForbidsAnyMoreMovement() {
        var f=fixture(straight(120),false);var result=f.index.inspect(f.graph,40,80,r->"Brak toru");
        assertEquals(40,result.limit());assertFalse(result.clear());
    }
    @Test void perTickProofHorizonAndProbeCountAreBounded() {
        var cells=new ArrayList<TrackCell>();int x=0,z=0;cells.add(cell(x,z));for(int i=0;i<1000;i++){if(i%2==0)x++;else z++;cells.add(cell(x,z));}
        var f=fixture(cells,false);var result=f.index.inspect(f.graph,0,1_000_000,r->null);
        assertTrue(result.checked()<=PhysicalRailGuard.MAX_PROBES);assertTrue(result.limit()<=PhysicalRailGuard.MAX_AHEAD);assertTrue(result.limit()>0);
    }
    @Test void staleGraphGeometryIsRejectedWithoutCallingBukkitProbe() {
        var f=fixture(straight(80),false);f.graph.segment("ab").cells(new ArrayList<>(f.graph.segment("ab").cells()));
        var result=f.index.inspect(f.graph,0,80,r->{fail("No probe of stale route");return null;});assertEquals(0,result.limit());assertFalse(result.clear());
    }
    @Test void malformedCanonicalCellsAreRejectedBeforeWorldReads() {
        assertThrows(IllegalArgumentException.class,()->fixture(List.of(cell(0,0),cell(1,1)),false));
        assertThrows(IllegalArgumentException.class,()->fixture(List.of(cell(0,0),cell(0,1),cell(0,0)),false));
        assertThrows(IllegalArgumentException.class,()->fixture(List.of(cell(0,0),new TrackCell(0,67,1,CellKind.GROUND)),false));
    }
    @Test void nonFiniteCursorAndProbeFailureFailClosed() {
        var f=fixture(straight(80),false);assertFalse(f.index.inspect(f.graph,Double.NaN,80,r->null).clear());
        assertEquals(0,f.index.inspect(f.graph,0,80,r->{throw new IllegalStateException("world unavailable");}).limit());
    }
    private static TrackCell cell(int x,int z){return new TrackCell(x,65,z,CellKind.GROUND);}
}
