// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.ludnosc.logic.RoyalManifest;
import pl.medivalrpg.ludnosc.service.LudnoscData;

class RoyalRegistryCheckpointTest {
    @TempDir Path temp;
    @Test void strictCommitOrdersAfterOlderSavesWithoutStoppingTheRegularWriter() throws Exception {
        final var store=new DataStore(temp,Logger.getAnonymousLogger());final var data=new LudnoscData();
        data.state(RoyalManifest.COUNTRY).royalRebuildActive=true;store.saveAsync(data);
        data.state(RoyalManifest.COUNTRY).royalRebuildActive=false;data.state(RoyalManifest.COUNTRY).food=10000;
        store.saveRoyalCheckpoint(data).join();
        final var reload=new LudnoscData();DataCodec.apply(DataCodec.decode(Files.readString(temp.resolve("data.json"))),reload);
        assertFalse(reload.state(RoyalManifest.COUNTRY).royalRebuildActive);assertEquals(10000,reload.state(RoyalManifest.COUNTRY).food);
        data.state(RoyalManifest.COUNTRY).food=9999;store.saveAsync(data);store.saveAndShutdown(data);
        assertTrue(Files.readString(temp.resolve("data.json")).contains("9999"));
    }
    @Test void diskErrorsPropagateThroughFinalCommitFuture() throws Exception {
        final Path file=temp.resolve("blocked");Files.writeString(file,"file");final var store=new DataStore(file,Logger.getAnonymousLogger());
        assertThrows(java.util.concurrent.CompletionException.class,()->store.saveRoyalCheckpoint(new LudnoscData()).join());store.saveAndShutdown(null);
    }
    @Test void completedPlanAndActiveRebuildFlagsSurviveRestartWithoutLosingPendingCitizens() {
        final var data=new LudnoscData();final var st=data.state(RoyalManifest.COUNTRY);
        st.royalRebuildActive=true;st.designExecutionPaused=true;st.designJson="{\"version\":1,\"options\":{\"autoFill\":false}}";
        st.designDone=new java.util.ArrayList<>(java.util.List.of("dom@4850,1000"));
        final var pending=new pl.medivalrpg.ludnosc.model.CitizenRecord(java.util.UUID.randomUUID(),RoyalManifest.COUNTRY,"Anna","minecraft:none",false,"world",4856,91,1028,1,0,true);
        data.putCitizen(pending.withState("world",4857,91,1028,pending.profession(),pending.adult()));
        final var restored=new LudnoscData();DataCodec.apply(DataCodec.decode(DataCodec.encode(data)),restored);
        assertTrue(restored.state(RoyalManifest.COUNTRY).royalRebuildActive);assertTrue(restored.state(RoyalManifest.COUNTRY).designExecutionPaused);
        assertEquals(java.util.List.of("dom@4850,1000"),restored.state(RoyalManifest.COUNTRY).designDone);
        assertTrue(restored.citizen(pending.entityId()).pending());assertFalse(restored.citizen(pending.entityId()).adult());
        assertEquals("minecraft:none",restored.citizen(pending.entityId()).profession());
    }
}
