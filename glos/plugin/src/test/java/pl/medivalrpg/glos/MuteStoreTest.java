// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteStoreTest {
    private static final Logger LOG = Logger.getLogger("MuteStoreTest");

    @TempDir
    Path dir;

    @Test
    void writeAtomicKeepsPreviousVersionAsBak() throws Exception {
        Path f = dir.resolve("mutes.yml");
        MuteStore.writeAtomic(f, "mutes:\n  a: 1\n");
        assertFalse(Files.exists(dir.resolve("mutes.yml.bak")), "pierwszy zapis bez .bak");
        MuteStore.writeAtomic(f, "mutes:\n  a: 2\n");
        assertEquals("mutes:\n  a: 2\n", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals("mutes:\n  a: 1\n", Files.readString(dir.resolve("mutes.yml.bak"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("mutes.yml.tmp")), "plik tymczasowy sprzątnięty");
    }

    @Test
    void writeAtomicCreatesMissingDataFolder() throws Exception {
        Path f = dir.resolve("plugins").resolve("Glos").resolve("mutes.yml");
        MuteStore.writeAtomic(f, "mutes: {}\n");
        assertTrue(Files.exists(f));
    }

    @Test
    void readYamlFallsBackToBakWhenMainIsCorrupt() throws Exception {
        Path f = dir.resolve("mutes.yml");
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        MuteStore.writeAtomic(f, "mutes:\n  " + uuid + ": -1\n");
        MuteStore.writeAtomic(f, "mutes:\n  " + uuid + ": -1\n");
        Files.writeString(f, "mutes: [nie: domknięte\n\t:::", StandardCharsets.UTF_8); // przerwany zapis
        YamlConfiguration yaml = MuteStore.readYaml(f, LOG);
        assertNotNull(yaml, "kopia .bak");
        assertEquals(-1L, yaml.getLong("mutes." + uuid, 0L));
    }

    @Test
    void readYamlReadsMainFileAndReturnsNullWhenNothingExists() throws Exception {
        Path f = dir.resolve("mutes.yml");
        assertNull(MuteStore.readYaml(f, LOG));
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        MuteStore.writeAtomic(f, "mutes:\n  " + uuid + ": 1234\n");
        YamlConfiguration yaml = MuteStore.readYaml(f, LOG);
        assertNotNull(yaml);
        assertEquals(1234L, yaml.getLong("mutes." + uuid));
    }
}
