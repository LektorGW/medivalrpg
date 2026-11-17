// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.rpc.MethodSpec;

class ConfigTest {

    static YamlConfiguration load() {
        try (var in = new InputStreamReader(ConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(in);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void defaults() {
        final PanelConfig c = new PanelConfig(load());
        assertEquals("https://panel.example.workers.dev", c.workerUrl);
        assertEquals("", c.pluginSecret, "generated on first start");
        assertTrue(c.admins.isEmpty());
        assertFalse(c.opsAreAdmins, "ops are NOT panel admins by default");
        assertTrue(c.npcManagers.isEmpty());
        assertEquals(5, c.loginAttempts);
        assertEquals(15 * 60_000L, c.loginWindowMillis);
        assertTrue(c.rpcTimeoutMillis < 10_000, "below the Worker's 10 s");
    }

    @Test
    void healthDefaults() {
        final pl.medivalrpg.panel.health.HealthSettings h = new PanelConfig(load()).health;
        assertTrue(h.enabled());
        assertEquals(10, h.intervalSeconds());
        assertEquals(60, h.fineMinutes());
        assertEquals(24, h.coarseHours());
        assertEquals(18.0, h.thresholds().tps());
        assertEquals(45.0, h.thresholds().mspt());
        assertEquals(2000, h.thresholds().npc());
        assertEquals(java.util.Map.of("armia", 1000), h.thresholds().npcPlugin());
        assertEquals("panel.health.alerts", h.alerts().permission());
        assertEquals(15 * 60_000L, h.alerts().cooldownMs());
        // clamped and without a section
        final YamlConfiguration y = load();
        y.set("health.interval-seconds", 1);
        y.set("health.thresholds.npc-plugin.nieznany", 5);
        final pl.medivalrpg.panel.health.HealthSettings c = new PanelConfig(y).health;
        assertEquals(5, c.intervalSeconds());
        assertFalse(c.thresholds().npcPlugin().containsKey("nieznany"));
        assertEquals(10, pl.medivalrpg.panel.health.HealthSettings.from(null).intervalSeconds());
    }

    @Test
    void roleListsAreCaseInsensitive() {
        final YamlConfiguration y = load();
        y.set("admins", java.util.List.of("Maciej", " Kowal "));
        y.set("npc-managers", java.util.List.of("Zarzadca"));
        y.set("worker-url", "https://x.example/");
        final PanelConfig c = new PanelConfig(y);
        assertEquals(Set.of("maciej", "kowal"), c.admins);
        assertEquals(Set.of("zarzadca"), c.npcManagers);
        assertEquals("https://x.example", c.workerUrl);
    }

    @Test
    void allMessagesParse() {
        final YamlConfiguration y = load();
        final Messages m = new Messages(y);
        for (final String key : y.getConfigurationSection("messages").getKeys(true)) {
            if (y.isString("messages." + key)) {
                final Component c = m.get(key, Messages.text("player", "<red>X"), Messages.text("country", "K"),
                    Messages.text("value", "v"), Messages.text("target", "t"), Messages.text("list", "l"), Messages.text("ops", "nie"),
                    Messages.text("seconds", 3), Messages.text("minutes", 10), Messages.text("url", "u"));
                final String plain = PlainTextComponentSerializer.plainText().serialize(c);
                assertFalse(plain.contains("<prefix>"), key);
                if (plain.contains("X")) {
                    assertTrue(plain.contains("<red>X"), "user input must stay literal: " + key);
                }
            }
        }
    }

    @Test
    void everyMutationOfACountryHasANotificationOrIsSilentOnPurpose() {
        final Messages m = new Messages(load());
        for (final String method : MethodSpec.ALL.keySet()) {
            final boolean has = m.has("notify." + method.replace('.', '-'));
            if (method.startsWith("country.set") || method.startsWith("member.") || method.startsWith("relation.")
                || method.startsWith("treasury.") || method.startsWith("army.")) {
                assertTrue(has, "notify message for " + method);
            }
        }
    }
}
