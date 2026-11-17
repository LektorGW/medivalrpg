// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.szlaki;

import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.szlaki.Model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModelTest {
    private static final UUID OWNER=UUID.randomUUID(), ENEMY=UUID.randomUUID();
    private Route route() {
        Route r=new Route(); r.id="trakt"; r.manager=UUID.randomUUID(); r.country=OWNER; r.destinationCountry=OWNER; r.mode=Mode.ROAD;
        r.material="WHEAT"; r.quantity=64; r.intervalSeconds=300; r.fee=12;
        r.points.add(new Point("world",0.5,65,0.5)); r.points.add(new Point("world",8.5,65,0.5));
        r.source=new Chest("world",0,65,1); r.destination=new Chest("world",8,65,1); return r;
    }
    @Test void validManualRoute() { assertDoesNotThrow(route()::validate); }
    @Test void longSegmentRejected() { Route r=route(); r.points.set(1,new Point("world",9,65,.5)); assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void crossWorldRejected() { Route r=route(); r.points.set(1,new Point("nether",8.5,65,.5)); assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void nanRejected() { Route r=route(); r.points.set(0,new Point("world",Double.NaN,65,0)); assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void remoteChestRejected() { Route r=route(); r.destination=new Chest("world",100,65,1); assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void sameChestRejected() { Route r=route(); r.destination=r.source; assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void invalidStopRejected() { Route r=route(); r.stops.add(2); assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void invalidFrequencyRejected() { Route r=route(); r.intervalSeconds=0; assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void oversizedManifestRejected() { Route r=route(); r.quantity=1729; assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void negativeFeeRejected() { Route r=route(); r.fee=-1; assertThrows(IllegalArgumentException.class,r::validate); }
    @Test void editsRequireApprovalAndStopSchedule() { Route r=route(); r.enabled=true; r.approved=true; r.edited(); assertFalse(r.enabled); assertFalse(r.approved); }
    @Test void negativeChunkCoordinatesFloor() { assertEquals(-1,new Point("world",-.1,65,-.1).cx()); assertEquals(-2,new Point("world",-16.1,65,-16.1).cz()); }
    @Test void insufficientStockDoesNotMutate() { int[] a={4,5}; assertNull(take(a,10)); assertArrayEquals(new int[]{4,5},a); }
    @Test void fullDestinationDoesNotPartiallyInsert() { int[] a={63,64,-1}; assertNull(put(a,2,64)); assertArrayEquals(new int[]{63,64,-1},a); }
    @Test void metadataSlotsRemainUntouched() { assertArrayEquals(new int[]{64,-1,1},put(new int[]{63,-1,0},2,64)); }
    @Test void zeroCargoRejected() { assertNull(take(new int[]{64},0)); assertNull(put(new int[]{0},0,64)); }
    @Test void conservationForTenThousandInventories() {
        Random rng=new Random(8153);
        for(int n=0;n<10000;n++) {
            int[] slots=new int[27]; for(int i=0;i<27;i++) slots[i]=rng.nextInt(65);
            int amount=1+rng.nextInt(1728), sum=Arrays.stream(slots).sum();
            int[] out=take(slots,amount); if(out!=null) { assertEquals(sum-amount,Arrays.stream(out).sum()); assertTrue(Arrays.stream(out).allMatch(v->v>=0 && v<=64)); } else assertTrue(sum<amount);
            int[] in=put(slots,amount,64); if(in!=null) { assertEquals(sum+amount,Arrays.stream(in).sum()); assertTrue(Arrays.stream(in).allMatch(v->v>=0 && v<=64)); } else assertTrue(sum+amount>27*64);
        }
    }
    private Job disabled() { Job j=new Job(); j.phase=Phase.DISABLED; j.hp=0; return j; }
    @Test void enemyCanCaptureOnlyAfterDisable() { assertTrue(disabled().canCapture(ENEMY,OWNER,true,6)); }
    @Test void neutralCannotCapture() { assertFalse(disabled().canCapture(ENEMY,OWNER,false,1)); }
    @Test void statelessCannotCapture() { assertFalse(disabled().canCapture(null,OWNER,true,1)); }
    @Test void ownerCannotRaidOwnCargo() { assertFalse(disabled().canCapture(OWNER,OWNER,true,1)); }
    @Test void distantOrCrossWorldCannotCapture() { assertFalse(disabled().canCapture(ENEMY,OWNER,true,6.01)); assertFalse(disabled().canCapture(ENEMY,OWNER,true,Double.POSITIVE_INFINITY)); }
    @Test void movingCannotBeCaptured() { Job j=disabled(); j.phase=Phase.MOVING; assertFalse(j.canCapture(ENEMY,OWNER,true,1)); }
    @Test void secondCaptureBlockedByIntent() { Job j=disabled(); assertTrue(j.canCapture(ENEMY,OWNER,true,1)); j.phase=Phase.UNLOADING; assertFalse(j.canCapture(ENEMY,OWNER,true,1)); }
    @Test void damageCappedAndRateLimited() { Job j=new Job(); j.phase=Phase.MOVING; j.hit(ENEMY,10000,1000); assertEquals(80,j.hp); j.hit(ENEMY,10,1100); assertEquals(80,j.hp); j.hit(ENEMY,10,1800); assertEquals(70,j.hp); }
    @Test void fiveValidHitsDisableWithoutDestroyingCargo() { Job j=new Job(); j.phase=Phase.MOVING; for(int i=1;i<=5;i++) j.hit(ENEMY,20,i*1000); assertEquals(0,j.hp); assertEquals(Phase.DISABLED,j.phase); assertTrue(j.active()); }
    @Test void invalidDamageIgnored() { Job j=new Job(); j.phase=Phase.MOVING; j.hit(ENEMY,Double.NaN,1000); j.hit(ENEMY,-1,2000); j.hit(null,20,3000); assertEquals(100,j.hp); }
    @ParameterizedTest @EnumSource(value=Phase.class,names={"LOADING","UNLOADING"}) void ambiguousOperationsNeverReplay(Phase phase) { State s=new State(); Job j=new Job(); j.phase=phase; s.jobs.put(j.id,j); s.recover(); assertEquals(Phase.QUARANTINED,j.phase); assertTrue(j.active()); }
    @ParameterizedTest @EnumSource(value=Phase.class,names={"MOVING","DISABLED","FUNDING","PAYING","DONE","QUARANTINED"}) void safePhasesPreserved(Phase phase) { State s=new State(); Job j=new Job(); j.phase=phase; s.jobs.put(j.id,j); s.recover(); assertEquals(phase,j.phase); }
    @Test void dedicatedEscrowsUnique() { assertNotEquals(new Job().escrow(),new Job().escrow()); }
    @Test void settlementRetryCannotGenerateMoney() { assertEquals(Settlement.ALREADY_PAID,settlement(0,100)); assertEquals(Settlement.TRANSFER,settlement(100,100)); assertEquals(Settlement.QUARANTINE,settlement(1,100)); assertEquals(Settlement.QUARANTINE,settlement(200,100)); }
    @Test void freeTransportDoesNotCallZeroTransfer() { assertEquals(Settlement.ALREADY_PAID,settlement(0,0)); }
    @Test void crossCountryTradeAllowedOnApprovedRoute() { Route r=route(); r.destinationCountry=ENEMY; assertDoesNotThrow(r::validate); }
    @Test void storeRoundTripAndBackup(@TempDir Path dir) throws Exception {
        try(StateStore store=new StateStore(dir)) {
            State s=new State(); Route r=route(); s.routes.put(r.id,r); Job j=new Job(); j.route=r.id; j.position=r.points.getFirst(); j.phase=Phase.LOADING; s.jobs.put(j.id,j);
            store.save(s); s.routes.get(r.id).fee=99; store.save(s);
            State restored=store.load(); assertEquals(99,restored.routes.get(r.id).fee); assertEquals(Phase.QUARANTINED,restored.jobs.get(j.id).phase);
            assertTrue(Files.exists(dir.resolve("state.json.bak"))); assertFalse(Files.exists(dir.resolve("state.json.tmp")));
        }
    }
    @Test void corruptSnapshotFailsClosedWithoutBackupRollback(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("state.json"),"{invalid"); Files.writeString(dir.resolve("state.json.bak"),"{}");
        try(StateStore store=new StateStore(dir)) { assertThrows(java.io.IOException.class,store::load); }
    }
    @Test void missingFieldsDoNotSilentlyCreateEmptyState(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("state.json"),"{}"); try(StateStore store=new StateStore(dir)) { assertThrows(java.io.IOException.class,store::load); }
    }
    @Test void unsupportedSchemaFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("state.json"),"{\"schema\":99}"); try(StateStore store=new StateStore(dir)) { assertThrows(java.io.IOException.class,store::load); }
    }
}
