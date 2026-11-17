// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;
import static pl.medivalrpg.kolej.infrastructure.InfrastructureState.*;
import static pl.medivalrpg.kolej.infrastructure.SupplyCoordinator.*;
import static org.junit.jupiter.api.Assertions.*;

class SupplyCoordinatorTest {
    static Fixture fixture(SupplyKind kind){return new Fixture("supply","station",UUID.randomUUID().toString(),10,64,10,0,kind,"CHEST",UUID.randomUUID().toString());}
    static final class Fake implements Port {
        final Fixture f;
        TrainView train=new TrainView("train","station",true,0,0);
        Snapshot stock;
        long money=1000,fees;
        int debits,refunds,credits;
        boolean unload,deny,applyFalse,creditFalse,ambiguous;
        CompletableFuture<Void> bankAck=CompletableFuture.completedFuture(null),tenderAck=CompletableFuture.completedFuture(null);
        String tenderReceipt;
        Fake(SupplyKind kind){f=fixture(kind);stock=new Snapshot(f,f.receipt(),kind.coal?List.of(new Slot("COAL",20,true,64)):List.of(new Slot("WATER_BUCKET",1,true,1),new Slot("WATER_BUCKET",1,true,1)),new Object());}
        public TrainView train(String id,Fixture f){return train;}
        public Snapshot inventory(Fixture f){if(deny)throw new IllegalArgumentException("Revoked consent");return unload?null:stock;}
        public boolean apply(Snapshot before,List<Slot> after,String receipt){
            if(applyFalse || before!=stock)return false;
            stock=new Snapshot(f,receipt,after,new Object());
            if(ambiguous)throw new IllegalStateException("PDC write failed after inventory write");return true;
        }
        public boolean debit(long amount,String tx){debits++;if(money<amount)return false;money-=amount;fees+=amount;return true;}
        public boolean refund(long amount,String tx){refunds++;money+=amount;fees-=amount;return true;}
        public boolean credit(TrainView before,double coal,double water){if(creditFalse || !before.equals(train))return false;credits++;train=new TrainView(train.id(),train.station(),train.eligible(),coal,water);return true;}
        public void audit(Pending p,String result){}
        public CompletableFuture<Void> confirmEconomy(String transaction){return bankAck;}
        public CompletableFuture<Void> persistTender(String train,String transaction){tenderReceipt=transaction;return tenderAck;}
        InfrastructureState state(){var s=new InfrastructureState();s.fixtures.put(f.id(),f);return s;}
        SupplyCoordinator coordinator(){var c=new SupplyCoordinator(this,CompletableFuture.completedFuture(state()),s->CompletableFuture.completedFuture(null));c.tick();return c;}
    }
    @Test void noMoneyItemsOrTenderChangeBeforeDurableIntentAcknowledgement() {
        Fake p=new Fake(SupplyKind.WEGIEL);var disk=new CompletableFuture<Void>();var c=new SupplyCoordinator(p,CompletableFuture.completedFuture(p.state()),s->disk);c.tick();
        c.request(p.f.id(),"train",10,1,1);c.tick();
        assertTrue(c.holds("train"));assertEquals(0,p.debits);assertEquals(20,SupplyRules.available(p.stock.slots(),SupplyKind.WEGIEL));assertEquals(0,p.train.coal());
        disk.complete(null);c.tick();assertEquals(100,p.train.coal());assertEquals(900,p.money);assertEquals(10,SupplyRules.available(p.stock.slots(),SupplyKind.WEGIEL));
    }
    @Test void actualWaterIsConsumedAndOneBucketReturnedPerUnit() {
        Fake p=new Fake(SupplyKind.ZURAW);var c=p.coordinator();c.request(p.f.id(),"train",2,1,1);c.tick();c.tick();
        assertEquals(500,p.train.water());assertEquals(995,p.money);assertEquals(5,p.fees);assertEquals(2,p.stock.slots().stream().filter(s->s.material().equals("BUCKET")).mapToInt(Slot::amount).sum());assertTrue(c.available());
        assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));
        assertEquals(1,p.debits);
    }
    @Test void insufficientFundsPreserveInventoryAndTender() {
        Fake p=new Fake(SupplyKind.WEGIEL);p.money=0;var before=p.stock;var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);c.tick();c.tick();
        assertSame(before,p.stock);assertEquals(0,p.train.coal());assertEquals(0,p.fees);assertTrue(c.available());
    }
    @Test void movingTrainOrWrongStationDeniedBeforeJournaling() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();p.train=new TrainView("train","station",false,0,0);
        assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));
        p.train=new TrainView("train","other",true,0,0);assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));assertEquals(0,p.debits);
    }
    @Test void unloadedContainersNeverGetCreatedOrReadAsUnlimitedSupply() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();p.unload=true;
        assertEquals(-1,c.stock(p.f));assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));assertEquals(0,p.debits);
    }
    @Test void replacementWithIdenticalItemsButDifferentReceiptIsRejected() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();p.stock=new Snapshot(p.f,UUID.randomUUID().toString(),p.stock.slots(),new Object());
        assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));assertEquals(0,p.debits);
    }
    @Test void changedTrainAfterPrepareCancelsBeforeCharging() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);p.train=new TrainView("train","station",false,0,0);c.tick();c.tick();assertEquals(0,p.debits);assertTrue(c.available());
    }
    @Test void unloadedAfterPrepareCancelsWithoutRemovingOrCharging() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);p.unload=true;c.tick();c.tick();assertEquals(0,p.debits);assertEquals(0,p.train.coal());assertTrue(c.available());
    }
    @Test void definiteInventoryCasFailureRefundsExactlyOnce() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);p.applyFalse=true;c.tick();c.tick();c.tick();
        assertEquals(1000,p.money);assertEquals(0,p.fees);assertEquals(1,p.debits);assertEquals(1,p.refunds);assertEquals(0,p.credits);assertTrue(c.available());
    }
    @Test void tenderFailureRollsInventoryAndFeesBackWithoutMinting() {
        Fake p=new Fake(SupplyKind.ZURAW);var c=p.coordinator();var before=p.stock.slots();c.request(p.f.id(),"train",2,1,1);p.creditFalse=true;c.tick();c.tick();
        assertEquals(before,p.stock.slots());assertEquals(p.f.receipt(),p.stock.receipt());assertEquals(1000,p.money);assertEquals(1,p.refunds);assertEquals(0,p.train.water());assertTrue(c.available());
    }
    @Test void ambiguousInventoryFailureStaysPreparedAndNeverAutomaticallyReplaysOrRefunds() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);p.ambiguous=true;c.tick();for(int i=0;i<10;i++)c.tick();
        assertTrue(c.status().startsWith("BLOKADA"));assertNotNull(c.state().pending);assertTrue(c.holds("train"));assertEquals(1,p.debits);assertEquals(0,p.refunds);assertEquals(0,p.credits);
        var restart=new SupplyCoordinator(p,CompletableFuture.completedFuture(c.state()),s->{fail("No replay after restart");return null;});restart.tick();
        assertTrue(restart.holds("train"));assertFalse(restart.available());assertEquals(1,p.debits);
    }
    @Test void persistenceFailureBeforeDebitBlocksWithoutTouchingStocks() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=new SupplyCoordinator(p,CompletableFuture.completedFuture(p.state()),s->CompletableFuture.failedFuture(new java.io.IOException("disk full")));c.tick();c.request(p.f.id(),"train",1,1,1);c.tick();
        assertTrue(c.holds("train"));assertTrue(c.status().startsWith("BLOKADA"));assertEquals(0,p.debits);
    }
    @Test void zeroTariffStillConsumesStockButSkipsZeroValueTransfer() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,0,0);c.tick();c.tick();
        assertEquals(0,p.debits);assertEquals(10,p.train.coal());assertEquals(19,SupplyRules.available(p.stock.slots(),SupplyKind.WEGIEL));
    }
    @Test void consentRevokedAfterIntentFreezesTheTrainBeforeDebit() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);p.deny=true;c.tick();
        assertTrue(c.holds("train"));assertFalse(c.available());assertEquals(0,p.debits);
    }
    @Test void onlyOneTransactionCanBeInFlightAndOverfillIsDenied() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=p.coordinator();
        assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",51,1,1));
        c.request(p.f.id(),"train",1,1,1);assertThrows(IllegalArgumentException.class,()->c.request(p.f.id(),"train",1,1,1));c.tick();c.tick();assertEquals(1,p.debits);
    }
    @Test void accountAckMustCompleteBeforeAnyInventoryMutation() {
        Fake p=new Fake(SupplyKind.WEGIEL);p.bankAck=new CompletableFuture<>();var c=p.coordinator();var stock=p.stock;
        c.request(p.f.id(),"train",1,1,1);c.tick();
        assertEquals(990,p.money);assertSame(stock,p.stock);assertEquals(0,p.train.coal());assertTrue(c.holds("train"));assertNotNull(c.state().pending);
        p.bankAck.complete(null);c.tick();assertEquals(10,p.train.coal());assertTrue(c.available());
    }
    @Test void failedAccountPersistenceKeepsIntentAndBlocksWithoutConsumingStock() {
        Fake p=new Fake(SupplyKind.WEGIEL);p.bankAck=CompletableFuture.failedFuture(new java.io.IOException("bank failure"));var c=p.coordinator();var before=p.stock;
        c.request(p.f.id(),"train",1,1,1);c.tick();
        assertSame(before,p.stock);assertEquals(0,p.train.coal());assertTrue(c.holds("train"));assertNotNull(c.state().pending);assertEquals(InfrastructureState.Phase.PREPARED,c.state().phase);
    }
    @Test void committingAndReceiptAreRetainedUntilTenderDurablyAcknowledged() {
        Fake p=new Fake(SupplyKind.WEGIEL);p.tenderAck=new CompletableFuture<>();var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);String tx=c.state().pending.id();c.tick();
        assertEquals(InfrastructureState.Phase.COMMITTING,c.state().phase);assertNotNull(c.state().pending);assertEquals(tx,p.tenderReceipt);assertTrue(c.holds("train"));assertFalse(c.available());
        assertEquals(10,p.train.coal());assertEquals(990,p.money);
        p.tenderAck.complete(null);c.tick();assertNull(c.state().pending);assertFalse(c.holds("train"));assertTrue(c.available());assertEquals(1,p.debits);assertEquals(1,p.credits);
    }
    @Test void tenderDiskFailureNeverClearsCommittingOrReplaysAfterRestart() {
        Fake p=new Fake(SupplyKind.ZURAW);p.tenderAck=CompletableFuture.failedFuture(new java.io.IOException("tender disk failure"));var c=p.coordinator();c.request(p.f.id(),"train",1,1,1);c.tick();
        assertEquals(InfrastructureState.Phase.COMMITTING,c.state().phase);assertTrue(c.holds("train"));assertNotNull(c.state().pending);assertTrue(c.status().startsWith("BLOKADA"));
        var restored=new SupplyCoordinator(p,CompletableFuture.completedFuture(c.state()),s->{fail("No unverified receipt release");return null;});restored.tick();
        assertTrue(restored.holds("train"));assertEquals(1,p.debits);assertEquals(1,p.credits);
    }
    @Test void committingJournalFailureKeepsTrainHeldAndNeverStartsTenderReceiptSave() {
        Fake p=new Fake(SupplyKind.WEGIEL);var writes=new java.util.concurrent.atomic.AtomicInteger();
        var c=new SupplyCoordinator(p,CompletableFuture.completedFuture(p.state()),s->writes.incrementAndGet()==1?CompletableFuture.completedFuture(null):CompletableFuture.failedFuture(new java.io.IOException("committing journal failure")));
        c.tick();c.request(p.f.id(),"train",1,1,1);c.tick();
        assertEquals(InfrastructureState.Phase.COMMITTING,c.state().phase);assertNotNull(c.state().pending);assertTrue(c.holds("train"));assertNull(p.tenderReceipt);assertFalse(c.available());
    }
    @Test void failedFinalClearStillHoldsTrainEvenAfterDurableTenderReceipt() {
        Fake p=new Fake(SupplyKind.WEGIEL);var c=new SupplyCoordinator(p,CompletableFuture.completedFuture(p.state()),s->s.pending==null?CompletableFuture.failedFuture(new java.io.IOException("clear failed")):CompletableFuture.completedFuture(null));
        c.tick();c.request(p.f.id(),"train",1,1,1);String tx=c.state().pending.id();c.tick();
        assertEquals(tx,p.tenderReceipt);assertTrue(c.holds("train"));assertFalse(c.available());assertTrue(c.status().startsWith("BLOKADA"));assertEquals(1,p.debits);assertEquals(1,p.credits);
    }
}
