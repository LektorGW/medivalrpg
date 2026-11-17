// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InfrastructureStoreTest {
    @TempDir Path directory;
    @Test void snapshotsAreOrderedAndKeepThePreviousBackup() throws Exception {
        try(var store=new InfrastructureStore(directory)) {
            var state=store.load().get(5,TimeUnit.SECONDS);var first=store.save(state);
            var f=SupplyCoordinatorTest.fixture(SupplyKind.WIEZA);state.fixtures.put(f.id(),f);var second=store.save(state);
            first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
            assertEquals(f,store.load().get(5,TimeUnit.SECONDS).fixtures.get(f.id()));assertTrue(Files.exists(directory.resolve("infrastructure.json.bak")));assertFalse(Files.exists(directory.resolve("infrastructure.json.tmp")));
            assertTrue(Files.readString(directory.resolve("infrastructure.json.bak")).contains("\"fixtures\": {}"));
        }
    }
    @Test void corruptAndIncompleteFilesAreNeverResetToAnEmptyLedger() throws Exception {
        for(String json:new String[]{"{","{}","null","{\"version\":2,\"fixtures\":{}}"}) {
            Files.writeString(directory.resolve("infrastructure.json"),json);
            try(var store=new InfrastructureStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->store.save(new InfrastructureState()).get(5,TimeUnit.SECONDS));}
            assertEquals(json,Files.readString(directory.resolve("infrastructure.json")));
        }
    }
    @Test void missingLedgerWithBackupOrTemporaryFileRequiresAudit() throws Exception {
        for(String suffix:new String[]{".bak",".tmp"}) {
            Path remnant=directory.resolve("infrastructure.json"+suffix);Files.writeString(remnant,"{}");
            try(var store=new InfrastructureStore(directory)){assertThrows(ExecutionException.class,()->store.load().get(5,TimeUnit.SECONDS));}
            Files.delete(remnant);
        }
    }
    @Test void duplicateContainersAndOverLimitAreRejectedBeforeWriting() {
        var s=new InfrastructureState();var f=SupplyCoordinatorTest.fixture(SupplyKind.WEGIEL);s.fixtures.put(f.id(),f);
        s.fixtures.put("other",new InfrastructureState.Fixture("other",f.station(),f.world(),f.x(),f.y(),f.z(),f.rotation(),f.kind(),f.block(),f.receipt()));
        assertThrows(IllegalStateException.class,s::validate);assertFalse(Files.exists(directory.resolve("infrastructure.json")));
    }
    @Test void durablePreparedTransactionSurvivesWithoutAutomaticRecovery() throws Exception {
        var p=new SupplyCoordinatorTest.Fake(SupplyKind.WEGIEL);var c=p.coordinator();c.request(p.f.id(),"train",2,1,1);
        try(var store=new InfrastructureStore(directory)) {
            store.save(c.state()).get(5,TimeUnit.SECONDS);var recovered=store.load().get(5,TimeUnit.SECONDS);
            assertEquals(c.state().pending,recovered.pending);
            var restored=new SupplyCoordinator(p,CompletableFuture.completedFuture(recovered),store::save);restored.tick();
            assertTrue(restored.holds("train"));assertFalse(restored.available());assertEquals(0,p.debits);
        }
    }
}
