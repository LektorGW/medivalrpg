// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.*;
import pl.medivalrpg.ludnosc.model.*;

class SupplyLedgerTest {
    final Payload payload=new Payload(new UUID(0,1),new UUID(0,2),"contract:one","COBBLESTONE",7);
    static final class Journal implements SupplyLedger.Journal {
        final List<Entry> writes=new ArrayList<>(); final List<CompletableFuture<Void>> barriers=new ArrayList<>();
        Entry durable;
        public CompletableFuture<Void> write(Entry e) { writes.add(e); var f=new CompletableFuture<Void>(); barriers.add(f); return f; }
        void finish() { int last=barriers.size()-1; durable=writes.get(last); barriers.get(last).complete(null); }
        void fail() { barriers.getLast().completeExceptionally(new IllegalStateException("disk")); }
    }
    static final class Port implements SupplyLedger.Port {
        int source=20,target=0,savedSource=20,savedTarget=0,applies,locks,unlocks,prepares,crash=-1;
        String sourceMarker="",targetMarker="",savedSourceMarker="",savedTargetMarker="";
        boolean ready=true,available=true,changed;
        boolean failPlayerSave; Proof disk=Proof.AFTER;
        public Attempt prepare(Payload p) {
            prepares++;
            if(!ready) return new Attempt(null,"Magazyn pełny, brakujący lub niezaładowany.");
            var plan=new Plan(new UUID(0,3),new ProductionBinding(new UUID(0,4),List.of(new BlockPos("world",1,2,3))),
                List.of(""+source),List.of(""+(source-p.amount())),List.of(""+target),List.of(""+(target+p.amount())),sourceMarker,List.of(targetMarker));
            return new Attempt(plan,"");
        }
        public void lock(Entry e) { locks++; }
        public void unlock(Entry e) { unlocks++; }
        public boolean before(Entry e) { return !changed && available && e.plan().sourceBefore().equals(List.of(""+source)) && e.plan().targetBefore().equals(List.of(""+target)); }
        void at(int stage) { if(crash==stage) throw new IllegalStateException("crash "+stage); }
        public void apply(Entry e) {
            applies++; at(0); source-=e.payload().amount(); at(1); sourceMarker=e.receipt(); at(2);
            if(!failPlayerSave) { savedSource=source; savedSourceMarker=sourceMarker; } at(3);
            target+=e.payload().amount(); at(4); targetMarker=e.receipt(); at(5);
            savedTarget=target; savedTargetMarker=targetMarker; at(6);
        }
        public Proof proof(Entry e) {
            if(!available) return Proof.UNAVAILABLE;
            if(e.plan().sourceAfter().equals(List.of(""+source)) && e.plan().targetAfter().equals(List.of(""+target))
                && sourceMarker.equals(e.receipt()) && targetMarker.equals(e.receipt())) return Proof.AFTER;
            if(e.plan().sourceBefore().equals(List.of(""+source)) && e.plan().targetBefore().equals(List.of(""+target))
                && sourceMarker.equals(e.plan().playerMarker()) && e.plan().chestMarkers().equals(List.of(targetMarker))) return Proof.BEFORE;
            return Proof.MISMATCH;
        }
        public Proof sourceProof(Entry e) {
            if(!available) return Proof.UNAVAILABLE;
            return sourceMarker.equals(e.receipt()) && savedSourceMarker.equals(e.receipt()) ? Proof.AFTER : Proof.MISMATCH;
        }
        public Proof targetProof(Entry e) {
            if(!available) return Proof.UNAVAILABLE;
            return targetMarker.equals(e.receipt()) && savedTargetMarker.equals(e.receipt()) ? Proof.AFTER : Proof.MISMATCH;
        }
        public Proof durableProof(Entry e) {
            if(disk!=Proof.AFTER) return disk;
            return savedSourceMarker.equals(e.receipt()) && savedTargetMarker.equals(e.receipt()) ? Proof.AFTER : Proof.MISMATCH;
        }
        void restart() { source=savedSource; target=savedTarget; sourceMarker=savedSourceMarker; targetMarker=savedTargetMarker; }
    }
    final Journal journal=new Journal(); final Port port=new Port();
    final SupplyLedger ledger=new SupplyLedger(journal,port,List.of());
    String status() { return (String)ledger.receive(payload).get("status"); }
    void intent() { ledger.receive(payload); journal.finish(); ledger.tick(true); }
    void transfer() { intent(); journal.finish(); ledger.tick(false); }

