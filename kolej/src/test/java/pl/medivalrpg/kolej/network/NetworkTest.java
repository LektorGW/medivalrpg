// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.signal.Interlocking;
import static org.junit.jupiter.api.Assertions.*;

class NetworkTest {
    private TrackGraph graph() {
        TrackGraph g=new TrackGraph();
        for(int i=0;i<3;i++)g.putNode(new TrackNode("s"+i,"S"+i,NodeType.STATION,"world",i*200,64,0,1,1));
        for(int i=0;i<2;i++){TrackSegment s=new TrackSegment("t"+i,"s"+i,"s"+(i+1));List<TrackCell> cells=new ArrayList<>();for(int x=i*200;x<=i*200+200;x++)cells.add(new TrackCell(x,64,0,CellKind.GROUND));s.cells(cells);s.status(SegmentStatus.BUILT);g.putSegment(s);}
        return g;
    }
    @Test void routeRequiresCompletedTrackAndReturnsBothLegs() {
        TrackGraph g=graph();Route r=g.route("s0","s2",TrackSegment::isBuilt).orElseThrow();
        assertEquals(List.of("t0","t1"),r.segmentIds());assertEquals(400,r.length(),1);
        g.segment("t1").status(SegmentStatus.PLANNED);assertTrue(g.route("s0","s2",TrackSegment::isBuilt).isEmpty());
    }
    @Test void opposingTrainWaitsAtItsStation() {
        TrackGraph g=graph();Interlocking box=new Interlocking(g::node,32,12,6);
        Route a=g.route("s0","s2",TrackSegment::isBuilt).orElseThrow();
        Route b=g.route("s2","s0",TrackSegment::isBuilt).orElseThrow();
        box.update("a",a,0,-30,300);
        var limit=box.update("b",b,0,-30,300);
        assertTrue(limit.limit()<20,"opposing train must stay at station");assertEquals(0,box.conflicts());
    }
    @Test void releasingOccupancyAllowsFollowingTrain() {
        TrackGraph g=graph();Interlocking box=new Interlocking(g::node,32,12,6);Route r=g.route("s0","s2",TrackSegment::isBuilt).orElseThrow();
        box.update("a",r,100,60,60);var limited=box.update("b",r,0,-30,150);
        assertTrue(limited.limit()<100);box.release("a");var open=box.update("b",r,0,-30,150);assertTrue(open.limit()>limited.limit());
    }
}
