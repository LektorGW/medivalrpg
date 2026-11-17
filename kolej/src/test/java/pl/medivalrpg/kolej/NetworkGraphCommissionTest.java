// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.kolej.network.*;
class NetworkGraphCommissionTest {
    @Test void stationAuditIncludesWholePhysicalPlatformTrackAndIgnoresOtherWorldJunctions(){
        var node=new TrackNode("p1","Peron",NodeType.STATION,"world",0,65,0,1,1);
        var cells=List.of(new TrackCell(0,65,0,CellKind.PLATFORM),new TrackCell(1,65,0,CellKind.PLATFORM));
        var plan=new CommissionGraph.Plan(1,"world",List.of("hub"),List.of(node),List.of(new CommissionGraph.Segment("track","p1","p1",cells)),List.of());
        var g=new TrackGraph();g.putNode(node);g.putNode(new TrackNode("foreign","Foreign",NodeType.JUNCTION,"other",1,65,0,-1,1));
        var checks=NetworkGraphCommission.checks(g,plan);
        assertEquals(129,checks.size());assertTrue(checks.stream().anyMatch(c->c.cell().x()==-64));assertTrue(checks.stream().anyMatch(c->c.cell().x()==64));
        assertTrue(checks.stream().noneMatch(NetworkGraphCommission.Check::junction));
        assertTrue(checks.stream().allMatch(c->c.shape().equals("EAST_WEST")));
    }
    @Test void previouslyInspectedMutationIsRecheckedBeforeForwardProgressAndPublication(){
        var audit=new NetworkGraphCommission.AuditProgress();List<Integer> reads=new ArrayList<>();
        assertEquals(64,NetworkGraphCommission.runBatch(audit,100,i->true,reads::add));assertEquals(64,audit.cursor());
        audit.invalidated(2);audit.invalidated(2);audit.invalidated(90);audit.invalidated(-1);
        assertEquals(1,audit.dirtyCount());reads.clear();
        assertEquals(37,NetworkGraphCommission.runBatch(audit,100,i->true,reads::add));
        assertEquals(2,reads.getFirst());assertEquals(64,reads.get(1));assertTrue(audit.complete(100));
        audit.invalidated(0);assertFalse(audit.complete(100));
        reads.clear();NetworkGraphCommission.runBatch(audit,100,i->true,reads::add);
        assertEquals(List.of(0),reads);assertTrue(audit.complete(100));
    }
    @Test void unloadedDirtyCheckRemainsQueuedAndPreventsAnyForwardRead(){
        var audit=new NetworkGraphCommission.AuditProgress();NetworkGraphCommission.runBatch(audit,80,i->true,i->{});
        audit.invalidated(5);int[] reads={0};
        assertEquals(0,NetworkGraphCommission.runBatch(audit,80,i->i!=5,i->reads[0]++));
        assertEquals(0,reads[0]);assertEquals(64,audit.cursor());assertEquals(1,audit.dirtyCount());assertFalse(audit.complete(80));
        List<Integer> resumed=new ArrayList<>();NetworkGraphCommission.runBatch(audit,80,i->true,resumed::add);
        assertEquals(5,resumed.getFirst());assertTrue(audit.complete(80));
    }
    @Test void dirtyRechecksShareThe64CheckTickBudgetAndDoNotAdvanceTheCursor(){
        var audit=new NetworkGraphCommission.AuditProgress();
        NetworkGraphCommission.runBatch(audit,200,i->true,i->{});NetworkGraphCommission.runBatch(audit,200,i->true,i->{});
        for(int i=0;i<80;i++)audit.invalidated(i);
        List<Integer> reads=new ArrayList<>();assertEquals(64,NetworkGraphCommission.runBatch(audit,200,i->true,reads::add));
        assertEquals(128,audit.cursor());assertEquals(16,audit.dirtyCount());assertEquals(63,reads.getLast());
        reads.clear();assertEquals(64,NetworkGraphCommission.runBatch(audit,200,i->true,reads::add));
        assertEquals(64,reads.getFirst());assertEquals(128,reads.get(16));assertEquals(176,audit.cursor());
    }
    @Test void mutationRaisedDuringReinspectionIsNotLostByClearingTheOldDirtyMarker(){
        var audit=new NetworkGraphCommission.AuditProgress();NetworkGraphCommission.runBatch(audit,1,i->true,i->{});
        audit.invalidated(0);int[] attempts={0};
        assertEquals(2,NetworkGraphCommission.runBatch(audit,1,i->true,i->{if(attempts[0]++==0)audit.invalidated(i);}));
        assertTrue(audit.complete(1));
        audit.invalidated(0);assertEquals(64,NetworkGraphCommission.runBatch(audit,1,i->true,audit::invalidated));
        assertFalse(audit.complete(1));assertEquals(1,audit.dirtyCount());
    }
    @Test void watcherVolumesCoverSupportRailAndFullGradedClearance(){
        var flat=new NetworkGraphCommission.Check(new TrackCell(0,70,0,CellKind.GROUND),"NORTH_SOUTH",false,false);
        var grade=new NetworkGraphCommission.Check(new TrackCell(1,70,0,CellKind.GROUND),"ASCENDING_EAST",true,false);
        var volumes=NetworkGraphCommission.watchVolumes(List.of(flat,grade));
        assertEquals(new AuditMutationWatch.Volume(0,70,0,4,0),volumes.get(0));
        assertEquals(new AuditMutationWatch.Volume(1,70,0,5,1),volumes.get(1));
    }
    @Test void finalPermissionsIncludeUniqueTouchedChunksOnBothSidesOfNegativeChunkBoundaries(){
        var cell=new TrackCell(0,70,0,CellKind.GROUND);
        var checks=List.of(new NetworkGraphCommission.Check(cell,"NORTH_SOUTH",false,false),new NetworkGraphCommission.Check(new TrackCell(1,70,1,CellKind.GROUND),"NORTH_SOUTH",false,false));
        var points=NetworkGraphCommission.permissionPoints(checks);Set<String> chunks=new HashSet<>();
        for(var point:points)assertTrue(chunks.add((point.x()>>4)+":"+(point.z()>>4)),"one current permission lookup per touched chunk");
        assertEquals(Set.of("-1:-1","-1:0","0:-1","0:0"),chunks);assertEquals(4,points.size());
    }
    @Test void finalBorderVisitsEveryActualRailAndItsFootprintWithinTheSameChunk(){
        var checks=List.of(borderCheck(1,1),borderCheck(14,14));Set<String> visited=new HashSet<>();
        World world=borderWorld(()->border(location->{visited.add(location.getBlockX()+":"+location.getBlockZ());return true;}));
        NetworkGraphCommission.validateCurrentBorder(world,checks);
        assertEquals(18,visited.size());
        assertTrue(visited.containsAll(Set.of("1:1","14:14","0:0","2:2","13:13","15:15")));
    }
    @Test void finalBorderReReadsCurrentBorderAndRejectsLaterRailInAnOtherwiseAllowedChunk(){
        var checks=List.of(borderCheck(1,1),borderCheck(14,14));
        WorldBorder[] current={border(location->true)};World world=borderWorld(()->current[0]);
        NetworkGraphCommission.validateCurrentBorder(world,checks); // Earlier audit/publication metadata was valid.
        current[0]=border(location->location.getBlockX()<14);
        assertThrows(IllegalStateException.class,()->NetworkGraphCommission.validateCurrentBorder(world,checks));
    }
    @Test void finalBorderRejectsSupportFootprintOutsideWhileActualRailRemainsInside(){
        var check=borderCheck(0,0);Predicate<Location> inside=location->location.getBlockX()>=0;
        World world=borderWorld(()->border(inside));
        assertTrue(inside.test(new Location(world,0,70,0)));
        assertThrows(IllegalStateException.class,()->NetworkGraphCommission.validateCurrentBorder(world,List.of(check)));
    }
    private static NetworkGraphCommission.Check borderCheck(int x,int z){
        return new NetworkGraphCommission.Check(new TrackCell(x,70,z,CellKind.GROUND),"NORTH_SOUTH",false,false);
    }
    // Strict proxies fail on block/chunk reads: final access validation must remain metadata-only.
    private static World borderWorld(Supplier<WorldBorder> current){
        return (World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(proxy,method,args)->{
            if(method.getName().equals("getWorldBorder"))return current.get();
            throw new AssertionError("Unexpected world access: "+method.getName());
        });
    }
    private static WorldBorder border(Predicate<Location> inside){
        return (WorldBorder)Proxy.newProxyInstance(WorldBorder.class.getClassLoader(),new Class<?>[]{WorldBorder.class},(proxy,method,args)->{
            if(method.getName().equals("isInside"))return inside.test((Location)args[0]);
            throw new AssertionError("Unexpected border access: "+method.getName());
        });
    }
}
