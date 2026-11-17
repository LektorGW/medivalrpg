// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.pory.config.PoryConfig;

class ConfigTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            ConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void defaultConfigParses() {
        final PoryConfig c = PoryConfig.from(bundled());
        assertTrue(c.enabled());
        assertEquals(VisualSeason.LATO, c.fallbackSeason());
        assertTrue(c.worlds().isEmpty());
        assertTrue(c.biomes());
        assertTrue(c.snow());
        assertEquals(0.45, c.coverage().coverage(VisualSeason.ZIMA, 1));
        assertEquals(1.0, c.coverage().coverage(VisualSeason.ZIMA, 4));
        assertEquals(0.0, c.coverage().coverage(VisualSeason.JESIEN, 4));
        assertEquals(0.3, c.coverage().coverage(VisualSeason.WIOSNA, 1));
        assertTrue(c.denyBlocks().contains("minecraft:crafting_table"));
        assertTrue(c.leaves());
        assertEquals(6, c.refreshPerPlayer());
    }

    @Test
    void boundsAreEnforced() {
        final YamlConfiguration y = new YamlConfiguration();
        y.set("fallback-season", "monsun");
        y.set("poll-seconds", 0);
        y.set("refresh.chunks-per-tick-per-player", 100000);
        y.set("snow.budget-ms-per-tick", 500.0);
        y.set("snow.coverage.zima", java.util.List.of(5.0));
        y.set("snow.deny-blocks", java.util.List.of("Minecraft:Loom", "tnt"));
        y.set("leaves.enabled", false);
        final PoryConfig c = PoryConfig.from(y);
        assertEquals(VisualSeason.LATO, c.fallbackSeason());
        assertEquals(1, c.pollSeconds());
        assertEquals(200, c.refreshPerPlayer());
        assertEquals(20.0, c.snowBudgetMs());
        assertEquals(1.0, c.coverage().coverage(VisualSeason.ZIMA, 1));
        assertTrue(c.denyBlocks().contains("minecraft:loom"));
        assertTrue(c.denyBlocks().contains("minecraft:tnt"));
        assertFalse(c.leaves());
    }

    @Test
    void everyMessageKeyUsedInCodeExists() {
        final YamlConfiguration y = bundled();
        for (final String key : new String[] {"prefix", "info-header", "info-day", "info-source-ludnosc",
            "info-source-fallback", "info-preview", "info-look.wiosna", "info-look.lato", "info-look.jesien",
            "info-look.zima", "info-tip", "preview-set", "preview-off", "preview-unknown", "refresh-own", "reloaded",
            "players-only", "status-header", "datapack-missing"}) {
            assertTrue(y.isString("messages." + key), key);
        }
        assertFalse(y.getStringList("messages.help").isEmpty());
        assertFalse(y.getStringList("messages.help-admin").isEmpty());
    }
}
