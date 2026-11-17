// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.config.MenuSettings;

class StoredLocationTest {

    private static YamlConfiguration yaml(final String text) {
        final YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(text);
        } catch (final InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return c;
    }

    @Test
    void sectionWithRotation() {
        final YamlConfiguration c = yaml("""
            spawn:
              location:
                world: world
                x: 120.5
                y: 64
                z: -30.25
                yaw: 90.0
                pitch: -12.5
            """);
        assertEquals(Optional.of(new StoredLocation("world", 120.5, 64, -30.25, 90f, -12.5f)),
            StoredLocation.parse(c.get("spawn.location")));
    }

    @Test
    void sectionWithoutRotationAndNumbersAsStrings() {
        final YamlConfiguration c = yaml("""
            location: { world: world_nether, x: "1", y: "70.5", z: 3 }
            """);
        assertEquals(Optional.of(new StoredLocation("world_nether", 1, 70.5, 3, 0f, 0f)),
            StoredLocation.parse(c.get("location")));
    }

    @Test
    void stringForm() {
        assertEquals(Optional.of(new StoredLocation("world", 1.5, 64, 2.5, 180f, 10f)),
            StoredLocation.parse("world, 1.5, 64, 2.5, 180, 10"));
        assertEquals(Optional.of(new StoredLocation("world", 1, 2, 3, 0f, 0f)), StoredLocation.parse("world,1,2,3"));
    }

    @Test
    void emptyOrBrokenMeansWorldSpawn() {
        assertTrue(StoredLocation.parse(null).isEmpty());
        assertTrue(StoredLocation.parse("").isEmpty());
        assertTrue(StoredLocation.parse("world,1,2").isEmpty());
        assertTrue(StoredLocation.parse("world,1,abc,3").isEmpty());
        assertTrue(StoredLocation.parse(", 1, 2, 3").isEmpty());
        assertTrue(StoredLocation.parse("world,NaN,2,3").isEmpty());
        assertTrue(StoredLocation.parse(yaml("l: { x: 1, y: 2, z: 3 }").get("l")).isEmpty(), "no world");
        assertTrue(StoredLocation.parse(yaml("l: { world: w, x: 1, z: 3 }").get("l")).isEmpty(), "no y");
        assertTrue(StoredLocation.parse(42).isEmpty());
    }

    @Test
    void pitchIsClamped() {
        assertEquals(90f, StoredLocation.parse("world,0,0,0,0,200").orElseThrow().pitch());
    }

    @Test
    void roundTripThroughYaml() {
        final StoredLocation loc = new StoredLocation("world", 100.123456, 65.0, -7.98765, -45.06f, 30.04f);
        final YamlConfiguration c = new YamlConfiguration();
        c.set("spawn.location", loc.toMap());
        final YamlConfiguration reloaded = yaml(c.saveToString());
        final StoredLocation back = StoredLocation.parse(reloaded.get("spawn.location")).orElseThrow();
        assertEquals("world", back.world());
        assertEquals(100.123, back.x(), 1e-9);
        assertEquals(-7.988, back.z(), 1e-9);
        assertEquals(-45.1f, back.yaw(), 1e-4);
        assertEquals(30.0f, back.pitch(), 1e-4);
    }

    @Test
    void chunkCoordinates() {
        assertEquals(7, new StoredLocation("w", 120.5, 0, 0, 0, 0).chunkX());
        assertEquals(-1, new StoredLocation("w", 0, 0, -0.5, 0, 0).chunkZ());
        assertEquals(-2, new StoredLocation("w", 0, 0, -17, 0, 0).chunkZ());
        assertEquals("w 120, 64, -1", new StoredLocation("w", 120.5, 64.9, -0.5, 0, 0).describe());
    }

    @Test
    void bundledConfigDefaults() {
        final YamlConfiguration c = YamlConfiguration.loadConfiguration(new InputStreamReader(
            StoredLocationTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        final MenuSettings s = new MenuSettings(c);
        assertTrue(s.spawnLocation.isEmpty(), "empty spawn.location → main world spawn");
        assertEquals(5, s.spawnWarmupSeconds);
        assertEquals(30, s.spawnCooldownSeconds);
        assertEquals(0.5, s.teleportMaxMove);
        assertEquals(10, s.combatTagSeconds);
        assertTrue(s.spawnNewPlayers);
        assertEquals(false, s.spawnOnRespawn);
        assertEquals(1, s.homesMax);
        assertTrue(s.homesOnlyOwnOrWild);
        // every message used by teleports and homes exists
        for (final String key : new String[] {"teleport-start", "teleport-actionbar", "teleport-cancelled-move",
            "teleport-cancelled-damage", "teleport-cancelled-combat", "teleport-cooldown", "teleport-combat",
            "teleport-siege", "teleport-failed", "spawn-arrived", "spawn-missing", "spawn-set", "spawn-set-config-only",
            "home-set", "home-updated", "home-arrived", "home-deleted", "home-unknown", "home-none", "home-limit",
            "home-bad-name", "home-foreign-land", "home-foreign-blocked", "home-world-missing", "home-disabled",
            "homes-header", "homes-line", "homes-hover"}) {
            assertTrue(c.isString("messages." + key), key);
        }
    }
}
