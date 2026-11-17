// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.postep.logic.NpcStrategy;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;

/** The shipped config.yml and drzewko.yml are valid and complete. */
class DefaultConfigTest {

    private static YamlConfiguration resource(final String name) throws IOException {
        try (InputStream in = DefaultConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue(in != null, name + " missing");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static TechTree tree(final List<String> problems) throws IOException {
        return TechTree.of(TreeLoader.load(resource("drzewko.yml"), problems));
    }

    @Test
    void configParsesWithoutProblems() throws IOException {
        final List<String> problems = new ArrayList<>();
        final PostepConfig cfg = new PostepConfig(resource("config.yml"), problems);
        assertEquals(List.of(), problems);
        assertEquals(2, cfg.maxEra());
        assertEquals(List.of("Średniowiecze", "Renesans"),
            cfg.eras.stream().map(EraDef::name).toList());
        assertEquals(21.0, cfg.era(1).minDays());
        assertEquals(21.0, cfg.era(2).minDays());
        assertEquals(21.0, cfg.era(3).minDays());
        assertEquals("Renesans", cfg.era(3).accusative());
        assertTrue(cfg.research.buildingPoints().containsKey("uniwersytet"));
    }

    @Test
    void defaultTreeIsValid() throws IOException {
        final List<String> problems = new ArrayList<>();
        final TechTree t = tree(problems);
        assertEquals(List.of(), problems);
        assertEquals(List.of(), t.validate(4));
        for (int era = 1; era <= 4; era++) {
            final int n = t.inEra(era).size();
            assertTrue(n >= 5 && n <= 8, "epoka " + era + " ma " + n + " odkryć");
            if (era < 4) {
                assertFalse(t.requiredToLeave(era).isEmpty(), "epoka " + era + " bez wymaganych odkryć");
            }
        }
        for (final String id : List.of("kolo_wodne", "plug", "zegar", "gotyk", "uniwersytety", "druk", "proch",
            "nawigacja_oceaniczna", "bankowosc", "astronomia", "maszyna_parowa", "kolej", "elektrycznosc", "stal",
            "telegraf", "beton_zbrojony", "samochod", "lotnictwo", "komputery", "internet")) {
            assertTrue(t.contains(id), id);
        }
        for (final Discovery d : t.all()) {
            assertFalse(d.description().isBlank(), d.id());
            assertFalse(d.effectText().isBlank(), d.id());
            assertFalse(d.effects().isEmpty(), d.id());
        }
    }

    @Test
    void limitedTreeCannotResearchIndustrialOrModernDiscoveries() throws IOException {
        final List<String> problems = new ArrayList<>();
        final PostepConfig cfg = new PostepConfig(resource("config.yml"), problems);
        final TechTree active = TechTree.of(tree(problems).all().stream()
            .filter(d -> d.era() <= cfg.maxEra()).toList());
        assertEquals(List.of(), active.validate(cfg.maxEra()));
        assertTrue(active.contains("druk"));
        assertFalse(active.contains("maszyna_parowa"));
        assertFalse(active.contains("internet"));
        assertTrue(active.inEra(3).isEmpty());
        assertTrue(active.inEra(4).isEmpty());
    }

    @Test
    void eraLimitCanBeConfiguredAndIsClamped() throws IOException {
        final var config = resource("config.yml");
        config.set("awans.maksymalna-epoka", 4);
        assertEquals(4, new PostepConfig(config, new ArrayList<>()).maxEra());
        config.set("awans.maksymalna-epoka", 1);
        assertEquals(1, new PostepConfig(config, new ArrayList<>()).maxEra());
        config.set("awans.maksymalna-epoka", 99);
        assertEquals(4, new PostepConfig(config, new ArrayList<>()).maxEra());
    }

    /** A neutral NPC choosing with unlimited points can always finish every era (the tree is completable). */
    @Test
    void npcCanReachTheLastEra() throws IOException {
        final TechTree t = tree(new ArrayList<>());
        final Set<String> owned = new HashSet<>();
        int era = 1;
        for (int step = 0; step < 200 && era < 4; step++) {
            final int current = era;
            if (t.requiredToLeave(current).stream().allMatch(d -> owned.contains(d.id()))) {
                era++;
                continue;
            }
            final Discovery pick = NpcStrategy.chooseResearch(t, owned, era, NpcStrategy.Personality.NEUTRAL,
                UUID.nameUUIDFromBytes(new byte[] {1}), true).orElseThrow();
            owned.add(pick.id());
        }
        assertEquals(4, era);
    }

    /** Every message key used literally in the code exists in config.yml. */
    @Test
    void everyUsedMessageExists() throws IOException {
        final YamlConfiguration cfg = resource("config.yml");
        final Pattern call = Pattern.compile("(?:\\.get|\\.send|\\.raw|plain|\\.has)\\((?:[^\"()]*?,\\s*)?\"([a-z][a-z0-9.-]+)\"");
        final Set<String> keys = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final Matcher m = call.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        // keys built at run time
        for (final String n : List.of("ustaw", "punkty", "odkryj", "zabierz")) {
            keys.add("admin-usage-" + n);
        }
        for (final String n : List.of("informacje", "badania", "admin")) {
            keys.add("help-categories." + n);
        }
        final List<String> ignored = List.of("tick.godzina", "tick.strefa");
        final List<String> missing = new ArrayList<>();
        for (final String k : keys) {
            if (k.endsWith("-") || k.endsWith(".") || k.contains(".") && !k.startsWith("help-") && !k.startsWith("kategorie.")) {
                continue; // config paths (getString("tick.godzina") ...) are not messages
            }
            if (!ignored.contains(k) && !cfg.isString("messages." + k)) {
                missing.add(k);
            }
        }
        assertEquals(List.of(), missing.stream().sorted().toList());
        assertTrue(keys.size() > 100, "found " + keys.size() + " keys");
    }

    @Test
    void everyHelpEntryHasTexts() throws IOException {
        final YamlConfiguration cfg = resource("config.yml");
        for (final String id : List.of("status", "drzewko", "odkrycia", "epoki", "ranking", "kraj", "pomoc", "odkryj", "cel",
            "inwestuj", "awansuj", "admin-ustaw", "admin-punkty", "admin-odkryj", "admin-zabierz", "admin-tick",
            "admin-info", "admin-przeladuj")) {
            for (final String part : List.of("short", "description", "rank")) {
                assertTrue(cfg.isString("messages.help-entries." + id + "." + part), id + "." + part);
            }
        }
    }
}
