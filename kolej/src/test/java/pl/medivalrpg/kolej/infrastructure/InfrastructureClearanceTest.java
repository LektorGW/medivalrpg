// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.Depot;
import pl.medivalrpg.kolej.infrastructure.InfrastructureState.Fixture;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class InfrastructureClearanceTest {
    private Fixture pump(int x,int z,int rotation){return new Fixture("pump","station",UUID.randomUUID().toString(),x,64,z,rotation,SupplyKind.POMPA,"BARREL",UUID.randomUUID().toString());}
    private TrackGraph station(int axis){var g=new TrackGraph();g.putNode(new TrackNode("station","station",NodeType.STATION,"world",0,65,0,axis,1));return g;}
    @Test void stationTrackIsProtectedAwayFromNodeWithoutAnySegment(){
        var g=station(0);assertTrue(g.segments().isEmpty());
        assertTrue(InfrastructureClearance.conflicts(pump(2,10,0),"world",g,Map.of()));
        assertTrue(InfrastructureClearance.conflicts(pump(2,-63,0),"world",g,Map.of()));
        assertFalse(InfrastructureClearance.conflicts(pump(5,10,0),"world",g,Map.of()));
    }
    @Test void stationAxisRotationAndOtherWorldsAreRespected(){
        var g=station(1);
        assertTrue(InfrastructureClearance.conflicts(pump(10,2,1),"world",g,Map.of()));
        assertFalse(InfrastructureClearance.conflicts(pump(10,5,1),"world",g,Map.of()));
        assertFalse(InfrastructureClearance.conflicts(pump(10,2,1),"other",g,Map.of()));
    }
    @Test void depotApproachAndSweptBridgeAreProtectedWithoutGraphSegments(){
        var g=new TrackGraph();g.putNode(new TrackNode("base","base",NodeType.DEPOT,"world",118,65,100,1,1));
        var depots=Map.of("base",new Depot(100,64,100,1));
        assertTrue(InfrastructureClearance.conflicts(pump(122,100,0),"world",g,depots));
        assertTrue(InfrastructureClearance.conflicts(pump(100,100,0),"world",g,depots));
        assertFalse(InfrastructureClearance.conflicts(pump(140,140,0),"world",g,depots));
    }
}
