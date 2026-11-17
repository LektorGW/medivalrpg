// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.kolej.steam.*;
class RouteSpeedTest {
    private TrackGraph graph(){
        var g=new TrackGraph();for(int i=0;i<4;i++)g.putNode(new TrackNode("n"+i,"n"+i,NodeType.STATION,"world",i*100,65,0,1,1));
        for(int i=0;i<3;i++){var s=new TrackSegment("s"+i,"n"+i,"n"+(i+1));var cells=new ArrayList<TrackCell>();for(int x=i*100;x<=(i+1)*100;x++)cells.add(new TrackCell(x,65,0,CellKind.GROUND));s.cells(cells);s.status(SegmentStatus.BUILT);s.speedLimit(i==1?1:0);g.putSegment(s);}return g;
    }
    @Test void brakesBeforeRestrictionAndKeepsItUntilLastWagonLeaves(){
        var g=graph();var r=Route.of(g,List.of("n0","n1","n2","n3"),List.of("s0","s1","s2"));
        assertEquals(12,RouteSpeed.allowed(g,r,-1000,20,12,.6));
        assertTrue(RouteSpeed.allowed(g,r,90,20,12,.6)<4);
        assertEquals(1,RouteSpeed.allowed(g,r,150,20,12,.6));
        assertEquals(1,RouteSpeed.allowed(g,r,205,20,12,.6));
        assertEquals(12,RouteSpeed.allowed(g,r,221,20,12,.6));
    }
    @Test void invalidLimitFailsClosedAndManualRegulatorCannotOverride(){
        var g=graph();var r=Route.of(g,List.of("n0","n1"),List.of("s0"));g.segment("s0").speedLimit(Double.NaN);
        assertEquals(0,RouteSpeed.allowed(g,r,1,20,12,.6));
        var s=SteamState.cold(LocomotiveSpec.defaults());s.speed=5;s.throttle=1;s.brake=0;
        assertTrue(AutoDriver.enforceSpeed(s,1));assertEquals(0,s.throttle);assertEquals(1,s.brake);
    }
}
