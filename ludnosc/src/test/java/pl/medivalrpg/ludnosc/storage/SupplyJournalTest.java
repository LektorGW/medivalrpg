// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.*;
import pl.medivalrpg.ludnosc.model.*;

class SupplyJournalTest {
    @TempDir Path temp;
    final Payload payload=new Payload(new UUID(0,1),new UUID(0,2),"Wladcy:contract-1","RAW_IRON",3);
    Entry entry(Phase phase) {
        var binding=new ProductionBinding(new UUID(0,4),List.of(new BlockPos("world",1,2,3),new BlockPos("world",2,2,3)));
        var plan=new Plan(new UUID(0,3),binding,Arrays.asList("before",null),Arrays.asList(null,"after"),
            Arrays.asList(null,"target"),List.of("target","after"),"previous-player-receipt",List.of("previous-chest","previous-chest"));
        return new Entry(1,payload,new UUID(0,8).toString(),phase,phase==Phase.REGISTERED ? null : plan);
    }
    @Test void retainedIntentAndBothSnapshotsAndMarkersRoundTripExactly() throws Exception {
        try(var store=new SupplyJournal(temp)) {
            Entry intent=entry(Phase.INTENT); store.write(intent).join();
            assertEquals(List.of(intent),store.load());
            store.write(entry(Phase.DELIVERED)).join();
            assertEquals(List.of(entry(Phase.DELIVERED)),store.load());
            assertTrue(Files.readString(temp.resolve(SupplyJournal.fileName(payload.contractId())+".bak")).contains("INTENT"));
        }
    }
    @Test void missingPrimaryNeverRestoresAnOlderRegistrationForReplay() throws Exception {
        try(var store=new SupplyJournal(temp)) {
            store.write(entry(Phase.REGISTERED)).join(); store.write(entry(Phase.INTENT)).join();
            Files.delete(temp.resolve(SupplyJournal.fileName(payload.contractId())));
            assertThrows(java.io.IOException.class,store::load);
        }
    }
    @Test void corruptPrimaryDoesNotFallBackToAnOlderValidBackup() throws Exception {
        try(var store=new SupplyJournal(temp)) {
            store.write(entry(Phase.REGISTERED)).join(); store.write(entry(Phase.INTENT)).join();
            Files.writeString(temp.resolve(SupplyJournal.fileName(payload.contractId())),"{broken");
            assertThrows(java.io.IOException.class,store::load);
        }
    }
    @Test void idIsNeverUsedAsAPathAndRenamedReceiptsAreRejected() throws Exception {
        assertTrue(SupplyJournal.fileName("../../somewhere").matches("[a-f0-9]{64}\\.json"));
        try(var store=new SupplyJournal(temp)) {
            store.write(entry(Phase.INTENT)).join();
            Files.move(temp.resolve(SupplyJournal.fileName(payload.contractId())),temp.resolve("renamed.json"));
            assertThrows(java.io.IOException.class,store::load);
        }
    }
    @Test void orphanTemporaryFileRequiresAuditAndIsNotRemoved() throws Exception {
        Path orphan=temp.resolve(SupplyJournal.fileName(payload.contractId())+".tmp-interrupted"); Files.writeString(orphan,"intent");
        try(var store=new SupplyJournal(temp)) { assertThrows(java.io.IOException.class,store::load); assertTrue(Files.exists(orphan)); }
    }
    @Test void diskFailureIsObservableBeforeAnyTransferMayProceed() throws Exception {
        Path file=temp.resolve("not-a-directory"); Files.writeString(file,"occupied");
        try(var store=new SupplyJournal(file)) { assertThrows(CompletionException.class,() -> store.write(entry(Phase.INTENT)).join()); }
    }
    @Test void cleanCloseRetainsUnresolvedIntentAndCompletedReceipt() throws Exception {
        try(var store=new SupplyJournal(temp)) { store.write(entry(Phase.INTENT)).join(); }
        try(var store=new SupplyJournal(temp)) { assertEquals(Phase.INTENT,store.load().getFirst().phase()); }
    }
    @Test void snapshotsAreImmutableAndDoNotFollowCallerChanges() {
        var source=new ArrayList<>(Arrays.asList("original",null));
        Plan p=new Plan(new UUID(0,3),new ProductionBinding(new UUID(0,4),List.of(new BlockPos("world",1,2,3))),
            source,source,source,source,"",List.of(""));
        source.set(0,"changed"); assertEquals("original",p.sourceBefore().getFirst());
        assertThrows(UnsupportedOperationException.class,() -> p.sourceAfter().set(0,"changed"));
    }
}
