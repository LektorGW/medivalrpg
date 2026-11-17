// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierRecord;

/** Przegląd C 2026-10-29: an empty soldiers.json must not erase the army, and the good .bak must survive the next save. */
class DataStoreRecoveryTest {
    @TempDir Path temp;

    private static Logger quiet() {
        final Logger l = Logger.getAnonymousLogger();
        l.setLevel(Level.OFF);
        return l;
    }

    private static SoldierRecord soldier() {
        return new SoldierRecord(UUID.randomUUID(), "piechota", new Pos("world", 1, 64, 2, 0), 8);
    }

    private void writeBackup(final List<SoldierRecord> list) throws Exception {
        // a genuine snapshot written by the store itself, moved to the .bak slot
        final DataStore seed = new DataStore(this.temp, quiet());
        seed.saveNow(list, Map.of(), Map.of(), new DataStore.Meta());
        Files.move(this.temp.resolve("soldiers.json"), this.temp.resolve("soldiers.json.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test void emptyMainFileFallsBackToBackupInsteadOfLoadingNoSoldiers() throws Exception {
        final SoldierRecord a = soldier();
        final SoldierRecord b = soldier();
        this.writeBackup(List.of(a, b));
        Files.writeString(this.temp.resolve("soldiers.json"), "", StandardCharsets.UTF_8); // 0 bytes after a crash
        final DataStore store = new DataStore(this.temp, quiet());
        final List<SoldierRecord> loaded = store.loadSoldiers();
        assertEquals(2, loaded.size(), "an empty file must not be read as an empty army");
        assertEquals(a.id, loaded.get(0).id);
        assertEquals(b.id, loaded.get(1).id);
        store.saveNow(loaded, Map.of(), Map.of(), new DataStore.Meta());
    }

    @Test void literalNullFileIsTreatedAsBroken() throws Exception {
        this.writeBackup(List.of(soldier()));
        Files.writeString(this.temp.resolve("soldiers.json"), "null", StandardCharsets.UTF_8);
        final DataStore store = new DataStore(this.temp, quiet());
        assertEquals(1, store.loadSoldiers().size());
        try (var files = Files.list(this.temp)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("soldiers.json.broken-")), "broken copy kept for the admin");
        }
        store.saveNow(List.of(), Map.of(), Map.of(), new DataStore.Meta());
    }

    @Test void firstSaveAfterRecoveryKeepsTheGoodBackup() throws Exception {
        final SoldierRecord a = soldier();
        this.writeBackup(List.of(a));
        final String goodBackup = Files.readString(this.temp.resolve("soldiers.json.bak"), StandardCharsets.UTF_8);
        Files.writeString(this.temp.resolve("soldiers.json"), "{ broken", StandardCharsets.UTF_8);
        final DataStore store = new DataStore(this.temp, quiet());
        assertEquals(1, store.loadSoldiers().size());
        store.saveNow(List.of(a, soldier()), Map.of(), Map.of(), new DataStore.Meta());
        // the broken main file must not have replaced the healthy backup
        assertEquals(goodBackup, Files.readString(this.temp.resolve("soldiers.json.bak"), StandardCharsets.UTF_8));
        assertEquals(2, new DataStore(this.temp, quiet()).loadSoldiers().size());
    }

    @Test void healthyFilesStillRotateIntoBackup() throws Exception {
        final DataStore store = new DataStore(this.temp, quiet());
        assertTrue(store.loadSoldiers().isEmpty()); // no files at all: a fresh server
        final SoldierRecord a = soldier();
        store.saveAsync(List.of(a), Map.of(), Map.of(), new DataStore.Meta());
        store.saveNow(List.of(a, soldier()), Map.of(), Map.of(), new DataStore.Meta());
        final DataStore reread = new DataStore(this.temp, quiet());
        assertEquals(2, reread.loadSoldiers().size());
        Files.delete(this.temp.resolve("soldiers.json"));
        assertEquals(1, new DataStore(this.temp, quiet()).loadSoldiers().size(), "the previous save is the .bak");
    }
}
