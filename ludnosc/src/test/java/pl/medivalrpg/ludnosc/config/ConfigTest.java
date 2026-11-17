// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;

class ConfigTest {

    private static YamlConfiguration load() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            ConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void defaults() {
        final LudnoscConfig cfg = new LudnoscConfig(load(), Logger.getAnonymousLogger());
        assertTrue(cfg.adoptVillagers);
        assertEquals(LudnoscConfig.ConquestMode.SWITCH, cfg.onConquest);
        assertEquals(LudnoscConfig.UnclaimMode.RELEASE, cfg.onUnclaim);
        assertEquals(LudnoscConfig.DisbandMode.RELEASE, cfg.onDisband);
        assertEquals(Rank.ADVISOR, cfg.buildRank);
        assertEquals(LocalTime.of(6, 0), cfg.dailyTime);
        assertEquals(5, cfg.foodValue(Material.BREAD));
        assertEquals(0, cfg.foodValue(Material.STONE));
        assertEquals(3, cfg.rules.taxPerCitizen());
        assertEquals(2.25, cfg.rules.multiplier("minecraft:librarian")); // +50% tax (2026-10-29): 1.5 × 1.5
        assertEquals(1.5, cfg.rules.multiplier("minecraft:farmer"));
        assertTrue(cfg.requireMaterials, "buildings use real wood and stone by default");
        assertTrue(cfg.logistics.enabled());
        assertEquals(32, cfg.logistics.load());
        assertEquals(12, cfg.carryLoad);
        assertEquals(30, cfg.professionWeights.get("farmer"));
        assertEquals(List.of("dom"), cfg.npcStartingBuildings);
        assertTrue(cfg.names.size() >= 50);
        assertEquals(LudnoscConfig.BuilderType.MANNEQUIN, cfg.builderType);
        assertFalse(cfg.terrainBlocks.isEmpty());
        assertTrue(cfg.conscriptUnloaded, "recruitment works without players nearby");
        assertTrue(cfg.keepLoaded);
        assertEquals(64, cfg.maxLoadedChunks);
        assertTrue(cfg.workTime(6000));
        assertFalse(cfg.workTime(18000));
        assertEquals(6, cfg.levels.levels().size());
        assertEquals(150000L, cfg.buildings.get("palac").cost());
        assertEquals(10, cfg.queueSize);
        assertEquals(5, cfg.maxSitesPerCountry);
        assertEquals(12, cfg.maxSitesCap);
        assertEquals(10, pl.medivalrpg.ludnosc.logic.QueueRules.siteSlots(5, 1, 5, 0, 1, 12), "Metropolia: 10 sites");
        assertEquals(12, pl.medivalrpg.ludnosc.logic.QueueRules.siteSlots(5, 1, 5, 1, 4, 12), "capped");
        assertEquals(6, cfg.levels.levels().size(), "Metropolia");
    }

    @Test
    void alertsHaveDefaultsAndSafePolishMessagesForOldConfigs() {
        final var defaults = load();
        assertTrue(defaults.getBoolean("alerts.enabled"));
        assertEquals(600, defaults.getInt("alerts.cooldown-seconds"));
        assertTrue(defaults.getStringList("alerts.npc-manager-uuids").isEmpty());
        final var merge = YamlConfiguration.loadConfiguration(Path.of("alerts-config-merge.yml").toFile());
        for (final String path : merge.getKeys(true)) {
            if (!merge.isConfigurationSection(path)) assertEquals(defaults.get(path), merge.get(path), path);
        }
        final var old = new YamlConfiguration(); old.setDefaults(defaults);
        final var messages = new Messages(old, Logger.getAnonymousLogger());
        for (final String key : List.of("alert-storage-full", "alert-torches", "alert-storage-missing",
            "alert-storage-unavailable", "alert-work-recovered", "alert-food", "alert-food-recovered")) {
            assertTrue(messages.has(key));
            String text = plain(messages.get(key, Placeholder.unparsed("country", "<red>Wieś</red>"),
                Placeholder.unparsed("detail", "Kopalnia 12345678 (world: 1, 2, 3)")));
            assertTrue(text.contains("<red>Wieś</red>"), key);
            assertFalse(text.contains("<detail>"), key);
        }
    }

    @Test
    void supplyConfigurationUsesJarDefaultsAndOnlyExplicitResourceMaterials() {
        final var old = new YamlConfiguration(); old.setDefaults(load());
        assertTrue(old.getBoolean("supply.enabled")); assertEquals(16,old.getInt("supply.warehouse-range"));
        var allowed=old.getStringList("supply.allowed-materials");
        assertTrue(allowed.containsAll(List.of("COBBLESTONE","RAW_IRON","TORCH","STONE_BRICKS","COD","SALMON","BREAD","CARROT","POTATO")));
        assertFalse(allowed.contains("IRON_NUGGET")); assertFalse(allowed.contains("COPPER_NUGGET"));
        for(String material:allowed) assertNotNull(Material.valueOf(material)); // Registry/item checks are exercised inside Paper.
    }

