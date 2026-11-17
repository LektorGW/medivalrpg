// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** config.yml parses with the owner's defaults; every message used in the code exists and is valid MiniMessage. */
class ConfigTest {

    private static final Pattern KEY = Pattern.compile("(?:send\\([^,;]+?, |\\.get\\(|plain\\()\"([a-z0-9-]+)\"");
    private static final Pattern TERNARY = Pattern.compile("\\? \"([a-z]+-[a-z0-9-]+)\" : \"([a-z]+-[a-z0-9-]+)\"");

    @Test
    void defaults() {
        final WladcyConfig c = TestSupport.config();
        assertEquals("claude-opus-5-5", c.model);
        assertEquals(WladcyConfig.Effort.LOW, c.effort);
        assertEquals(2048, c.maxTokens);
        assertTrue(c.fallbacks);
        assertEquals(40, c.perPlayerPerDay);
        assertEquals(4, c.cooldownSeconds);
        assertEquals(300, c.maxInputChars);
        assertEquals(3.00, c.dailyBudgetUsd, 1e-9);
        assertTrue(c.allowWar);
        assertEquals(24, c.warCooldownHours);
        assertEquals(40, c.warMinAggression);
        assertEquals(6.0, c.startRadius);
        assertEquals(8.0, c.maxDistance);
        assertEquals(180, c.idleSeconds);
        assertEquals(Rank.RULER, c.diplomacyRank);
        assertEquals(5.0, c.giftTreasuryPercent);
        assertEquals("", c.configApiKey, "the shipped config must never contain a key");
        assertEquals(75, c.styleFreedom);
        assertEquals(100, TestSupport.config(java.util.Map.of("style.freedom", 300)).styleFreedom, "clamped");
    }

    @Test
    void pricing() {
        final WladcyConfig c = TestSupport.config();
        assertEquals(new ModelPrice(4.00, 20.00, 0.20, 5.00), c.price("claude-opus-5-5"));
        assertEquals(new ModelPrice(0.10, 0.50, 0.01, 0.125), c.price("claude-haiku-5-5"));
        // Dated id → longest configured prefix; claude-opus-5-5-x must not pick claude-opus-5.
        assertEquals(c.price("claude-opus-5-5"), c.price("claude-opus-5-5-20261001"));
        assertEquals(new ModelPrice(5.00, 25.00, 0.50, 6.25), c.price("claude-opus-5"));
        assertEquals(new ModelPrice(5.00, 25.00, 0.50, 6.25), c.price("some-future-model"));
    }

    @Test
    void fallbacksOnlyWhereSupported() {
        final WladcyConfig c = TestSupport.config();
        assertTrue(c.fallbacksFor("claude-opus-5-5"));
        assertFalse(c.fallbacksFor("claude-haiku-5-5"), "Claude Haiku 5.5 has no server-side fallback");
        assertFalse(TestSupport.config(Map.of("api.fallbacks", false)).fallbacksFor("claude-opus-5-5"));
    }

    @Test
    void invalidValuesAreClamped() {
        final WladcyConfig c = TestSupport.config(Map.of("api.effort", "turbo", "api.max-tokens", 5, "diplomacy.min-rank", "doradca"));
        assertEquals(WladcyConfig.Effort.LOW, c.effort);
        assertEquals(256, c.maxTokens);
        assertEquals(Rank.ADVISOR, c.diplomacyRank);
    }

    @Test
    void everyUsedKeyExists() throws IOException {
        final ConfigurationSection messages = TestSupport.yaml().getConfigurationSection("messages");
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
    void allMessagesAreValidMiniMessage() {
        final ConfigurationSection messages = TestSupport.yaml().getConfigurationSection("messages");
        final MiniMessage mm = MiniMessage.miniMessage();
        for (final String key : messages.getKeys(true)) {
            if (messages.isString(key)) {
                mm.deserialize(messages.getString(key));
            }
        }
        for (final String id : messages.getConfigurationSection("help-wladca.entries").getKeys(false)) {
            for (final String part : List.of("short", "description", "rank")) {
                assertTrue(messages.isString("help-wladca.entries." + id + "." + part), id + "." + part);
            }
        }
    }
}
