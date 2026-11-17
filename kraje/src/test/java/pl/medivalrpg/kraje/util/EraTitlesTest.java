// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.listener.TablistService;

/** Postep integration: era titles, percent format, tab-list format and messages of the bundled config.yml. */
class EraTitlesTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            EraTitlesTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static final EraTitles TITLES = new EraTitles(true, true, Map.of(
        3, Map.of("Cesarstwo", "Imperium"),
        4, Map.of("Królestwo", "Rzeczpospolita", "Księstwo", "Republika", "Wielkie Księstwo", "Wielka Republika")));

    @Test
    void npcTitlesDependOnEra() {
        assertEquals("Królestwo_Mazowsza", TITLES.apply("Królestwo_Mazowsza", true, 1));
        assertEquals("Królestwo_Mazowsza", TITLES.apply("Królestwo_Mazowsza", true, 3));
        assertEquals("Rzeczpospolita_Mazowsza", TITLES.apply("Królestwo_Mazowsza", true, 4));
        assertEquals("Republika Śląska", TITLES.apply("Księstwo Śląska", true, 4));
        assertEquals("Republika", TITLES.apply("księstwo", true, 4), "whole name, case ignored");
    }

    @Test
    void replacementsAreCumulativeAndLongestFirst() {
        // era 4 has no Cesarstwo → the era-3 title still applies
        assertEquals("Imperium_Wschodu", TITLES.apply("Cesarstwo_Wschodu", true, 4));
        assertEquals("Imperium_Wschodu", TITLES.apply("Cesarstwo_Wschodu", true, 3));
        assertEquals("Cesarstwo_Wschodu", TITLES.apply("Cesarstwo_Wschodu", true, 2));
        // "Wielkie Księstwo" beats nothing shorter; underscores and spaces are alike, separator style is kept
        assertEquals("Wielka_Republika_Litwy", TITLES.apply("Wielkie_Księstwo_Litwy", true, 4));
        assertEquals("Wielka Republika Litwy", TITLES.apply("Wielkie Księstwo Litwy", true, 4));
    }

    @Test
    void onlyWholeWordsAndNpcOnly() {
        assertEquals("Królestwowo", TITLES.apply("Królestwowo", true, 4), "prefix must end at a separator");
        assertEquals("Aldoria", TITLES.apply("Aldoria", true, 4));
        assertEquals("Królestwo_Graczy", TITLES.apply("Królestwo_Graczy", false, 4), "player countries keep their names");
        assertTrue(TITLES.appliesTo(true));
        assertFalse(TITLES.appliesTo(false));

        final EraTitles all = new EraTitles(true, false, Map.of(4, Map.of("Królestwo", "Rzeczpospolita")));
        assertEquals("Rzeczpospolita_Graczy", all.apply("Królestwo_Graczy", false, 4));
        final EraTitles off = new EraTitles(false, false, Map.of(4, Map.of("Królestwo", "Rzeczpospolita")));
        assertEquals("Królestwo_Graczy", off.apply("Królestwo_Graczy", true, 4));
        assertFalse(EraTitles.NONE.enabled());
    }

    @Test
    void bundledConfigEnablesNpcTitles() {
        final KrajeConfig cfg = new KrajeConfig(bundled());
        assertTrue(cfg.eraTitles.appliesTo(true));
        assertFalse(cfg.eraTitles.appliesTo(false));
        assertEquals("Rzeczpospolita_Polska", cfg.eraTitles.apply("Królestwo_Polska", true, 4));
        assertEquals("Federacja Północy", cfg.eraTitles.apply("Cesarstwo Północy", true, 4));
        assertEquals("Imperium Północy", cfg.eraTitles.apply("Cesarstwo Północy", true, 3));
        assertTrue(TablistService.needsEra(cfg.tablistFormat), cfg.tablistFormat);
        assertTrue(cfg.mapPopupEra.contains("{era}") && cfg.mapPopupEra.contains("{progress}"));
    }

    @Test
    void oldServerConfigFallsBackToJarDefaults() throws InvalidConfigurationException {
        final YamlConfiguration server = new YamlConfiguration();
        server.loadFromString("tablist:\n  format: \"<color>[<country>]</color> <player>\"\n");
        server.setDefaults(bundled());
        final KrajeConfig cfg = new KrajeConfig(server);
        assertEquals("Republika_X", cfg.eraTitles.apply("Księstwo_X", true, 4));
        assertFalse(TablistService.needsEra(cfg.tablistFormat), "old format: Postep keeps appending its icon");
    }

    @Test
    void percentAndInfoMessages() {
        assertEquals("0%", Format.percent(0.0));
        assertEquals("52%", Format.percent(0.523));
        assertEquals("99%", Format.percent(0.999));
        assertEquals("100%", Format.percent(1.0));
        assertEquals("100%", Format.percent(3.0));
        assertEquals("0%", Format.percent(Double.NaN));

        final Messages m = new Messages(bundled(), Logger.getAnonymousLogger());
        final String line = PlainTextComponentSerializer.plainText().serialize(m.get("info-era",
            Placeholder.parsed("badge", "<#C9A227>⚜ Renesans</#C9A227>"), Placeholder.unparsed("progress", "52%"),
            Placeholder.unparsed("name", "Aldoria"), Placeholder.unparsed("era", "Renesans")));
        assertTrue(line.contains("⚜ Renesans") && line.contains("52%"), line);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(m.get("info-era-max",
            Placeholder.parsed("badge", "Nowoczesność"))).contains("Nowoczesność"));
    }
}
