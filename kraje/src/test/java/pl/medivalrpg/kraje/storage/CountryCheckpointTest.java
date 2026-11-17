// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.SpawnPoint;
import static org.junit.jupiter.api.Assertions.*;

final class CountryCheckpointTest {
    @TempDir Path directory;
    private Country country() {
        return new Country(UUID.randomUUID(),"KRÓLESTWO",0x4034e5,"",UUID.randomUUID(),1L,
            new ChunkPos("world",303,61),new SpawnPoint("world",4856.5,163,1028.5,0,0));
    }
    @Test void finalSafeSpawnOvertakesPendingSavesAndTheLiveWriterRemainsUsable() throws Exception {
        final CountryStorage store=new CountryStorage(directory,Logger.getAnonymousLogger());
        try {
            final Country country=country();
            store.saveAsync(List.of(country),1,List.of());
            country.spawn(new SpawnPoint("world",4856.5,91,1028.5,0,0));
            store.checkpoint(List.of(country),2,List.of()).join();
            assertEquals(91,store.load().countries().getFirst().spawn().y());
            assertEquals(2,store.load().lastDailyTick());
            country.description("Pałac ukończony");
            store.saveAsync(List.of(country),3,List.of());
            store.checkpoint(List.of(country),4,List.of()).join();
            assertEquals("Pałac ukończony",store.load().countries().getFirst().description());
            assertEquals(4,store.load().lastDailyTick());
            assertTrue(Files.isRegularFile(directory.resolve("countries.json.bak")));
        } finally {store.saveAndShutdown(null,0);}
    }
    @Test void failedIoNeverClaimsThatTheCheckpointCompleted() throws Exception {
        final Path blocked=directory.resolve("not-a-directory");Files.writeString(blocked,"keep");
        final CountryStorage store=new CountryStorage(blocked,Logger.getAnonymousLogger());
        try {
            assertThrows(CompletionException.class,()->store.checkpoint(List.of(country()),1,List.of()).join());
            assertEquals("keep",Files.readString(blocked));
        } finally {store.saveAndShutdown(null,0);}
    }
}
