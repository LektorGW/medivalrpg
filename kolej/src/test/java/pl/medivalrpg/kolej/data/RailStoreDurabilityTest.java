// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.data;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RailStoreDurabilityTest {
    @TempDir Path directory;
    @Test void acknowledgedSnapshotContainsExactTenderAndReceiptEvenAfterMemoryChanges() throws Exception {
        var data=new RailData();var train=new RailData.Train();train.id="train";train.supplyReceipt="test-receipt";train.steam.water=250;train.steam.coal=10;data.trains.put(train.id,train);
        try(var store=new RailStore(directory,Logger.getAnonymousLogger())) {
            var saved=store.save(data);train.steam.water=4000;train.supplyReceipt="different";saved.get(5,TimeUnit.SECONDS);
            var restored=store.load().trains.get("train");assertEquals(250,restored.steam.water);assertEquals(10,restored.steam.coal);assertEquals("test-receipt",restored.supplyReceipt);
            assertFalse(Files.exists(directory.resolve("kolej.json.tmp")));
        }
    }
    @Test void writeFailureIsObservableAndLaterWritesCannotHideIt() throws Exception {
        Files.createDirectory(directory.resolve("kolej.json.tmp"));
        try(var store=new RailStore(directory,Logger.getAnonymousLogger())) {
            assertThrows(ExecutionException.class,()->store.save(new RailData()).get(5,TimeUnit.SECONDS));
            Files.delete(directory.resolve("kolej.json.tmp"));
            assertThrows(ExecutionException.class,()->store.save(new RailData()).get(5,TimeUnit.SECONDS));
            assertFalse(Files.exists(directory.resolve("kolej.json")));
        }
    }
}
