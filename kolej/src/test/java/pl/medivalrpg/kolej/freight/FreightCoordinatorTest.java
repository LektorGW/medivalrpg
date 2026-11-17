// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.Car;
import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.kolej.freight.FreightState.*;
import static pl.medivalrpg.kolej.freight.FreightRules.*;

class FreightCoordinatorTest {
    static final String WORLD="00000000-0000-0000-0000-000000000001";
    static Warehouse warehouse(String station,int x) {Warehouse w=new Warehouse();w.station=station;w.world=WORLD;w.x=x;w.receipt=UUID.randomUUID().toString();return w;}
    static FreightState copy(FreightState s){return FreightStore.GSON.fromJson(FreightStore.GSON.toJson(s),FreightState.class);}
    static class Disk {
        FreightState saved;
        record Write(FreightState snapshot,CompletableFuture<Void> done) {}
        Deque<Write> queue=new ArrayDeque<>();
        Disk(FreightState initial){saved=copy(initial);}
        CompletableFuture<Void> save(FreightState s){var f=new CompletableFuture<Void>();queue.add(new Write(copy(s),f));return f;}
        void ack(){Write w=queue.remove();saved=w.snapshot;w.done.complete(null);}
        void fail(){queue.remove().done.completeExceptionally(new IllegalStateException("disk unavailable"));}
    }
    static class World implements FreightCoordinator.Port {
        Map<String,List<Slot>> inventory=new HashMap<>();Map<String,String> receipts=new HashMap<>();
        Map<String,Integer> cargo=new LinkedHashMap<>();String destination,station="a",route="route";double distance,x;
        boolean stopped=true,moving,loaded=true;Set<String> unavailable=new HashSet<>();List<Car> cars=new ArrayList<>(List.of(Car.WEGLARKA));int applies;
        World(FreightState s){for(var w:s.warehouses.values()){receipts.put(w.station,w.receipt);inventory.put(w.station,new ArrayList<>(Collections.nCopies(27,Slot.empty())));}inventory.get("a").set(0,new Slot("COAL",64,true,64));}
        public FreightCoordinator.TrainView train(String id) {return new FreightCoordinator.TrainView(id,station,stopped,moving,route,distance,new JourneyProof.Position(WORLD,x,0,0),List.copyOf(cars),Map.copyOf(cargo),destination);}
        public JourneyProof.Position station(String id){return new JourneyProof.Position(WORLD,id.equals("a")?0:10,0,0);}
        public FreightCoordinator.Snapshot inventory(Warehouse w){return loaded&&!unavailable.contains(w.station)?new FreightCoordinator.Snapshot(w,receipts.get(w.station),List.copyOf(inventory.get(w.station)),null):null;}
        public boolean apply(FreightCoordinator.Snapshot before,List<Slot> after,String receipt){if(!inventory.get(before.warehouse().station).equals(before.slots()))return false;applies++;inventory.put(before.warehouse().station,new ArrayList<>(after));receipts.put(before.warehouse().station,receipt);return true;}
        public void project(String id,Map<String,Integer> cargo,String destination){this.cargo=new LinkedHashMap<>(cargo);this.destination=destination;}
        int total(){return cargo.values().stream().mapToInt(Integer::intValue).sum()+inventory.values().stream().flatMap(Collection::stream).mapToInt(Slot::amount).sum();}
    }
    static class Fixture {
        FreightState state=new FreightState();Disk disk;World world;FreightCoordinator engine;long now=1_000;
        Fixture(){state.warehouses.put("a",warehouse("a",0));state.warehouses.put("b",warehouse("b",10));disk=new Disk(state);world=new World(state);engine=new FreightCoordinator(world,CompletableFuture.completedFuture(state),disk::save);tick();}
        void tick(){engine.tick(now);}
        void ack(){disk.ack();tick();}
        void order(){engine.order("p1","COAL",40,"a","b");ack();}
        void load(){order();tick();assertNotNull(disk.queue.getFirst().snapshot.pending);assertEquals(0,world.applies);ack();assertEquals(40,world.cargo.get("COAL"));ack();}
        void travel(){
            world.stopped=false;world.moving=true;
            for(int i=1;i<=10;i++){world.x=i;world.distance=i;tick();}
            world.stopped=true;world.moving=false;world.station="b";
            while(!disk.queue.isEmpty())ack();
        }
        void settle(){for(int i=0;i<8;i++){tick();while(!disk.queue.isEmpty())ack();}}
        FreightCoordinator restart(){return new FreightCoordinator(world,CompletableFuture.completedFuture(copy(disk.saved)),disk::save);}
    }
    @Test void durableIntentPrecedesInventoryAndRealTripPrecedesDelivery() {
        Fixture f=new Fixture();f.load();assertEquals(64,f.world.total());assertEquals(24,f.world.inventory.get("a").getFirst().amount());assertEquals("b",f.world.destination);
        f.settle();assertEquals(1,f.world.applies);
        f.travel();f.settle();assertFalse(f.engine.blocked(),f.engine.status());assertEquals(2,f.world.applies);assertTrue(f.world.cargo.isEmpty());assertEquals(40,f.world.inventory.get("b").getFirst().amount());assertEquals(64,f.world.total());assertEquals(Stage.DONE,f.state.jobs.get("p1").stage);
        f.settle();assertEquals(2,f.world.applies);
    }
    @Test void teleportOrNodeRenameWithoutTravelDoesNotUnload() {
        Fixture f=new Fixture();f.load();f.world.station="b";f.world.x=10;f.world.distance=10;f.settle();
        assertEquals(1,f.world.applies);assertEquals(40,f.world.cargo.get("COAL"));
    }
    @Test void cannotLoadAtWrongStationOrWhileMoving() {
        Fixture f=new Fixture();f.order();f.world.station="b";f.world.x=10;f.settle();assertEquals(0,f.world.applies);
        f.world.station="a";f.world.x=0;f.world.stopped=false;f.world.moving=true;f.settle();assertEquals(0,f.world.applies);
    }
    @Test void departureDuringPreparedWriteAbortsWithoutRemovingItems() {
        Fixture f=new Fixture();f.order();f.tick();f.world.stopped=false;f.world.moving=true;f.ack();f.ack();assertEquals(0,f.world.applies);assertEquals(64,f.world.total());assertNull(f.disk.saved.pending);
    }
    @Test void hopperOrPlayerChangeDuringPreparedWriteAborts() {
        Fixture f=new Fixture();f.order();f.tick();f.world.inventory.get("a").set(0,new Slot("COAL",63,true,64));f.ack();f.ack();assertEquals(0,f.world.applies);assertTrue(f.world.cargo.isEmpty());assertNull(f.disk.saved.pending);
    }
    @Test void noStockOrFullDestinationNeverPartiallyTransfers() {
        Fixture f=new Fixture();f.world.inventory.get("a").set(0,new Slot("COAL",39,true,64));f.order();f.settle();assertEquals(0,f.world.applies);
        f.world.inventory.get("a").set(0,new Slot("COAL",64,true,64));f.tick();f.ack();f.ack();
        Collections.fill(f.world.inventory.get("b"),new Slot("STONE",64,true,64));f.travel();f.settle();assertEquals(1,f.world.applies);assertEquals(40,f.world.cargo.get("COAL"));
    }
    @Test void wagonRemovalDuringPreparedWriteAborts() {
        Fixture f=new Fixture();f.order();f.tick();f.world.cars.clear();f.ack();f.ack();assertEquals(0,f.world.applies);
    }
    @Test void crashAfterPreparedBeforeInventoryFreezesWithoutReplay() {
        Fixture f=new Fixture();f.order();f.tick();f.disk.ack(); // durable PREPARED, original engine never gets acknowledgement
        var restarted=f.restart();restarted.tick(f.now);assertTrue(restarted.blocked());assertEquals(0,f.world.applies);assertEquals(64,f.world.total());assertNotNull(f.disk.saved.pending);
    }
    @Test void crashAfterInventoryBeforeCommitFreezesWithoutReplay() {
        Fixture f=new Fixture();f.order();f.tick();f.ack();assertEquals(1,f.world.applies);assertNotNull(f.disk.saved.pending);
        var restarted=f.restart();restarted.tick(f.now);assertTrue(restarted.blocked());assertEquals(1,f.world.applies);assertEquals(64,f.world.total());
    }
    @Test void failedPreSaveDoesNotTouchInventoryAndFailedPostSaveDoesNotReplay() {
        Fixture f=new Fixture();f.order();f.tick();f.disk.fail();f.tick();assertTrue(f.engine.blocked());assertEquals(0,f.world.applies);
        Fixture g=new Fixture();g.order();g.tick();g.ack();g.disk.fail();g.tick();assertTrue(g.engine.blocked());assertEquals(1,g.world.applies);assertEquals(64,g.world.total());assertNotNull(g.disk.saved.pending);
    }
    @Test void completedCommitButRolledBackChunkReceiptIsQuarantined() {
        Fixture f=new Fixture();String old=f.world.receipts.get("a");f.load();f.world.receipts.put("a",old);
        var restarted=f.restart();restarted.tick(f.now);assertTrue(restarted.blocked());assertEquals(1,f.world.applies);
    }
    @Test void completedCommitRestoresOnlyOurStaleTrainProjection() {
        Fixture f=new Fixture();f.load();f.world.cargo.clear();f.world.destination=null;
        var restarted=f.restart();restarted.tick(f.now);assertFalse(restarted.blocked());assertEquals(40,f.world.cargo.get("COAL"));assertEquals("b",f.world.destination);assertEquals(1,f.world.applies);
    }
    @Test void foreignCargoIsNeverOverwritten() {
        Fixture f=new Fixture();f.load();f.world.cargo.put("DIAMOND",1);var restarted=f.restart();restarted.tick(f.now);assertTrue(restarted.blocked());assertEquals(1,f.world.cargo.get("DIAMOND"));
    }
    @Test void unavailableChunksWaitWithoutLoadingOrMutating() {
        Fixture f=new Fixture();f.order();f.world.loaded=false;f.settle();assertFalse(f.engine.blocked());assertEquals(0,f.world.applies);
    }
    @Test void remoteDestinationMayBeUnloadedDuringLoadAndSourceMayUnloadDuringTravel() {
        Fixture f=new Fixture();f.world.unavailable.add("b");f.load();
        f.world.unavailable.clear();f.world.unavailable.add("a");f.travel();f.settle();
        assertFalse(f.engine.blocked(),f.engine.status());assertEquals(2,f.world.applies);assertEquals(64,f.world.total());
    }
    @Test void restartRestoresMassButRequiresSourceReceiptBeforeReleasingInventory() {
        Fixture f=new Fixture();f.load();f.world.cargo.clear();f.world.destination=null;f.world.unavailable.add("a");
        var restarted=f.restart();restarted.tick(f.now);
        assertEquals(40,f.world.cargo.get("COAL"));assertEquals(1,f.world.applies);assertFalse(restarted.blocked());
        f.world.unavailable.clear();f.world.receipts.put("a",UUID.randomUUID().toString());restarted.tick(f.now);assertTrue(restarted.blocked());assertEquals(1,f.world.applies);
    }
    @Test void explicitRegularityWaitsForDueTimeAndSourceReturn() {
        Fixture f=new Fixture();f.order();f.engine.regularity("p1",2,f.now);f.ack();f.tick();f.ack();f.ack();f.travel();f.settle();
        assertEquals(Stage.SOURCE,f.state.jobs.get("p1").stage);assertEquals(121_000,f.state.jobs.get("p1").nextAt);
        f.world.inventory.get("a").set(0,new Slot("COAL",64,true,64));f.world.station="a";f.world.x=0;f.settle();assertEquals(2,f.world.applies);
        f.now=121_000;f.settle();assertEquals(3,f.world.applies);assertEquals(40,f.world.cargo.get("COAL"));
    }
    @Test void duplicateJobsAndWrongWagonTypeAreRejected() {
        Fixture f=new Fixture();f.order();assertThrows(IllegalArgumentException.class,()->f.engine.order("p1","COAL",1,"a","b"));
        Fixture g=new Fixture();g.world.cars=List.of(Car.PASAZERSKI);assertThrows(IllegalArgumentException.class,()->g.engine.order("p1","COAL",1,"a","b"));
        assertThrows(IllegalArgumentException.class,()->g.engine.order("p1","COAL",1,"a","a"));
    }
    @Test void closeNeverAppliesAnUnacknowledgedPreparedTransfer() {
        Fixture f=new Fixture();f.order();f.tick();f.engine.close();f.disk.ack();f.tick();assertEquals(0,f.world.applies);assertNotNull(f.disk.saved.pending);
    }
}