    @Test void bothInventoriesWaitForDurableIntentAndPaymentWaitsForDurableReceipt() {
        assertEquals("PENDING",status()); ledger.tick(true); assertEquals(0,port.locks);
        journal.finish(); ledger.tick(true); assertEquals(1,port.locks); assertEquals(0,port.applies);
        ledger.tick(false); assertEquals(20,port.source); assertEquals(0,port.target);
        journal.finish(); ledger.tick(false);
        assertEquals(13,port.source); assertEquals(7,port.target); assertEquals("PENDING",status()); assertEquals(0,port.unlocks);
        journal.finish(); ledger.tick(false); assertEquals("DELIVERED",status()); assertEquals(1,port.unlocks);
    }
    @Test void sameIdIsReadOnlyAndRetainsOneReceiptAcrossRepeatedPolls() {
        transfer(); journal.finish(); ledger.tick(false); Object receipt=ledger.receive(payload).get("receipt");
        for(int n=0;n<1000;n++) { assertEquals("DELIVERED",status()); assertEquals(receipt,ledger.receive(payload).get("receipt")); }
        assertEquals(3,journal.writes.size()); assertEquals(1,port.applies); assertEquals(20,port.source+port.target);
    }
    @Test void payloadBindingIncludesPlayerCountryMaterialAndExactAmount() {
        intent();
        for(var p:List.of(new Payload(new UUID(0,9),payload.country(),payload.contractId(),payload.material(),7),
            new Payload(payload.player(),new UUID(0,9),payload.contractId(),payload.material(),7),
            new Payload(payload.player(),payload.country(),payload.contractId(),"RAW_IRON",7),
            new Payload(payload.player(),payload.country(),payload.contractId(),payload.material(),8)))
            assertEquals("BLOCKED",ledger.receive(p).get("status"));
        assertEquals(2,journal.writes.size()); assertEquals(0,port.applies);
    }
    @Test void fullMissingAndUnloadedStorageTakeNothingAndSameRequestCanLaterProceed() {
        port.ready=false; ledger.receive(payload); journal.finish(); ledger.tick(true);
        assertEquals("UNAVAILABLE",status()); assertEquals(0,port.locks); assertEquals(20,port.source); assertEquals(0,port.target);
        int writes=journal.writes.size(); status(); assertEquals(writes,journal.writes.size());
        port.ready=true; ledger.tick(true); journal.finish(); ledger.tick(false); journal.finish(); ledger.tick(false);
        assertEquals("DELIVERED",status()); assertEquals(13,port.source); assertEquals(7,port.target);
    }
    @Test void changedInventoryOrOwnershipAfterIntentBlocksWithoutBlindRetry() {
        intent(); port.changed=true; journal.finish(); ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(0,port.applies); assertEquals(0,port.unlocks);
        for(int n=0;n<100;n++) ledger.tick(true);
        assertEquals(0,port.applies); assertEquals(20,port.source);
    }
    @Test void failedRegistrationAndIntentWritesNeverMutateItems() {
        status(); journal.fail(); ledger.tick(true); assertTrue(ledger.faulted()); assertEquals(0,port.applies);
        var j=new Journal(); var p=new Port(); var l=new SupplyLedger(j,p,List.of());
        l.receive(payload); j.finish(); l.tick(true); j.fail(); l.tick(false);
        assertTrue(l.faulted()); assertEquals(0,p.applies); assertEquals(1,p.locks); assertEquals(0,p.unlocks);
    }
    @Test void finalWriteFailureRetainsIntentLocksAndNeverReturnsDelivered() {
        transfer(); journal.fail(); ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(13,port.source); assertEquals(7,port.target); assertEquals(0,port.unlocks);
        assertEquals(Phase.INTENT,journal.durable.phase());
    }
    @Test void crashAfterBothSavedCanReconcileReceiptWithoutApplyingAgain() {
        transfer(); Entry durable=journal.durable; port.restart();
        var restartedJournal=new Journal(); var restarted=new SupplyLedger(restartedJournal,port,List.of(durable));
        restarted.tick(true); assertEquals(1,port.applies); assertEquals("PENDING",restarted.receive(payload).get("status"));
        restartedJournal.finish(); restarted.tick(false);
        assertEquals("DELIVERED",restarted.receive(payload).get("status")); assertEquals(1,port.applies);
    }
    @Test void everyPartialCrashIsFailClosedAndNeverReplaysTheTransfer() {
        for(int stage=0;stage<=6;stage++) {
            var j=new Journal(); var p=new Port(); var l=new SupplyLedger(j,p,List.of());
            l.receive(payload); j.finish(); l.tick(true); j.finish(); Entry intent=j.durable;
            p.crash=stage; l.tick(false); p.restart();
            int source=p.source, target=p.target;
            var retryJournal=new Journal(); var retry=new SupplyLedger(retryJournal,p,List.of(intent));
            retry.tick(true);
            if(stage<6) assertEquals("BLOCKED",retry.receive(payload).get("status"),"stage "+stage);
            else { retryJournal.finish(); retry.tick(false); assertEquals("DELIVERED",retry.receive(payload).get("status")); }
            assertEquals(1,p.applies); assertEquals(source,p.source); assertEquals(target,p.target);
        }
    }
    @Test void evenExactBeforeAfterRestartRequiresAuditInsteadOfReplaying() {
        intent(); journal.finish(); var j=new Journal(); var restarted=new SupplyLedger(j,port,List.of(journal.durable));
        restarted.tick(true); assertEquals("BLOCKED",restarted.receive(payload).get("status")); assertEquals(0,port.applies);
    }
    @Test void missingChestOrOfflinePlayerDuringRecoveryKeepsLocksUntilProofIsReadable() {
        transfer(); var j=new Journal(); port.available=false;
        var restarted=new SupplyLedger(j,port,List.of(journal.durable)); restarted.tick(true);
        assertEquals("PENDING",restarted.receive(payload).get("status")); assertEquals(0,port.unlocks); assertTrue(j.writes.isEmpty());
        port.available=true; restarted.tick(true); j.finish(); restarted.tick(false);
        assertEquals("DELIVERED",restarted.receive(payload).get("status")); assertEquals(1,port.applies);
    }
    @Test void ordinaryItemMovementAndNpcConsumptionDoNotInvalidatePersistedReceiptLineage() {
        transfer(); journal.finish(); ledger.tick(false); port.source=0; port.target=0;
        var restarted=new SupplyLedger(new Journal(),port,List.of(journal.durable));
        assertEquals("PENDING",restarted.receive(payload).get("status")); restarted.tick(true);
        assertEquals("DELIVERED",restarted.receive(payload).get("status"));
        assertEquals(1,port.applies); assertEquals(0,port.source); assertEquals(0,port.target);
    }
    @Test void oneTransferAtATimeAndQueueHasHardLimit() {
        for(int n=0;n<16;n++) assertEquals("PENDING",ledger.receive(new Payload(payload.player(),payload.country(),"c"+n,"STONE",1)).get("status"));
        assertEquals("UNAVAILABLE",ledger.receive(payload).get("status"));
        for(var f:journal.barriers) f.complete(null);
        ledger.tick(true); ledger.tick(true); assertEquals(1,port.locks); assertEquals(0,port.applies);
    }
    @Test void invalidPayloadsAndOversizedSnapshotsAreRejected() {
        for(int n:new int[]{0,-1,4097}) assertThrows(IllegalArgumentException.class,() -> new Payload(payload.player(),payload.country(),"c","STONE",n));
        assertThrows(IllegalArgumentException.class,() -> new Payload(payload.player(),payload.country(),"../bad id","STONE",1));
        assertThrows(IllegalArgumentException.class,() -> new Plan(new UUID(0,3),new ProductionBinding(new UUID(0,4),List.of(new BlockPos("world",1,2,3))),
            List.of("a".repeat(131073)),List.of("a"),List.of("b"),List.of("c"),"",List.of("")));
    }
    @Test void retainedReceiptLimitNeverEvictsIdsAndAllowsNoExtraDebit() {
        var entries=new ArrayList<Entry>();
        for(int i=0;i<SupplyLedger.MAX_RECORDS;i++) {
            Payload p=new Payload(new UUID(1,i),payload.country(),"done"+i,"STONE",1);
            Plan plan=new Plan(new UUID(0,3),new ProductionBinding(new UUID(0,i),List.of(new BlockPos("world",i,2,3))),List.of("20"),List.of("19"),List.of("0"),List.of("1"),"",List.of(""));
            entries.add(new Entry(1,p,UUID.randomUUID().toString(),Phase.DELIVERED,plan));
        }
        var full=new SupplyLedger(journal,port,entries);
        assertEquals("UNAVAILABLE",full.receive(payload).get("status")); assertTrue(journal.writes.isEmpty());
        assertEquals("PENDING",full.receive(entries.getFirst().payload()).get("status"));
    }
    @Test void directBankOrTradeMutationDuringAsyncIntentIsNotOverwritten() {
        intent(); port.source+=11; journal.finish(); ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(31,port.source); assertEquals(0,port.target);
        assertEquals(0,port.applies); assertEquals(0,port.unlocks);
    }
    @Test void directNpcDestinationMutationDuringAsyncIntentIsNotOverwritten() {
        intent(); port.target+=5; journal.finish(); ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(20,port.source); assertEquals(5,port.target);
        assertEquals(0,port.applies);
    }
    @Test void changesDuringFinalJournalWriteBlockInsteadOfRestoringOldSnapshot() {
        transfer(); port.target+=9; journal.finish(); ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(13,port.source); assertEquals(16,port.target);
        assertEquals(1,port.applies); assertEquals(0,port.unlocks);
    }
    @Test void finalProofWaitsForOfflinePlayerWithoutReplaying() {
        transfer(); journal.finish(); port.available=false; ledger.tick(false);
        assertEquals("PENDING",status()); assertEquals(0,port.unlocks);
        port.available=true; ledger.tick(false); assertEquals("DELIVERED",status()); assertEquals(1,port.applies);
    }
    @Test void deliveredJournalWithRolledBackDestinationIsBlockedAndNeverRecreatesCargo() {
        transfer(); journal.finish(); ledger.tick(false);
        port.target=0; port.targetMarker="";
        var restarted=new SupplyLedger(new Journal(),port,List.of(journal.durable));
        assertEquals("PENDING",restarted.receive(payload).get("status")); restarted.tick(true);
        assertEquals("BLOCKED",restarted.receive(payload).get("status"));
        assertEquals(13,port.source); assertEquals(0,port.target); assertEquals(1,port.applies);
    }
    @Test void deliveredJournalWithRolledBackPlayerIsBlockedWithoutTakingGoodsAgain() {
        transfer(); journal.finish(); ledger.tick(false); port.source=20; port.sourceMarker="";
        var restarted=new SupplyLedger(new Journal(),port,List.of(journal.durable)); restarted.tick(true);
        assertEquals("BLOCKED",restarted.receive(payload).get("status")); assertEquals(20,port.source); assertEquals(7,port.target);
    }
    @Test void completedReceiptsArePendingWhileLatestInventoriesAreUnavailable() {
        transfer(); journal.finish(); ledger.tick(false); port.available=false;
        var restarted=new SupplyLedger(new Journal(),port,List.of(journal.durable)); restarted.tick(true);
        assertEquals("PENDING",restarted.receive(payload).get("status"));
        port.available=true; restarted.tick(true); assertEquals("DELIVERED",restarted.receive(payload).get("status")); assertEquals(1,port.applies);
    }
    @Test void latestReceiptChainAcceptsOlderIdsWithoutComparingTheirObsoleteSnapshots() {
        transfer(); journal.finish(); ledger.tick(false); Entry first=journal.durable;
        Payload second=new Payload(payload.player(),payload.country(),"contract:two",payload.material(),4);
        ledger.receive(second); journal.finish(); ledger.tick(true); journal.finish(); ledger.tick(false); journal.finish(); ledger.tick(false);
        Entry last=journal.durable;
        assertEquals(first.receipt(),last.plan().playerMarker()); assertEquals(List.of(first.receipt()),last.plan().chestMarkers());
        var restarted=new SupplyLedger(new Journal(),port,List.of(last,first)); restarted.tick(true);
        assertEquals("DELIVERED",restarted.receive(payload).get("status")); assertEquals("DELIVERED",restarted.receive(second).get("status"));
        assertEquals(9,port.source); assertEquals(11,port.target); assertEquals(2,port.applies);
    }
    @Test void forksOrMissingPredecessorsFailClosedInsteadOfChoosingArbitraryLatestReceipt() {
        transfer(); journal.finish(); ledger.tick(false); Entry first=journal.durable;
        Entry fork=new Entry(1,new Payload(payload.player(),payload.country(),"fork",payload.material(),7),UUID.randomUUID().toString(),Phase.DELIVERED,first.plan());
        assertThrows(IllegalArgumentException.class,() -> new SupplyLedger(new Journal(),port,List.of(first,fork)));
        Plan missing=new Plan(first.plan().warehouse(),first.plan().binding(),first.plan().sourceBefore(),first.plan().sourceAfter(),
            first.plan().targetBefore(),first.plan().targetAfter(),UUID.randomUUID().toString(),first.plan().chestMarkers());
        Entry bad=new Entry(1,payload,first.receipt(),Phase.DELIVERED,missing);
        assertThrows(IllegalArgumentException.class,() -> new SupplyLedger(new Journal(),port,List.of(bad)));
    }
    @Test void liveSnapshotsNeverSubstituteForFailedPlayerFileWrites() {
        port.failPlayerSave=true; transfer();
        assertEquals("BLOCKED",status()); assertEquals(20,port.savedSource); assertEquals(13,port.source);
        assertEquals(7,port.target); assertEquals(Phase.INTENT,journal.durable.phase()); assertEquals(0,port.unlocks);
    }
    @Test void delayedRegionIoKeepsBothLocksAndDoesNotFinalizeOrReplay() {
        port.disk=Proof.UNAVAILABLE; transfer();
        for(int i=0;i<100;i++) ledger.tick(false);
        assertEquals("PENDING",status()); assertEquals(Phase.INTENT,journal.durable.phase());
        assertEquals(2,journal.writes.size()); assertEquals(1,port.applies); assertEquals(0,port.unlocks);
        port.disk=Proof.AFTER; ledger.tick(false); assertEquals(Phase.DELIVERED,journal.writes.getLast().phase());
        journal.finish(); ledger.tick(false); assertEquals("DELIVERED",status()); assertEquals(1,port.applies);
    }
    @Test void persistenceProofTimeoutBlocksWithoutReturningGoodsOrCreatingThemAgain() {
        port.disk=Proof.UNAVAILABLE; transfer(); port.disk=Proof.MISMATCH; ledger.tick(false);
        assertEquals("BLOCKED",status()); assertEquals(13,port.source); assertEquals(7,port.target); assertEquals(0,port.unlocks);
    }
    @Test void deliveredFileWithOnlyMemoryMarkersButRolledBackDiskIsNotTrustedOnStartup() {
        transfer(); journal.finish(); ledger.tick(false); port.savedTargetMarker="";
        var restarted=new SupplyLedger(new Journal(),port,List.of(journal.durable)); restarted.tick(true);
        assertEquals("BLOCKED",restarted.receive(payload).get("status")); assertEquals(1,port.applies);
    }
    @Test void unavailableNewRequestHasEmptyReceiptAndNoRegistrationLocksOrMutations() {
        port.ready=false;
        var result=ledger.receivePrepared(payload);
        assertEquals("BLOCKED",result.get("status")); assertEquals("",result.get("receipt"));
        assertFalse(ledger.contains(payload.contractId())); assertTrue(journal.writes.isEmpty());
        assertEquals(0,port.locks); assertEquals(0,port.applies); assertEquals(20,port.source); assertEquals(0,port.target);
        port.ready=true; assertEquals("PENDING",ledger.receivePrepared(payload).get("status"));
        assertTrue(ledger.contains(payload.contractId())); assertEquals(1,journal.writes.size());
    }
    @Test void readyNewPreflightIsReadOnlyAndTheLaterIntentRecomputesItsPlan() {
        assertEquals("PENDING",ledger.receivePrepared(payload).get("status"));
        assertEquals(1,port.prepares); assertEquals(0,port.locks); assertEquals(0,port.applies);
        port.source=25; journal.finish(); ledger.tick(true);
        assertEquals(2,port.prepares); assertEquals(List.of("25"),journal.writes.getLast().plan().sourceBefore());
        assertEquals(25,port.source); assertEquals(0,port.target);
    }
    @Test void registeredPollSkipsPreflightWhenPlayerOrWarehouseBecomesUnavailable() {
        var result=ledger.receivePrepared(payload); Object receipt=result.get("receipt");
        port.ready=false;
        for(int i=0;i<10;i++) assertEquals(receipt,ledger.receivePrepared(payload).get("receipt"));
        assertEquals(1,port.prepares); assertEquals(1,journal.writes.size());
    }
    @Test void validationErrorsOnKnownIdsRetainTheOriginalReceipt() {
        Object receipt=ledger.receivePrepared(payload).get("receipt");
        var result=ledger.reject(payload.contractId(),"Nieprawidłowy materiał");
        assertEquals("BLOCKED",result.get("status")); assertEquals(receipt,result.get("receipt"));
        assertTrue(ledger.contains(payload.contractId())); assertEquals(1,journal.writes.size());
    }
    @Test void synchronousJournalExceptionAfterRegistrationCannotBecomeAnEmptyReceiptRejection() {
        var broken=new SupplyLedger(e -> { throw new IllegalArgumentException("write failed after registration"); },port,List.of());
        assertThrows(IllegalArgumentException.class,() -> broken.receivePrepared(payload));
        assertTrue(broken.contains(payload.contractId()));
        var result=broken.quarantine(payload.contractId(),"Nieudany zapis");
        assertEquals("BLOCKED",result.get("status")); assertFalse(((String)result.get("receipt")).isBlank());
        assertTrue(broken.faulted()); assertEquals(0,port.applies);
        assertEquals(result.get("receipt"),broken.receivePrepared(payload).get("receipt"));
    }
    @Test void newPreflightFailureCannotClaimAnExistingUnrelatedReceiptsOwnership() {
        Object receipt=ledger.receivePrepared(payload).get("receipt");
        var result=ledger.quarantine("wladcy:new-id","Nie można odczytać inventory");
        assertEquals("",result.get("receipt")); assertFalse(ledger.contains("wladcy:new-id"));
        assertEquals(receipt,ledger.receivePrepared(payload).get("receipt")); assertFalse(ledger.faulted());
    }
}
