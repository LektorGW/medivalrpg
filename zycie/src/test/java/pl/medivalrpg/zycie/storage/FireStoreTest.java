// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.zycie.logic.FireIncident;

class FireStoreTest {
    @TempDir Path directory;

    @Test void ongoingIncidentAndRecoveryRetrySurviveRestartAndBrokenPrimaryUsesBackup() throws Exception {
        final var incident=new FireIncident(UUID.randomUUID(),"world",new FireIncident.Cell(10,64,20),1000);
        incident.lastAlarmAt=900; incident.nextRecoveryAt=50000;
        final FireStore store=new FireStore(directory,Logger.getAnonymousLogger());
        store.save(List.of(incident)); store.close(List.of(incident));
        assertTrue(Files.exists(directory.resolve("pozary.json.bak")));
        Files.writeString(directory.resolve("pozary.json"),"broken {");
        final FireStore restart=new FireStore(directory,Logger.getAnonymousLogger());
        final var loaded=restart.load();
        assertEquals(1,loaded.size()); assertEquals(incident.id,loaded.getFirst().id);
        assertEquals(900,loaded.getFirst().lastAlarmAt); assertEquals(50000,loaded.getFirst().nextRecoveryAt);
        assertEquals(incident.fires,loaded.getFirst().fires);
        restart.close(loaded);
    }
}
