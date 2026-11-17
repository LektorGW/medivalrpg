// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageRecoveryTest {
    @TempDir Path dir;
    Storage open() { var s = new Storage(dir, Logger.getAnonymousLogger()); s.load(); return s; }

    @Test void snapshotExistsOnDiskBeforeCaptureReturnsAndRemovalPersists() {
        var s = open(); var id = UUID.randomUUID(); var snapshot = new Snapshot();
        snapshot.session = "capture"; snapshot.inventoryStored = true; snapshot.items.add("encoded-item");
        s.putSnapshot(id, snapshot);
        var recovered = open();
        assertEquals("encoded-item", recovered.snapshot(id).items.getFirst());
        recovered.removeSnapshot(id); recovered.shutdown();
        var again = open(); assertFalse(again.hasSnapshot(id)); again.shutdown(); s.shutdown();
    }
    @Test void uncommittedFinancialIntentBlocksRecoveryAcrossRestarts() {
        var s = open(); s.beginMoney("transfer escrow -> player 100");
        assertTrue(Files.exists(dir.resolve("money-journal.json")));
        var recovered = open(); assertTrue(recovered.moneyBlocked());
        recovered.saveState();
        assertTrue(Files.exists(dir.resolve("money-journal.json")));
        assertThrows(IllegalStateException.class, () -> recovered.beginMoney("retry"));
        recovered.shutdown(); s.shutdown();
    }
    @Test void committedStateRetiresFinancialIntent() {
        var s = open(); s.beginMoney("transfer player -> escrow 20");
        var ledger = new EscrowLedger("s", "Turniej", "turnieje:s");
        ledger.addFee(UUID.randomUUID(), 20); s.state().escrows.put("s", ledger); s.saveState();
        assertFalse(Files.exists(dir.resolve("money-journal.json")));
        var recovered = open(); assertFalse(recovered.moneyBlocked());
        assertEquals(20, recovered.state().escrows.get("s").held()); recovered.shutdown(); s.shutdown();
    }
    @Test void corruptSnapshotDoesNotReplayBackupOrGetOverwrittenOnDisable() throws Exception {
        Files.writeString(dir.resolve("snapshots.json"), "broken");
        Files.writeString(dir.resolve("snapshots.json.bak"), "{}");
        var s = new Storage(dir, Logger.getAnonymousLogger());
        assertThrows(IllegalStateException.class, s::load); s.shutdown();
        assertEquals("broken", Files.readString(dir.resolve("snapshots.json")));
    }
    @Test void diskFailurePropagatesBeforeCallerCanProceed() throws Exception {
        Files.writeString(dir.resolve("not-a-folder"), "x");
        var files = new JsonFiles(Logger.getAnonymousLogger());
        try { assertThrows(java.util.concurrent.CompletionException.class,
            () -> files.writeNow(dir.resolve("not-a-folder/state.json"), "state")); }
        finally { files.shutdown(); }
    }
    @Test void writesRemainOrderedAndBackupIsPreviousVersion() throws Exception {
        var files = new JsonFiles(Logger.getAnonymousLogger()); var path = dir.resolve("ordered.json");
        files.writeAsync(path, "old"); files.writeNow(path, "new"); files.shutdown();
        assertEquals("\"new\"", Files.readString(path));
        assertEquals("\"old\"", Files.readString(dir.resolve("ordered.json.bak")));
    }
    @Test void runtimeUncertaintyCannotBeClearedByAnUnrelatedSave() {
        var s = open(); s.beginMoney("ambiguous"); s.blockMoney(); s.saveState();
        assertTrue(Files.exists(dir.resolve("money-journal.json"))); s.shutdown();
    }
}
