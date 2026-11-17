// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistence and crash recovery of the ledger. */
class LedgerStoreTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;

    private LedgerStore store() {
        return new LedgerStore(this.dir, LOG, ZoneOffset.UTC, true, 30);
    }

    private Ledger open(final LedgerStore store) throws IOException {
        final Ledger ledger = new Ledger(store);
        store.load(ledger);
        store.start();
        return ledger;
    }

    @Test void durabilityAcknowledgesReplayablePaymentBeforeShutdown() throws Exception {
        var writer=store();var ledger=open(writer);
        ledger.deposit("player:ack",123,"durable-payment");
        writer.durable().get(5,java.util.concurrent.TimeUnit.SECONDS);
        var recovered=new LedgerStore(dir,LOG,ZoneOffset.UTC,true,30);
        var copy=new Ledger(recovered);recovered.load(copy);
        assertEquals(123,copy.balance("player:ack"));writer.close();recovered.close();
    }
    @Test void durabilityNeverAcknowledgesAWriterFailure() throws Exception {
        var writer=store();var ledger=open(writer);
        Files.createDirectory(dir.resolve("journal.log"));
        ledger.deposit("player:ack",123,"must-not-ack");
        assertThrows(java.util.concurrent.ExecutionException.class,()->writer.durable().get(5,java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,()->writer.durable().get(5,java.util.concurrent.TimeUnit.SECONDS));
        writer.close();
    }

    @Test
    void cleanShutdownRestoresBalances() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 500, "start");
        l1.transfer("test:a", "test:b", 200, "przelew");
        l1.requestSnapshot(true);
        s1.close();

        final LedgerStore s2 = this.store();
        final Ledger l2 = new Ledger(s2);
        final LedgerStore.LoadResult r = s2.load(l2);
        assertEquals(300, l2.balance("test:a"));
        assertEquals(200, l2.balance("test:b"));
        assertEquals(2, r.snapshotSeq());
        assertEquals(0, r.replayed());
        assertEquals(2, l2.seq());
    }

    @Test
    void crashWithoutSnapshotIsRecoveredFromJournal() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 500, "start");
        l1.requestSnapshot(true);
        l1.withdraw("test:a", 50, "opłata");
        l1.transfer("test:a", "test:b", 100, "przelew");
        s1.close(); // writer drains the queue; no snapshot after the last two operations = "crash"

        final Ledger l2 = new Ledger(Ledger.Sink.NONE);
        final LedgerStore.LoadResult r = this.store().load(l2);
        assertEquals(350, l2.balance("test:a"));
        assertEquals(100, l2.balance("test:b"));
        assertEquals(1, r.snapshotSeq());
        assertEquals(2, r.replayed());
        assertEquals(3, l2.seq());
        assertTrue(r.gaps().isEmpty());
    }

    @Test
    void tornLastLineIsIgnored() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 500, "start");
        s1.close();
        Files.writeString(this.dir.resolve("journal.log"), "2\t2026-01-01T00:00", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);
        final Ledger l2 = new Ledger(Ledger.Sink.NONE);
        final LedgerStore.LoadResult r = this.store().load(l2);
        assertEquals(500, l2.balance("test:a"));
        assertEquals(1, l2.seq());
        assertTrue(r.skippedLines() >= 1);
    }

    @Test
    void replayIsIdempotentAfterSnapshotBeforeTruncate() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 500, "start");
        l1.deposit("test:a", 5, "drugi");
        s1.close();
        // Simulate "snapshot written, journal not truncated yet": snapshot at seq 2 while the journal still has 1-2.
        final List<String> journal = Files.readAllLines(this.dir.resolve("journal.log"));
        final LedgerStore s2 = this.store();
        final Ledger l2 = this.open(s2);
        l2.requestSnapshot(true);
        s2.close();
        Files.write(this.dir.resolve("journal.log"), journal);
        final Ledger l3 = new Ledger(Ledger.Sink.NONE);
        final LedgerStore.LoadResult r = this.store().load(l3);
        assertEquals(505, l3.balance("test:a"));
        assertEquals(0, r.replayed());
    }

    @Test
    void brokenSnapshotFallsBackToBackupAndLogs() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 100, "1");
        l1.requestSnapshot(true);        // accounts.json @1
        l1.deposit("test:a", 10, "2");
        l1.requestSnapshot(true);        // accounts.json @2, .bak @1; journal truncated
        l1.deposit("test:a", 1, "3");
        s1.close();
        Files.writeString(this.dir.resolve("accounts.json"), "{ zepsuty", StandardCharsets.UTF_8);

        final Ledger l2 = new Ledger(Ledger.Sink.NONE);
        final LedgerStore.LoadResult r = this.store().load(l2);
        assertTrue(r.usedBackup());
        assertEquals(111, l2.balance("test:a"), "seq 2 comes from the daily log, seq 3 from the journal");
        assertEquals(3, l2.seq());
        assertTrue(r.gaps().isEmpty());
    }

    @Test
    void brokenSnapshotWithoutBackupRefusesToStart() throws IOException {
        Files.createDirectories(this.dir);
        Files.writeString(this.dir.resolve("accounts.json"), "nie json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> this.store().load(new Ledger(Ledger.Sink.NONE)));
    }

    @Test
    void operationsAfterCloseAreWrittenSynchronously() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 100, "1");
        l1.requestSnapshot(true);
        s1.close();
        l1.deposit("test:a", 7, "po wyłączeniu"); // e.g. another plugin paying in its onDisable
        final Ledger l2 = new Ledger(Ledger.Sink.NONE);
        this.store().load(l2);
        assertEquals(107, l2.balance("test:a"));
    }

    @Test
    void historyIsNewestFirst() throws IOException {
        final LedgerStore s1 = this.store();
        final Ledger l1 = this.open(s1);
        l1.deposit("test:a", 100, "1");
        l1.deposit("test:b", 100, "inne");
        l1.transfer("test:a", "test:b", 30, "2");
        s1.close();
        final List<TxRecord> h = s1.history("test:a", 10);
        assertEquals(2, h.size());
        assertEquals("2", h.get(0).reason());
        assertEquals("1", h.get(1).reason());
        assertFalse(s1.history("test:a", 1).isEmpty());
        assertEquals(1, s1.history("test:a", 1).size());
    }

    @Test
    void emptyDirectoryStartsEmpty() throws IOException {
        final Ledger l = new Ledger(Ledger.Sink.NONE);
        final LedgerStore.LoadResult r = this.store().load(l);
        assertEquals(0, r.lastSeq());
        assertEquals(0, l.balances().size());
    }
}
