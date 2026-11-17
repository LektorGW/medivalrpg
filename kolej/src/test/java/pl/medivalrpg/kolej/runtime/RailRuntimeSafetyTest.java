// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.Train;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.signal.Interlocking;
import pl.medivalrpg.kolej.steam.*;
import static org.junit.jupiter.api.Assertions.*;

class RailRuntimeSafetyTest {
    static TrackGraph graph(boolean junction){
        TrackGraph g=new TrackGraph();
        g.putNode(new TrackNode("a","a",NodeType.STATION,"world",0,65,0,0,1));
        g.putNode(new TrackNode("b","b",NodeType.STATION,"world",0,65,120,0,1));
        if(junction){g.putNode(new TrackNode("j","j",NodeType.JUNCTION,"world",0,65,60,0,1));segment(g,"aj","a","j",0,60);segment(g,"jb","j","b",60,120);}
        else segment(g,"ab","a","b",0,120);
        return g;
    }
    private static void segment(TrackGraph g,String id,String a,String b,int start,int end){
        TrackSegment s=new TrackSegment(id,a,b);List<TrackCell> cells=new ArrayList<>();
        for(int z=start;z<=end;z++)cells.add(new TrackCell(0,65,z,CellKind.GROUND));
        s.cells(cells);s.status(SegmentStatus.BUILT);g.putSegment(s);
    }
    static Route route(TrackGraph g,boolean reverse){
        boolean junction=g.node("j")!=null;
        return Route.of(g,junction?(reverse?List.of("b","j","a"):List.of("a","j","b")):(reverse?List.of("b","a"):List.of("a","b")),junction?(reverse?List.of("jb","aj"):List.of("aj","jb")):List.of("ab"));
    }
    static SteamState steam(double speed){SteamState s=SteamState.cold(LocomotiveSpec.defaults());s.speed=speed;s.throttle=1;return s;}
    @Test void revokedAuthorityNeverRewindsAndStopsGhostSpeed(){
        SteamState s=steam(12);
        assertEquals(30,RailRuntime.advance(30,1.2,20,s));assertEquals(0,s.speed);assertEquals(0,s.throttle);assertEquals(1,s.brake);assertTrue(s.emergency);
        s=steam(1);assertEquals(30,RailRuntime.advance(30,0,30,s));assertEquals(0,s.speed);
    }
    @Test void hardBoundaryCapsMovementAndRejectsNonfinitePhysics(){
        SteamState s=steam(8);assertEquals(31,RailRuntime.advance(30,2,31,s));assertEquals(0,s.speed);
        s=steam(8);assertEquals(30,RailRuntime.advance(30,Double.NaN,31,s));assertEquals(0,s.speed);
        s=steam(2);assertEquals(30.1,RailRuntime.advance(30,.1,50,s),1e-8);assertEquals(2,s.speed);
    }
    @Test void redSignalCannotSnapToPlatform(){
        assertFalse(RailRuntime.canArrive(118,120,0,118));
        assertTrue(RailRuntime.canArrive(118,120,0,120));
        assertFalse(RailRuntime.canArrive(121,120,0,130));
    }
    @Test void downhillBrakingCurveForcesEmergencyBeforeBoundary(){
        SteamState s=steam(8);RailRuntime.superviseSafety(s,20,.1);
        assertEquals(0,s.throttle);assertEquals(1,s.brake);assertTrue(s.emergency);
    }
    @Test void stoppedRouteIsRestorableAndStillProtectsTender(){
        TrackGraph g=graph(false);Train t=new Train();t.node="b";t.running=false;t.routeNodes=new ArrayList<>(List.of("a","b"));t.routeSegments=new ArrayList<>(List.of("ab"));t.distance=120;t.nextStop=2;
        Route restored=RailRuntime.restoreRoute(g,t);assertEquals(120,restored.length(),1e-8);
        double limit=RailRuntime.physicalLimit("following",route(g,false),20,15,List.of(new RailRuntime.Occupancy("stopped",restored,120,47,"b")),g);
        assertEquals(68,limit,1e-8); // conservative rear=73; nose offset 4 plus gap 1
        t.distance=Double.NaN;assertThrows(IllegalArgumentException.class,()->RailRuntime.restoreRoute(g,t));
    }
    @Test void restoredRouteRejectsDisconnectedEndpointsAndUnbuiltTrack(){
        TrackGraph g=graph(false);Train t=new Train();t.node="a";t.routeNodes=List.of("a","a");t.routeSegments=List.of("ab");
        assertThrows(IllegalArgumentException.class,()->RailRuntime.restoreRoute(g,t));
        t.routeNodes=List.of("a","b");g.segment("ab").status(SegmentStatus.PLANNED);
        assertThrows(IllegalArgumentException.class,()->RailRuntime.restoreRoute(g,t));
    }
    @Test void opposingRestoredTrainsAreBothBlockedEvenAcrossJunction(){
        for(boolean junction:new boolean[]{false,true}){
            TrackGraph g=graph(junction);
            var a=new RailRuntime.Occupancy("one",route(g,false),20,15,"a");
            var b=new RailRuntime.Occupancy("two",route(g,true),20,15,"b");
            assertEquals(Set.of("one","two"),RailRuntime.opposingOccupants(List.of(a,b),g));
        }
    }
    @Test void originDoesNotForceOpposingReservationButCommittedTrainIsGuarded(){
        TrackGraph g=graph(true);Interlocking signals=new Interlocking(g::node,32,12,6);
        Route reverse=route(g,true),forward=route(g,false);
        signals.update("other",reverse,24,9,40);
        assertFalse(RailRuntime.wouldForceOpposing(signals,"origin",forward,0,15,g));
        signals.update("origin",forward,4,-11,40);
        assertEquals(0,signals.conflicts());
        assertTrue(RailRuntime.wouldForceOpposing(signals,"origin",forward,20,15,g));
        assertEquals(0,signals.conflicts());
    }
    @Test void stationaryDestinationAndSameDirectionRearConstrainAuthority(){
        TrackGraph g=graph(false);Route r=route(g,false);
        assertEquals(100,RailRuntime.physicalLimit("a",r,10,15,List.of(new RailRuntime.Occupancy("b",null,0,15,"b")),g),1e-8);
        assertEquals(28,RailRuntime.physicalLimit("a",r,10,15,List.of(new RailRuntime.Occupancy("b",r,80,47,"a")),g),1e-8);
    }
}
