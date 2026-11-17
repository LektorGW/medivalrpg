// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FreightStoreTest {
    @TempDir Path directory;
    @Test void orderedAtomicWritesLeaveBackupAndReloadExactly() throws Exception {
        try(var store=new FreightStore(directory)) {
            FreightState s=store.load().get(5,TimeUnit.SECONDS);var first=store.save(s);
            var w=FreightCoordinatorTest.warehouse("a",1);s.warehouses.put("a",w);var second=store.save(s);
            first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
            assertEquals(w.receipt,store.load().get(5,TimeUnit.SECONDS).warehouses.get("a").receipt);
            assertTrue(Files.exists(directory.resolve("freight.json.bak")));assertFalse(Files.exists(directory.resolve("freight.json.tmp")));
            assertTrue(Files.readString(directory.resolve("freight.json.bak")).contains("\"warehouses\": {}"));
        }
    }
    @Test void malformedFileIsPreservedAndNeverReplacedWithEmptyState() throws Exception {
        Files.writeString(directory.resolve("freight.json"),"{");
        try(var store=new FreightStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));}
        assertEquals("{",Files.readString(directory.resolve("freight.json")));
    }
    @Test void orphanTemporaryOrBackupFileRequiresAudit() throws Exception {
        Files.writeString(directory.resolve("freight.json.tmp"),"{}");
        try(var store=new FreightStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));}
    }
    @Test void serializedSnapshotIsNotChangedByLaterMainThreadMutation() throws Exception {
        try(var store=new FreightStore(directory)) {
            FreightState state=new FreightState();var save=store.save(state);state.version=99;
            save.get(5,TimeUnit.SECONDS);assertEquals(1,store.load().get(5,TimeUnit.SECONDS).version);
        }
    }
    @Test void semanticallyInvalidLedgerDoesNotLoad() throws Exception {
        Files.writeString(directory.resolve("freight.json"),"{\"version\":2}");
        try(var store=new FreightStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));}
    }
    @Test void emptyObjectCannotSilentlyReplaceDurableLedger() throws Exception {
        Files.writeString(directory.resolve("freight.json"),"{}");
        try(var store=new FreightStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));}
        assertEquals("{}",Files.readString(directory.resolve("freight.json")));
    }
}
