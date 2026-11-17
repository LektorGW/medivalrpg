// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** config.yml parses, every message key used in the code exists, and every message is valid MiniMessage. */
class ConfigMessagesTest {

    private static final Pattern KEY = Pattern.compile("(?:send\\([^,;]+?, |\\.get\\(|plain\\(|item\\(m, Material\\.[A-Z_]+, |item\\(this\\.messages\\.get\\(\\), Material\\.[A-Z_]+, )\"([a-z0-9-]+)\"");
    private static final Pattern TERNARY = Pattern.compile("\\? \"([a-z]+-[a-z0-9-]+)\" : \"([a-z]+-[a-z0-9-]+)\"");

    private static YamlConfiguration config() throws IOException {
        try (var in = ConfigMessagesTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void siegeUnitsPreserveTheTwoEraServerAndLegacyTypes() throws IOException {
        final var cfg=config();
        assertEquals(1,cfg.getInt("units.katapulta.era"));
        assertEquals(2,cfg.getInt("units.armata.era"));
        assertEquals("proch",cfg.getString("units.armata.discovery"));
        assertEquals(3,cfg.getInt("units.artyleria.era"));
        assertEquals(1000,cfg.getInt("limits.global-max"));
        assertTrue(cfg.isConfigurationSection("units.piechota"));
        assertEquals(40,cfg.getInt("units.katapulta.range"));
        assertTrue(cfg.getInt("units.katapulta.cooldown-ticks")>=160);
        assertEquals(1,cfg.getInt("ai.unit-mix.katapulta"));
    }

    @Test
    void ramIsAMedievalUnitForPlayersAndNpcWithConfiguredStrength() throws IOException {
        final var cfg = config();
        assertEquals(1, cfg.getInt("units.taran.era"));
        assertTrue(cfg.getString("units.taran.discovery", "").isEmpty());
        assertTrue(!cfg.getBoolean("units.taran.ranged"));
        assertTrue(cfg.getLong("units.taran.cost") > 0 && cfg.getLong("units.taran.wage") > 0);
        assertTrue(cfg.getDouble("units.taran.health") > cfg.getDouble("units.katapulta.health"));
        assertEquals(1, cfg.getInt("ai.unit-mix.taran"));
        assertTrue(cfg.getBoolean("ai.sieges.rams"));
        final var ram = ArmiaConfig.ram(cfg.getConfigurationSection("units.taran.ram"));
        assertEquals(pl.medivalrpg.armia.logic.SiegeRules.RamSettings.defaults(), ram, "config.yml = wartości domyślne z kodu");
        assertTrue(ram.arrowMultiplier() < 1 && ram.fireMultiplier() > 1);
        assertTrue(ram.wood() > ram.stone() && ram.stone() > ram.fortification());
        // missing section (old production config) = defaults; hostile values are clamped
        assertEquals(pl.medivalrpg.armia.logic.SiegeRules.RamSettings.defaults(), ArmiaConfig.ram(null));
        final YamlConfiguration bad = new YamlConfiguration();
        bad.set("r.blocks-per-strike", 500);
        bad.set("r.strength.wood", 9);
        bad.set("r.order-seconds", 1);
        final var clamped = ArmiaConfig.ram(bad.getConfigurationSection("r"));
        assertEquals(4, clamped.blocksPerStrike());
        assertEquals(1.0, clamped.wood());
        assertEquals(200, clamped.orderTicks());
        assertTrue(messagesHave(cfg, "siege-ram-ordered", "siege-ram-none", "siege-ram-use", "siege-ram-height",
            "help-armia.entries.taranuj.description"));
    }

    private static boolean messagesHave(final YamlConfiguration cfg, final String... keys) {
        for (final String k : keys) {
            if (!cfg.isString("messages." + k)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void everyUsedKeyExists() throws IOException {
        final ConfigurationSection messages = config().getConfigurationSection("messages");
        assertNotNull(messages);
        final TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String src = Files.readString(f);
                final List<String> keys = new ArrayList<>();
                final Matcher m = KEY.matcher(src);
                while (m.find()) {
                    keys.add(m.group(1));
                }
                final Matcher t = TERNARY.matcher(src);
                while (t.find()) {
                    keys.add(t.group(1));
                    keys.add(t.group(2));
                }
                for (final String k : keys) {
                    if (!messages.isString(k)) {
                        missing.add(k + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(), missing, "Brakujące komunikaty");
    }

    @Test
    void helpEntriesComplete() throws IOException {
        final ConfigurationSection messages = config().getConfigurationSection("messages");
        for (final String book : List.of("help-armia", "help-nk")) {
            final ConfigurationSection entries = messages.getConfigurationSection(book + ".entries");
            assertNotNull(entries, book);
            for (final String id : entries.getKeys(false)) {
                for (final String part : List.of("short", "description", "rank")) {
                    assertTrue(entries.isString(id + "." + part), book + "." + id + "." + part);
                }
            }
        }
    }

    @Test
    void allMessagesAreValidMiniMessage() throws IOException {
        final ConfigurationSection messages = config().getConfigurationSection("messages");
        final MiniMessage strict = MiniMessage.builder().strict(false).build();
        for (final String key : messages.getKeys(true)) {
            if (messages.isString(key)) {
                strict.deserialize(messages.getString(key)); // throws on broken syntax
            }
        }
    }

    @Test
    void noPlaceholderInsideClickArguments() throws IOException {
        final ConfigurationSection messages = config().getConfigurationSection("messages");
        final TreeSet<String> bad = new TreeSet<>();
        for (final String key : messages.getKeys(true)) {
            if (messages.isString(key) && Messages.hasPlaceholderInClick(messages.getString(key))) {
                bad.add(key);
            }
        }
        assertEquals(new TreeSet<>(), bad, "MiniMessage nie podstawia placeholderów w <click:…> — użyj <cmd>…</cmd>");
    }

    @Test
    void legacyClickMessagesFromOldServerConfigsAreMigrated() {
        assertEquals("<prefix>Usunąć? <cmd><red>[Potwierdź]</red></cmd>",
            Messages.migrateLegacyClick("<prefix>Usunąć? <click:run_command:'<cmd>'><red>[Potwierdź]</red></click>"));
        assertEquals("<cmd><country></cmd> <gray>chunki",
            Messages.migrateLegacyClick("<click:run_command:'<cmd>'><country></click> <gray>chunki"));
        assertEquals("<cmd><gray>[następna strona]</gray></cmd>",
            Messages.migrateLegacyClick("<click:run_command:'/armia lista <next>'><gray>[następna strona]</gray></click>"));
        // literal commands stay untouched
        final String literal = "<click:run_command:'/armia pomoc'>[pomoc]</click>";
        assertEquals(literal, Messages.migrateLegacyClick(literal));
        assertTrue(!Messages.hasPlaceholderInClick(literal));
    }

    @Test
    void commandPlaceholderProducesClickEvent() {
        final net.kyori.adventure.text.Component c = MiniMessage.miniMessage().deserialize("<cmd><red>[Potwierdź]</red></cmd>",
            Messages.command("/nk usun \"Mazowsze\" potwierdz"));
        final java.util.List<net.kyori.adventure.text.event.ClickEvent> clicks = new ArrayList<>();
        collectClicks(c, clicks);
        assertTrue(!clicks.isEmpty(), "click event");
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND, clicks.get(0).action());
        assertTrue(clicks.get(0).toString().contains("/nk usun"), clicks.get(0).toString());
    }

    private static void collectClicks(final net.kyori.adventure.text.Component c, final List<net.kyori.adventure.text.event.ClickEvent> out) {
        if (c.clickEvent() != null) {
            out.add(c.clickEvent());
        }
        for (final net.kyori.adventure.text.Component child : c.children()) {
            collectClicks(child, out);
        }
    }

    @Test
    void unitsAndAiSectionsPresent() throws IOException {
        final YamlConfiguration c = config();
        assertEquals("mannequin", c.getString("visual"));
        assertTrue(c.getConfigurationSection("units").getKeys(false).containsAll(List.of("piechota", "lucznik", "rycerz", "kusznik")));
        assertTrue(c.getBoolean("units.rycerz.mounted"));
        assertTrue(c.getBoolean("units.lucznik.ranged"));
        assertEquals(1000, c.getInt("limits.global-max"));
        assertEquals(5, c.getInt("ai.tick-minutes"));
    }
}