    @Test
    void workSeasonsAndMines() {
        final LudnoscConfig cfg = new LudnoscConfig(load(), Logger.getAnonymousLogger());
        final WorkSettings w = cfg.work;
        assertTrue(w.enabled);
        assertTrue(w.seasons.enabled());
        assertEquals(7.0, w.seasons.lengthDays());
        assertEquals(pl.medivalrpg.ludnosc.logic.Season.JESIEN, w.seasons.first());
        assertEquals(0.25, w.seasons.foodMultiplier(pl.medivalrpg.ludnosc.logic.Season.ZIMA));
        assertEquals(1.4, w.seasons.foodMultiplier(pl.medivalrpg.ludnosc.logic.Season.JESIEN));
        assertEquals(0.4, w.seasons.growthChance(pl.medivalrpg.ludnosc.logic.Season.ZIMA));
        assertEquals(0.0, w.seasons.blightChance().get(pl.medivalrpg.ludnosc.logic.Season.ZIMA));
        assertEquals(0.1, w.seasons.maxLeaveFraction());
        assertEquals(pl.medivalrpg.ludnosc.logic.MineYield.defaults(), w.mineYield, "config.yml and code defaults agree");
        assertEquals(pl.medivalrpg.ludnosc.logic.SeasonRules.defaults().food(), w.seasons.food());
        assertEquals(List.of("farmer"), w.professions.get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.FARMER));
        assertEquals("mason", w.professions.get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.MINER).get(0));
        assertEquals(2, w.slots.get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.FARMER));
        assertTrue(w.workTime(3000));
        assertFalse(w.workTime(9500));
        assertTrue(w.deliveryTime(9500));
        assertFalse(w.deliveryTime(13000));
        assertTrue(w.mineAutoDig);
        assertTrue(w.mineFallProtection);
        assertEquals(25L, cfg.resourcePrices.get("kruszce"));
        // an old config without the new sections: code defaults
        final LudnoscConfig empty = new LudnoscConfig(new YamlConfiguration(), null);
        assertEquals(pl.medivalrpg.ludnosc.logic.MineYield.defaults(), empty.work.mineYield);
        assertEquals(0.25, empty.work.seasons.foodMultiplier(pl.medivalrpg.ludnosc.logic.Season.ZIMA));
        assertEquals(pl.medivalrpg.ludnosc.logic.JobBoard.defaultProfessions(), empty.work.professions);
    }

    /** Season texts are looked up by id ({@code season-hint.<id>}). */
    @Test
    void seasonHints() {
        final Messages messages = new Messages(load(), Logger.getAnonymousLogger());
        for (final pl.medivalrpg.ludnosc.logic.Season s : pl.medivalrpg.ludnosc.logic.Season.values()) {
            assertTrue(messages.has("season-hint." + s.id()), s.id());
        }
        for (final String key : List.of("info-season", "season-join", "season-now", "admin-season-set")) {
            assertTrue(messages.has(key), key);
        }
    }

    @Test
    void badValuesFallBack() {
        final YamlConfiguration y = new YamlConfiguration();
        y.set("daily.time", "25:99");
        y.set("citizens.on-conquest", "zjedz");
        y.set("building.rank", "krol");
        y.set("building.builders-per-site", 99);
        final LudnoscConfig cfg = new LudnoscConfig(y, null);
        assertEquals(LocalTime.of(6, 0), cfg.dailyTime);
        assertEquals(LudnoscConfig.ConquestMode.SWITCH, cfg.onConquest);
        assertEquals(Rank.ADVISOR, cfg.buildRank);
        assertEquals(8, cfg.buildersPerSite);
        assertEquals(Set.of("none"), cfg.professionWeights.keySet());
    }

    @Test
    void allMessagesParse() {
        final YamlConfiguration yaml = load();
        final Messages messages = new Messages(yaml, Logger.getAnonymousLogger());
        for (final String key : yaml.getConfigurationSection("messages").getKeys(true)) {
            if (!yaml.getConfigurationSection("messages").isString(key)) {
                continue;
            }
            final Component c = messages.get(key, Placeholder.unparsed("country", "Aldoria"), Placeholder.unparsed("name", "Chata"),
                Placeholder.component("status", Component.text("ok")), Placeholder.styling("kolor", NamedTextColor.RED),
                Placeholder.unparsed("imie", "Jan"));
            assertFalse(plain(c).contains("<prefix>"), key);
        }
    }

    /** Every message key used in the code exists in config.yml (catches typos in keys). */
    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        final Messages messages = new Messages(load(), Logger.getAnonymousLogger());
        final Pattern[] patterns = {
            Pattern.compile("\\.send\\([^,()]+(?:\\(\\))?, \"([a-z0-9.\\-]+)\""),
            Pattern.compile("messages\\(\\)\\.get\\(\"([a-z0-9.\\-]+)\""),
            Pattern.compile("m\\.get\\(\"([a-z0-9.\\-]+)\""),
            Pattern.compile("notifyCountry\\([^,]+, \"([a-z0-9.\\-]+)\""),
            Pattern.compile("cancel\\([^;]*\"(site-[a-z\\-]+)\"\\)"),
        };
        final Set<String> missing = new TreeSet<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String src = Files.readString(f);
                for (final Pattern p : patterns) {
                    final Matcher m = p.matcher(src);
                    while (m.find()) {
                        found++;
                        if (!m.group(1).endsWith("-") && !messages.has(m.group(1))) { // "x-" + suffix = dynamic, checked below
                            missing.add(m.group(1) + " (" + f.getFileName() + ")");
                        }
                    }
                }
            }
        }
        assertTrue(found > 60, "scanner finds keys: " + found);
        assertEquals(Set.of(), missing);
        for (final String dir : List.of("north", "east", "south", "west")) {
            assertTrue(messages.has("facing-" + dir));
        }
        for (final String b : List.of("build-button-rotate", "build-button-confirm", "build-button-cancel")) {
            assertTrue(messages.has(b + "-hover"));
        }
    }
}
