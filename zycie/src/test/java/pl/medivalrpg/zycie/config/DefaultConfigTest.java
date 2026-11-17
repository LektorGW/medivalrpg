// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.zycie.logic.DayPhase;
import pl.medivalrpg.zycie.logic.JobCatalog;
import pl.medivalrpg.zycie.logic.TextContext;
import pl.medivalrpg.zycie.logic.TextPool;
import pl.medivalrpg.zycie.logic.TextRule;

/** The shipped config.yml parses without problems; text selection by context and era; city jobs. */
class DefaultConfigTest {

    private static YamlConfiguration yaml;
    private static ZycieConfig cfg;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = DefaultConfigTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertTrue(in != null, "config.yml missing");
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        final List<String> problems = new ArrayList<>();
        cfg = new ZycieConfig(yaml, problems);
        assertEquals(List.of(), problems);
    }

    private static TextContext ctx(final DayPhase phase, final int era, final int mood) {
        return new TextContext(phase, era, mood, false, false, false, false, false, false, false, Set.of(),
            Set.of("farmer", "rolnik"), null);
    }

    private static Set<String> lines(final List<TextRule> rules) {
        final Set<String> out = new HashSet<>();
        for (final TextRule r : rules) {
            r.variants().forEach(out::addAll);
        }
        return out;
    }

    /** Every line the pool can say in this context. */
    private static Set<String> possible(final TextPool pool, final TextContext c) {
        return lines(pool.matching(c));
    }

    @Test
    void poolsAreFilled() {
        assertTrue(cfg.ambient.size() > 20);
        assertTrue(cfg.reactions.size() >= 6);
        assertTrue(cfg.alarm.size() >= 1);
        assertTrue(cfg.protest.size() >= 1);
        assertTrue(cfg.festivalTexts.size() >= 1);
        assertTrue(cfg.dialogues.size() >= 10);
        assertTrue(cfg.dialogues.rules().stream().allMatch(r -> r.variants().getFirst().size() >= 2), "dialogues have 2–3 lines");
        assertTrue(cfg.dialogues.rules().stream().allMatch(r -> r.variants().getFirst().size() <= 3));
    }

    @Test
    void everyTextIsValidMiniMessage() {
        final MiniMessage mm = MiniMessage.miniMessage();
        for (final TextPool p : List.of(cfg.ambient, cfg.reactions, cfg.alarm, cfg.protest, cfg.festivalTexts, cfg.siegeTexts,
            cfg.levyTexts, cfg.dialogues)) {
            for (final String line : lines(p.rules())) {
                mm.deserialize(line); // throws on broken tags in strict mode only; at least it must parse
                assertFalse(line.isBlank());
            }
        }
        for (final String key : yaml.getConfigurationSection("messages").getKeys(true)) {
            if (yaml.isString("messages." + key)) {
                mm.deserialize(yaml.getString("messages." + key));
            }
        }
    }

    @Test
    void eraChangesVocabulary() {
        final Set<String> medieval = possible(cfg.ambient, ctx(DayPhase.WORK, 1, 80));
        final Set<String> industrial = possible(cfg.ambient, ctx(DayPhase.WORK, 3, 80));
        final Set<String> modern = possible(cfg.ambient, ctx(DayPhase.WORK, 4, 50));
        assertTrue(medieval.contains("Niech Bóg ma króla w opiece!"));
        assertFalse(industrial.contains("Niech Bóg ma króla w opiece!"));
        assertTrue(industrial.contains("W fabryce znów 12 godzin…"));
        assertFalse(medieval.contains("W fabryce znów 12 godzin…"));
        assertTrue(modern.contains("Widziałeś nowy wieżowiec?"));
        assertFalse(medieval.contains("Widziałeś nowy wieżowiec?"));
    }

    @Test
    void contextSelectsTopics() {
        final TextContext war = new TextContext(DayPhase.WORK, 1, 40, true, false, false, false, false, false, false, Set.of(),
            Set.of(), null);
        assertTrue(possible(cfg.ambient, war).contains("Znów wojna z <wrog>…"));
        assertFalse(possible(cfg.ambient, ctx(DayPhase.WORK, 1, 40)).contains("Znów wojna z <wrog>…"));
        final TextContext hungry = new TextContext(DayPhase.NOON, 2, 30, false, false, true, true, false, false, false, Set.of(),
            Set.of(), null);
        final Set<String> h = possible(cfg.ambient, hungry);
        assertTrue(h.contains("Chleba nie ma…"));
        assertTrue(h.contains("Znowu podatki w górę!"));
        assertTrue(h.contains("Czas na obiad!"), "the time of day still counts");
        final TextContext event = new TextContext(DayPhase.WORK, 2, 60, false, false, false, false, false, false, false,
            Set.of("awans_epoki"), Set.of(), null);
        assertTrue(possible(cfg.ambient, event).contains("Nastały nowe czasy!"));
        assertTrue(possible(cfg.ambient, ctx(DayPhase.MASS, 1, 50)).contains("Amen."));
        assertFalse(possible(cfg.ambient, ctx(DayPhase.WORK, 1, 50)).contains("Amen."));
    }

    @Test
    void specificRulesArePreferred() {
        // in a hungry town most picks talk about hunger rather than generic chatter
        final TextContext hungry = new TextContext(DayPhase.WORK, 1, 30, false, false, true, false, false, false, false, Set.of(),
            Set.of(), null);
        final Set<String> hungerLines = Set.of("Chleba nie ma…", "Dzieci płaczą z głodu.", "Spichlerz pusty!");
        final Set<String> generic = Set.of("Ech, roboty nie ubywa…", "Ładna dziś pogoda.", "Co tam słychać?", "Hm…");
        final SplittableRandom random = new SplittableRandom(42);
        int hunger = 0;
        int chatter = 0;
        for (int i = 0; i < 3000; i++) {
            final String line = cfg.ambient.pickLine(hungry, random, List.of()).orElseThrow();
            if (hungerLines.contains(line)) {
                hunger++;
            } else if (generic.contains(line)) {
                chatter++;
            }
        }
        assertTrue(hunger > chatter * 2, "hunger " + hunger + " vs chatter " + chatter);
    }

    @Test
    void avoidsRepeatingRecentLines() {
        final TextContext c = new TextContext(DayPhase.WORK, 1, 50, false, false, false, false, false, true, false, Set.of(),
            Set.of(), null);
        final SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 200; i++) {
            final String line = cfg.festivalTexts.pickLine(c, random, List.of("Wiwat!")).orElseThrow();
            assertNotEquals("Wiwat!", line);
        }
    }

    @Test
    void reactionsByStanding() {
        final TextContext base = ctx(DayPhase.WORK, 1, 50);
        assertTrue(possible(cfg.reactions, base.withStanding("wladca")).contains("Witaj, panie!"));
        assertTrue(possible(cfg.reactions, base.withStanding("rodak")).contains("Dzień dobry, sąsiedzie!"));
        assertTrue(possible(cfg.reactions, base.withStanding("obcy")).contains("Czego tu szukasz?"));
        assertFalse(possible(cfg.reactions, base.withStanding("obcy")).contains("Witaj, panie!"));
        assertTrue(possible(cfg.alarm, base.withStanding("wrog")).contains("Straż! Wróg!"));
        assertTrue(possible(cfg.reactions, base).isEmpty(), "reactions need a standing");
        assertTrue(possible(cfg.protest, ctx(DayPhase.NOON, 1, 10)).contains("Chleba!"));
        assertTrue(possible(cfg.protest, ctx(DayPhase.NOON, 1, 10)).contains("Precz z podatkami!"));
    }

    @Test
    void cityJobsByProfessionBuildingAndEra() {
        final JobCatalog jobs = cfg.jobs;
        final Set<String> withBakery = Set.of("piekarnia", "pole");
        int bakers = 0;
        int farmers = 0;
        for (int i = 0; i < 1000; i++) {
            final JobCatalog.Job j = jobs.resolve("minecraft:farmer", UUID.randomUUID(), 1, withBakery::contains);
            if (j.id().equals("piekarz")) {
                bakers++;
            } else if (j.id().equals("rolnik")) {
                farmers++;
            }
        }
        assertEquals(1000, bakers + farmers);
        assertTrue(bakers > 250 && bakers < 450, "≈35 % bakers: " + bakers);
        // no bakery → no bakers
        final UUID id = UUID.randomUUID();
        assertEquals("rolnik", jobs.resolve("farmer", id, 1, t -> false).id());
        assertEquals("rolnik", jobs.resolve("farmer", id, 1, t -> false).title(1));
        assertEquals("robotnik rolny", jobs.resolve("farmer", id, 3, t -> false).title(3));
        // toolsmith: smith in the middle ages, worker (with a factory) in the industrial era
        final JobCatalog.Job smith = jobs.resolve("toolsmith", id, 1, Set.of("fabryka", "kuznia")::contains);
        assertEquals("kowal", smith.id());
        assertEquals("kowal", smith.title(1));
        assertEquals("mechanik", smith.title(4));
        assertEquals(List.of("kuznia"), smith.buildings());
        boolean worker = false;
        for (int i = 0; i < 50 && !worker; i++) {
            worker = jobs.resolve("toolsmith", UUID.randomUUID(), 3, Set.of("fabryka")::contains).id().equals("robotnik");
        }
        assertTrue(worker);
        assertEquals("kaplan", jobs.resolve("cleric", id, 1, t -> false).id());
        assertEquals("ksiądz", jobs.resolve("cleric", id, 4, t -> false).title(4));
        assertEquals("nauczyciel", jobs.resolve("librarian", id, 3, t -> false).title(3));
        assertEquals("skryba", jobs.resolve("librarian", id, 1, t -> false).title(1));
        assertEquals("mieszczanin", jobs.resolve("minecraft:unknown_mod_job", id, 1, t -> false).id());
        // miners and librarians work where the brief says
        assertTrue(jobs.resolve("mason", id, 1, t -> false).buildings().contains("kopalnia"));
        assertTrue(jobs.resolve("librarian", id, 1, t -> false).buildings().contains("szkola"));
    }

    @Test
    void scheduleAndPlacesFromConfig() {
        assertEquals(2000, cfg.schedule.workStart());
        assertEquals(6, cfg.schedule.sundayIndex());
        assertTrue(cfg.places.tavern().contains("karczma"));
        assertTrue(cfg.places.church().contains("kosciol"));
        assertTrue(cfg.greatBuildings.contains("palac"));
        assertTrue(cfg.greatBuildings.contains("katedra"));
        assertEquals(10, cfg.event("zwyciestwo").points());
        assertEquals(20, cfg.unrest.protestBelow());
        assertEquals(24000, cfg.unrest.protestAfter());
        assertEquals(0.6, cfg.mood.multiplierMin(), 1e-9);
        assertEquals(1.25, cfg.mood.multiplierMax(), 1e-9);
        assertTrue(cfg.concernTemplates.get("brak_lozek").contains("<brak>"));
        assertEquals("Brakuje łóżek (<brak>)", cfg.concernTemplates.get("brak_lozek"));
    }

    @Test
    void brokenRulesAreReported() {
        final YamlConfiguration bad = new YamlConfiguration();
        bad.set("teksty.dymki", List.of(java.util.Map.of("pory", List.of("jutro"), "teksty", List.of("x")),
            java.util.Map.of("epoki", List.of(1))));
        final List<String> problems = new ArrayList<>();
        final ZycieConfig c = new ZycieConfig(bad, problems);
        assertEquals(0, c.ambient.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("nieznana pora")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("brak tekstów")));
    }
}
