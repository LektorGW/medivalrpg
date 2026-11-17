// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.listener.TablistService;

class TablistSettingsTest {

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            TablistSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    /** A production config from before the extended tab list: only the classic keys. */
    private static YamlConfiguration oldServerConfig() throws InvalidConfigurationException {
        final YamlConfiguration server = new YamlConfiguration();
        server.loadFromString("""
            tablist:
              enabled: true
              format: "<rank_icon><color>[<country>]</color> <white><player>"
              no-country-format: "<gray>[Dzicz]</gray> <white><player>"
              sort: true
              header: "<gold><bold>MEDIVALRPG</bold></gold>"
              footer: "<gray>Graczy: <white><online>/<max></white></gray>"
              refresh-ticks: 100
            """);
        server.setDefaults(bundled());
        return server;
    }

    @Test
    void bundledDefaults() {
        final KrajeConfig cfg = new KrajeConfig(bundled());
        final TablistSettings ts = cfg.tablist;
        assertTrue(ts.extended);
        assertEquals(40, ts.refreshTicks);
        assertEquals(5_000, ts.cacheMillis);
        assertEquals(300_000, ts.afkMillis);
        assertEquals("https://panel.example.workers.dev/", ts.panelUrl);
        assertEquals(ZoneId.of("Europe/Warsaw"), ts.zone);
        assertTrue(ts.tips.size() >= 5);
        assertEquals(60, ts.tipSeconds);
        assertTrue(ts.news);
        assertEquals("<aqua>❄</aqua>", ts.seasonIcon("zima"));
        assertEquals("", ts.seasonIcon("nieznana"));
        assertTrue(ts.nameFormat.contains("<afk>"));
        assertTrue(TablistService.needsEra(ts.nameFormat), "Kraje shows the era itself → Postep steps aside");
        assertTrue(ts.header.stream().anyMatch(l -> l.contains("<tps>") && l.contains("<ping>")));
        assertTrue(ts.footer.stream().anyMatch(l -> l.contains("<balance>")));
    }

    @Test
    void oldProductionConfigGetsExtendedLayoutFromJar() throws InvalidConfigurationException {
        final KrajeConfig cfg = new KrajeConfig(oldServerConfig());
        assertTrue(cfg.tablist.extended, "no tablist.style in the server file → jar default (extended)");
        assertEquals(40, cfg.tablist.refreshTicks, "extended refresh comes from tablist.extended, not the old 100");
        assertEquals(100, cfg.tablistRefreshTicks, "classic key untouched");
        assertEquals(bundled().getStringList("tablist.extended.header"), cfg.tablist.header);
        assertFalse(TablistService.needsEra(cfg.tablistFormat), "old classic format has no era");
        assertTrue(TablistService.needsEra(cfg.tablist.nameFormat), "but the active extended name shows it");
    }

    @Test
    void classicStyleAndOverrides() throws InvalidConfigurationException {
        final YamlConfiguration server = new YamlConfiguration();
        server.loadFromString("""
            tablist:
              style: classic
              extended:
                refresh-ticks: 5
                afk-seconds: 0
                panel-url: ""
                time-zone: "Nie/Istnieje"
                date-format: "{{zly"
                tips: []
            """);
        server.setDefaults(bundled());
        final TablistSettings ts = new TablistSettings(server);
        assertFalse(ts.extended);
        assertEquals(20, ts.refreshTicks, "clamped to at least 1 s");
        assertEquals(0, ts.afkMillis);
        assertEquals("", ts.panelUrl);
        assertEquals(ZoneId.of("Europe/Warsaw"), ts.zone, "invalid zone → default");
        assertTrue(ts.tips.isEmpty());
        assertFalse(ts.dateFormat.format(ZonedDateTime.now(ts.zone)).isBlank(), "invalid pattern → default");
        assertTrue(TablistSettings.isExtendedStyle(null));
        assertTrue(TablistSettings.isExtendedStyle("rozbudowana"));
        assertFalse(TablistSettings.isExtendedStyle("Klasyczna"));
    }

    @Test
    void polishDate() {
        final TablistSettings ts = new TablistSettings(bundled());
        final ZonedDateTime day = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ts.zone);
        assertEquals("28 października 2026", ts.dateFormat.format(day));
    }

    @Test
    void defaultLinesParseAndDropMissingData() {
        final TablistSettings ts = new TablistSettings(bundled());
        final List<TagResolver> resolvers = new ArrayList<>();
        for (final String p : TabLines.PLACEHOLDERS) {
            resolvers.add(Placeholder.unparsed(p, "{" + p + "}"));
        }
        final TagResolver all = TagResolver.resolver(resolvers);
        final List<String> lines = new ArrayList<>(ts.header);
        lines.addAll(ts.footer);
        for (final String line : lines) {
            final String plain = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(line, all));
            assertFalse(plain.contains("<"), "unresolved tag in: " + line + " → " + plain);
        }
        // a server without Ludnosc, Skarbiec, Nowosci, Postep, map and nobody AFK, player without a country
        final Set<String> missing = Set.of("season", "season_icon", "season_day", "season_days", "balance", "news",
            "era", "era_icon", "rank", "rank_icon", "afk_count", "map_link", "map_url");
        final String header = String.join("\n", TabLines.filter(ts.header, missing));
        final String footer = String.join("\n", TabLines.filter(ts.footer, missing));
        assertTrue(header.contains("<tps>") && header.contains("<date>") && header.contains("<panel_link>"));
        assertFalse(header.contains("<season") || header.contains("<afk_count>") || header.contains("<map_link>"));
        assertTrue(footer.contains("<country>") && footer.contains("<tip>"));
        assertFalse(footer.contains("<balance>") || footer.contains("<news>") || footer.contains("<era>")
            || footer.contains("<rank>"));
    }
}
