// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionJournalTest {
    @TempDir Path dir;
    @Test void onlyExplicitUnpaidPauseMayRetireDebitIntentAndPaidEvidenceCannotBeRewound() throws Exception {
        var intent=ConstructionTransactionTest.intent();Path file=dir.resolve("transactions.json");
        try(var journal=new ConstructionJournal(file)) {
            journal.checkpoint(intent).get(5,TimeUnit.SECONDS);
            journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,()->journal.checkpoint(intent));
            journal.checkpointUnpaidPause(intent).get(5,TimeUnit.SECONDS);
        }
        try(var journal=new ConstructionJournal(file)) {
            var saved=journal.intents().iterator().next();assertEquals("INTENT",saved.phase());
            assertTrue(ConstructionTransaction.canRecoverUnpaid(ConstructionTransaction.recover(saved),Optional.empty()));
            journal.checkpoint(intent.phase("PAID","")).get(5,TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,()->journal.checkpointUnpaidPause(intent));
        }
    }
    @Test void crashEvidenceKeepsExactJobPriceGraphAndPreparedBlocks() throws Exception {
        Path file=dir.resolve("transactions.json");var intent=ConstructionTransactionTest.intent();
        try(var journal=new ConstructionJournal(file)) {
            journal.checkpoint(intent).get(5,TimeUnit.SECONDS);
            journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS);
        }
        try(var restored=new ConstructionJournal(file)) {
            var saved=restored.intents().iterator().next();assertEquals(intent.id(),saved.id());assertEquals(75,saved.price());
            assertEquals(intent.plan(),saved.plan());assertEquals(intent.graph(),saved.graph());
            assertEquals("RECONCILE",ConstructionTransaction.recover(saved).phase());
        }
    }
    @Test void corruptMainOrMissingMainNeverRollsFinancialHistoryBackToBackup() throws Exception {
        Path file=dir.resolve("transactions.json");var intent=ConstructionTransactionTest.intent();
        try(var journal=new ConstructionJournal(file)){journal.checkpoint(intent).get(5,TimeUnit.SECONDS);journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS);}
        Files.writeString(file,"broken");assertThrows(java.io.IOException.class,()->new ConstructionJournal(file));
        Files.delete(file);assertThrows(java.io.IOException.class,()->new ConstructionJournal(file));
    }
    @Test void stableTransactionIdCannotBeRepricedOrReplayed() throws Exception {
        var intent=ConstructionTransactionTest.intent();
        try(var journal=new ConstructionJournal(dir.resolve("transactions.json"))) {
            journal.checkpoint(intent).get(5,TimeUnit.SECONDS);
            var changed=new ConstructionJournal.Intent(intent.id(),intent.actor(),76,intent.plan(),intent.graph(),"INTENT","");
            assertThrows(IllegalArgumentException.class,()->journal.checkpoint(changed));
            journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,()->journal.checkpoint(intent));
        }
    }
    @Test void failedWriterLatchesAndNeverAcknowledgesLaterWrites() throws Exception {
        Path parent=dir.resolve("not-a-directory");Files.writeString(parent,"x");
        try(var journal=new ConstructionJournal(parent.resolve("transactions.json"))) {
            var intent=ConstructionTransactionTest.intent();
            assertThrows(java.util.concurrent.ExecutionException.class,()->journal.checkpoint(intent).get(5,TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,()->journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS));
        }
    }
    @Test void heldJobMustExactlyMatchItsDurableFinancialIntent() {
        var intent=ConstructionTransactionTest.intent();var plan=intent.plan();
        var held=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),0,plan.preview().cost(),"HELD","",plan.reused(),true);
        assertTrue(RailwayWorks.matchesIntent(held,plan));
        var unfunded=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),0,plan.preview().cost(),"QUEUED","");
        assertFalse(RailwayWorks.matchesIntent(unfunded,plan));
        var changed=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),List.of(),0,plan.preview().cost(),"HELD","",plan.reused(),true);
        assertFalse(RailwayWorks.matchesIntent(changed,plan));
    }
    @Test void aPersistedQueuedJobNeverBypassesThePaymentGateAfterRestart() {
        var intent=ConstructionTransactionTest.intent();var plan=intent.plan();
        var queued=new RailwayWorks.Job(intent.id(),plan.world(),plan.country(),plan.entries(),0,plan.preview().cost(),"QUEUED","",plan.reused(),true);
        assertFalse(RailwayWorks.executable(queued,Set.of()));
        assertTrue(RailwayWorks.executable(queued,Set.of(intent.id())));
        var restarted=RailwayWorks.afterRestart(queued);assertEquals("HELD",restarted.status());
        assertEquals(queued.entries(),restarted.entries());assertEquals(queued.cursor(),restarted.cursor());
        assertFalse(RailwayWorks.executable(restarted,Set.of(intent.id())));
    }
    @Test void constructionBudgetSourceIsDurableAndCannotSwitchToCompanyAfterRestart() throws Exception {
        var source=ConstructionTransactionTest.intent();String account="kolej:budzet_0123456789abcdef";
        var intent=new ConstructionJournal.Intent(source.id(),source.actor(),source.price(),source.plan(),source.graph(),"INTENT","",account);
        Path file=dir.resolve("budget-source.json");
        try(var journal=new ConstructionJournal(file)){journal.checkpoint(intent.phase("DEBITTING","")).get(5,TimeUnit.SECONDS);}
        try(var journal=new ConstructionJournal(file)){
            var recovered=journal.intents().iterator().next();assertEquals(account,recovered.debitAccount());
            assertEquals(account,ConstructionTransaction.recover(recovered).debitAccount());
            var changed=new ConstructionJournal.Intent(source.id(),source.actor(),source.price(),source.plan(),source.graph(),"DEBITTING","");
            assertThrows(IllegalArgumentException.class,()->journal.checkpoint(changed));
        }
    }
}
