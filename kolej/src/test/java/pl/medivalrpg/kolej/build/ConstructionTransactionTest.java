// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionTransactionTest {
    static ConstructionJournal.Intent intent() {
        var op=BlockOp.place(12,70,15,BlockOp.Part.BED);
        var entries=List.of(new RailwayWorks.Entry(op,"minecraft:dirt","minecraft:gravel"));
        var plan=new RailwayWorks.PreparedPlan(UUID.randomUUID(),UUID.randomUUID(),entries,new RailwayWorks.Preview(1,Map.of("GRAVEL",1),List.of(),1),List.of(),3);
        return new ConstructionJournal.Intent("tor1",UUID.randomUUID(),75,plan,new ConstructionJournal.GraphIntent(null,null,null,List.of(),null),"INTENT","");
    }
    static final class Fake implements ConstructionTransaction.Ports {
        final List<String> events=new ArrayList<>();
        final Deque<Runnable> acknowledgements=new ArrayDeque<>();
        final Deque<CompletableFuture<Void>> futures=new ArrayDeque<>();
        ConstructionJournal.Intent durable;
        int debits,executions;boolean reject,throwDebit;
        Future<?> pending(String name,Runnable acknowledged) {
            events.add(name);var f=new CompletableFuture<Void>();futures.add(f);acknowledgements.add(acknowledged);return f;
        }
        void acknowledge(){var f=futures.remove();acknowledgements.remove().run();f.complete(null);}
        void fail(){var f=futures.remove();acknowledgements.remove();f.completeExceptionally(new java.io.IOException("injected disk failure"));}
        public Future<?> checkpoint(ConstructionJournal.Intent i){return pending("journal:"+i.phase(),()->durable=i);}
        public Future<?> stage(ConstructionJournal.Intent i){return pending("jobs:HELD",()->{});}
        public TxResult debit(ConstructionJournal.Intent i){events.add("debit");debits++;if(throwDebit)throw new IllegalStateException("unknown bank result");return reject?TxResult.INSUFFICIENT_FUNDS:TxResult.OK;}
        public Future<?> paymentDurable(ConstructionJournal.Intent i){return pending("bank:durable",()->{});}
        public Future<?> graph(ConstructionJournal.Intent i){return pending("graph:durable",()->{});}
        public Future<?> activate(ConstructionJournal.Intent i){return pending("jobs:QUEUED",()->{});}
        public void execute(ConstructionJournal.Intent i){events.add("execute");executions++;}
        public void stopped(ConstructionJournal.Intent i){events.add("stopped:"+i.phase());}
    }
    @Test void everyIrreversibleStepWaitsForItsOwnDurabilityAck() {
        Fake p=new Fake();var tx=new ConstructionTransaction(intent(),p);
        for(int i=0;i<8;i++) {
            var before=List.copyOf(p.events);for(int n=0;n<10;n++)tx.tick();assertEquals(before,p.events,"must not advance before ack");
            assertEquals(0,p.executions);p.acknowledge();tx.tick();
        }
        assertEquals(List.of("journal:INTENT","jobs:HELD","journal:DEBITTING","debit","bank:durable","journal:PAID","graph:durable","jobs:QUEUED","journal:ACTIVE","execute"),p.events);
        assertEquals(1,p.debits);assertEquals(1,p.executions);assertTrue(tx.finished());
        tx.tick();assertEquals(1,p.debits);assertEquals(1,p.executions);
    }
    @Test void crashAtEachWriteBarrierNeverExecutesOrRepeatsTheDebit() {
        for(int barrier=0;barrier<8;barrier++) {
            Fake p=new Fake();var tx=new ConstructionTransaction(intent(),p);
            for(int step=0;step<barrier;step++){p.acknowledge();tx.tick();}
            p.fail();tx.tick();assertTrue(tx.finished());assertEquals(0,p.executions,"barrier "+barrier);
            int charged=p.debits;for(int n=0;n<20;n++)tx.tick();assertEquals(charged,p.debits);
            if(p.durable!=null) {
                var recovered=ConstructionTransaction.recover(p.durable);
                if(!ConstructionTransaction.safelyPaid(p.durable) && !p.durable.phase().equals("INTENT"))assertEquals("RECONCILE",recovered.phase());
            }
        }
    }
    @Test void acknowledgedBankWriteWithoutPaidJournalIsAmbiguousAndFailsClosed() {
        Fake p=new Fake();var tx=new ConstructionTransaction(intent(),p);
        for(int i=0;i<4;i++){p.acknowledge();tx.tick();}
        assertEquals(1,p.debits);assertEquals("DEBITTING",p.durable.phase());
        var recovered=ConstructionTransaction.recover(p.durable);
        assertEquals("RECONCILE",recovered.phase());
        assertThrows(IllegalArgumentException.class,()->ConstructionTransaction.resumePaid(recovered,new Fake()));
    }
    @Test void provenPaidRecoveryRechecksGraphAndJobPersistenceWithoutDebitingAgain() {
        for(String phase:List.of("PAID","ACTIVE")) {
            Fake p=new Fake();var tx=ConstructionTransaction.resumePaid(intent().phase(phase,""),p);
            tx.tick();assertEquals(List.of("graph:durable"),p.events);
            for(int n=0;n<3;n++){p.acknowledge();tx.tick();}
            assertEquals(0,p.debits);assertEquals(1,p.executions);assertTrue(tx.finished());
        }
    }
    @Test void unknownDebitResultAndKnownRefusalNeverBuildOrRefundAutomatically() {
        for(boolean unknown:List.of(false,true)) {
            Fake p=new Fake();p.throwDebit=unknown;p.reject=!unknown;var tx=new ConstructionTransaction(intent(),p);
            for(int i=0;i<3;i++){p.acknowledge();tx.tick();}
            assertTrue(tx.finished());assertEquals(1,p.debits);assertEquals(0,p.executions);
            assertEquals(unknown?"RECONCILE":"DECLINED",tx.intent().phase());
            assertFalse(p.events.contains("bank:durable"));
        }
    }
    @Test void pauseAtEveryBarrierNeverDebitsOrExecutesAndResumeNeverPaysTwice() {
        for(int barrier=0;barrier<8;barrier++) {
            Fake p=new Fake();var tx=new ConstructionTransaction(intent(),p);
            for(int i=0;i<barrier;i++){p.acknowledge();tx.tick();}
            int charged=p.debits;
            for(int i=0;i<12;i++) {
                if(!p.futures.isEmpty())p.acknowledge();
                tx.tick(true);
                assertEquals(charged,p.debits,"paused at "+barrier);
                assertEquals(0,p.executions);
            }
            assertTrue(java.util.Set.of("INTENT","PAID","ACTIVE").contains(p.durable.phase()));
            for(int i=0;i<16 && !tx.finished();i++){if(!p.futures.isEmpty())p.acknowledge();tx.tick(false);}
            assertTrue(tx.finished());assertEquals(1,p.debits);assertEquals(1,p.executions);
        }
    }
    @Test void restartDuringUnpaidPauseResumesStagedWorkWithoutSecondStageOrPayment() {
        for(int barrier=1;barrier<=3;barrier++) {
            Fake p=new Fake();var tx=new ConstructionTransaction(intent(),p);
            for(int i=0;i<barrier;i++){p.acknowledge();tx.tick();}
            // barrier 3 has already debited, and must persist PAID, not an unpaid marker.
            for(int i=0;i<5;i++){if(!p.futures.isEmpty())p.acknowledge();tx.tick(true);}
            Fake recovered=new Fake();
            var next=ConstructionTransaction.safelyPaid(p.durable)?ConstructionTransaction.resumePaid(p.durable,recovered):ConstructionTransaction.resumeUnpaid(p.durable,recovered,true);
            for(int i=0;i<5;i++){if(!recovered.futures.isEmpty())recovered.acknowledge();next.tick(true);}
            assertEquals(0,recovered.debits);assertEquals(0,recovered.executions);
            for(int i=0;i<16 && !next.finished();i++){if(!recovered.futures.isEmpty())recovered.acknowledge();next.tick();}
            assertTrue(next.finished());assertEquals(1,p.debits+recovered.debits);assertEquals(1,recovered.executions);
            assertFalse(recovered.events.contains("jobs:HELD"));
        }
    }
}
