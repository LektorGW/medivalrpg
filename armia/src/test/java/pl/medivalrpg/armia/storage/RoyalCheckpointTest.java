// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.armia.logic.RoyalGarrisonRules;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierRecord;

class RoyalCheckpointTest {
    @TempDir Path temp;
    @Test void holdCheckpointFollowsQueuedOldSnapshotAndKeepsWriterAliveForFinalDeployment() {
        final DataStore store=new DataStore(temp,Logger.getAnonymousLogger());
        final SoldierRecord soldier=new SoldierRecord(RoyalGarrisonRules.COUNTRY,"katapulta",new Pos("world",10,70,20,0),8);
        soldier.health=17.5;soldier.morale=72;
        try {
            store.saveAsync(List.of(soldier),Map.of(),Map.of(),new DataStore.Meta());
            soldier.royalEvacuated=true;
            store.checkpointSoldiers(List.of(soldier));
            final SoldierRecord held=store.loadSoldiers().getFirst();
            assertTrue(held.royalEvacuated);assertTrue(held.suspended());assertNull(held.transportShip);
            assertEquals(soldier.id,held.id);assertEquals("katapulta",held.type);assertEquals(17.5,held.health);assertEquals(72,held.morale);
            // The barrier must not shut down the writer, unlike the plugin-disable saveNow method.
            soldier.royalEvacuated=false;soldier.post=new Pos("world",30,91,40,0);soldier.last=soldier.post;soldier.royalPost="siege_01";
            store.saveAsync(List.of(soldier),Map.of(),Map.of(),new DataStore.Meta());
            store.checkpointSoldiers(List.of(soldier));
            final SoldierRecord deployed=store.loadSoldiers().getFirst();
            assertFalse(deployed.suspended());assertEquals("siege_01",deployed.royalPost);assertEquals(91,deployed.post.y());
            assertEquals(17.5,deployed.health);
        } finally {store.saveNow(List.of(soldier),Map.of(),Map.of(),new DataStore.Meta());}
    }
    @Test void checkpointFailureIsPropagatedSoTheRebuildCannotProceedIntoTerrain() throws Exception {
        final Path file=temp.resolve("not-a-directory");Files.writeString(file,"occupied");
        final DataStore store=new DataStore(file,Logger.getAnonymousLogger());
        try {assertThrows(IllegalStateException.class,()->store.checkpointSoldiers(List.of()));}
        finally {store.saveNow(List.of(),Map.of(),Map.of(),new DataStore.Meta());}
    }
}
