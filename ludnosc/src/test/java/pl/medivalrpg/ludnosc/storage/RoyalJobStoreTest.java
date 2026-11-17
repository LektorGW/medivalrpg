// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.ludnosc.logic.RoyalManifest;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.service.RoyalRebuildService;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

class RoyalJobStoreTest {
    @TempDir Path temp;
    @Test void orderedCheckpointPersistsOriginalOwnershipAndBuildingSnapshotsWithBackup() throws Exception {
        final var gson=new Gson();final var state=new RoyalRebuildService.State();
        state.manifestSha="verified-sha";state.originalClaims=List.of(RoyalManifest.key(319,69));state.terrainChunks=List.of(RoyalManifest.key(295,53));
        final Building old=new Building(UUID.randomUUID(),RoyalManifest.COUNTRY,"chata",BuildingFunction.HOUSING,new BlockBox("world",5000,80,900,5008,87,908),4,null,0,1,false);
        state.originalBuildings=List.of(old);state.originalBuildingIds=List.of(old.id());
        try(var store=new RoyalJobStore(temp)) {
            store.save(gson.toJson(state)).join();state.phase="BUILDINGS";state.index=120;state.cursor=42;
            store.save(gson.toJson(state)).join();
            final var loaded=gson.fromJson(store.load(),RoyalRebuildService.State.class);
            assertEquals("BUILDINGS",loaded.phase);assertEquals(120,loaded.index);assertEquals(old,loaded.originalBuildings.getFirst());
            assertEquals("PLATFORM",gson.fromJson(store.backup(),RoyalRebuildService.State.class).phase);
        }
    }
    @Test void missingPrimaryRecoversFromBackupRatherThanStartingAnotherJob() throws Exception {
        try(var store=new RoyalJobStore(temp)) {store.save("{\"phase\":\"EVACUATE\"}").join();store.save("{\"phase\":\"TERRAIN\"}").join();Files.delete(temp.resolve("state.json"));
            assertTrue(store.load().contains("EVACUATE"));}
    }
    @Test void failedCheckpointIsObservableAndCannotBeTreatedAsPermissionToContinue() throws Exception {
        Path file=temp.resolve("not-a-directory");Files.writeString(file,"block");
        try(var store=new RoyalJobStore(file)){final CompletableFuture<Void> result=store.save("{}" );assertThrows(java.util.concurrent.CompletionException.class,result::join);assertTrue(result.isCompletedExceptionally());}
    }
    @Test void explicitResumeAlwaysReplaysSafetyPhasesBecauseChunkSavesCanLagTheCursor() {
        final var state=new RoyalRebuildService.State();state.phase="BUILDINGS";state.index=180;state.cursor=12345;state.manifestSha="unchanged";
        state.originalClaims=List.of(RoyalManifest.key(319,69));state.originalBuildingIds=List.of(UUID.randomUUID());
        state.restartFromPlatform();assertEquals("PLATFORM",state.phase);assertEquals(0,state.index);assertEquals(0,state.cursor);assertTrue(state.running);
        assertEquals("unchanged",state.manifestSha);assertEquals(1,state.originalBuildingIds.size());assertEquals(RoyalManifest.key(319,69),state.originalClaims.getFirst());
        state.phase="DONE";assertThrows(IllegalStateException.class,state::restartFromPlatform);
    }
}
