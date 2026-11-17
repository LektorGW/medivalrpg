// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.logic.BarracksRules.Barracks;
import pl.medivalrpg.armia.logic.BarracksRules.Limit;
import pl.medivalrpg.armia.logic.BarracksRules.Settings;

/** Barracks: limit per bunk, working / out of order, muster point, garrison healing, config and messages. */
class BarracksRulesTest {

    private static final Settings D = Settings.defaults();

    private static Barracks b(final String type, final String function, final int beds, final int x, final int z) {
        return BarracksRules.classify(type, function, beds, "world", x, 64, z, "koszary", D).orElseThrow();
    }

    private static YamlConfiguration config() throws IOException {
        try (var in = BarracksRulesTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void recognisesEveryKindOfBarracksAndNothingElse() {
        assertTrue(BarracksRules.isBarracks("koszary", "koszary", "koszary"));
        assertTrue(BarracksRules.isBarracks("koszary2", "koszary • uszkodzony 12%", "koszary"));
        assertTrue(BarracksRules.isBarracks("wlasny_koszary", "koszary • zgłoszony przez graczy", "koszary"));
        assertTrue(BarracksRules.isBarracks("wlasny_koszary", "koszary • nieczynny — w naprawie u graczy", "koszary"));
        // older Ludnosc without a function text: the type id decides
        assertTrue(BarracksRules.isBarracks("koszary2", null, "koszary"));
        assertTrue(BarracksRules.isBarracks("WLASNY_KOSZARY", "", null));
        assertFalse(BarracksRules.isBarracks("chata", "mieszkanie", "koszary"));
        assertFalse(BarracksRules.isBarracks("wlasny_mieszkanie", "mieszkanie • zgłoszony przez graczy", "koszary"));
        assertFalse(BarracksRules.isBarracks("kuznia", "kuźnia", "koszary"));
        assertFalse(BarracksRules.isBarracks(null, null, "koszary"));
        assertTrue(BarracksRules.classify("chata", "mieszkanie", 2, "world", 0, 64, 0, "koszary", D).isEmpty());
        assertTrue(BarracksRules.classify("koszary", "koszary", 6, null, 0, 64, 0, "koszary", D).isEmpty(), "bez świata");
    }

    @Test
    void outOfOrderBarracksAreSelfBuiltWithoutKeyBlocksOrBadlyDamaged() {
        assertTrue(BarracksRules.working("koszary", 50));
        assertTrue(BarracksRules.working("koszary • zgłoszony przez graczy", 50));
        assertTrue(BarracksRules.working("koszary • w naprawie", 50));
        assertTrue(BarracksRules.working("koszary • do rozbiórki (plan v2)", 50));
        assertFalse(BarracksRules.working("koszary • nieczynny — w naprawie u graczy", 50));
        assertTrue(BarracksRules.working("koszary • uszkodzony 50%", 50));
        assertFalse(BarracksRules.working("koszary • uszkodzony 51%", 50));
        assertFalse(BarracksRules.working("koszary • uszkodzony 100%", 50));
        assertTrue(BarracksRules.working("koszary • uszkodzony 100%", 100), "100 = uszkodzenia bez znaczenia");
        assertFalse(BarracksRules.working("koszary • uszkodzony 1%", 0));
        assertTrue(BarracksRules.working(null, 50));
    }

    @Test
    void everyBunkInAWorkingBarracksAddsOneOnTopOfTheLandLimit() {
        // owner's example: limit 24 = 12 kraj + 12 prycze w koszarach
        final Limit l = BarracksRules.limit(12, D, List.of(b("koszary", "koszary", 6, 0, 0), b("koszary", "koszary", 6, 40, 0)));
        assertEquals(new Limit(12, 12, 12, 2, 0, false), l);
        assertEquals(24, l.total());
        // production 11.11: Watykan 60 + 8, Landsbergia 35 + 12, Vadstena 23 + 6
        assertEquals(68, BarracksRules.limit(60, D, List.of(b("koszary", "koszary", 8, 0, 0))).total());
        assertEquals(47, BarracksRules.limit(35, D, List.of(b("koszary", "koszary", 6, 0, 0), b("koszary", "koszary", 6, 9, 9))).total());
        assertEquals(29, BarracksRules.limit(23, D, List.of(b("koszary", "koszary", 6, 0, 0))).total());
        // no barracks: exactly the old limit — no existing army becomes illegal
        assertEquals(new Limit(35, 0, 0, 0, 0, false), BarracksRules.limit(35, D, List.of()));
        // koszary2 = 12 bunks
        assertEquals(12, BarracksRules.limit(5, D, List.of(b("koszary2", "koszary", 12, 0, 0))).bonus());
    }

    @Test
    void destroyedOrIdleBarracksStopCountingButNeverLowerTheLandLimit() {
        final List<Barracks> list = List.of(b("koszary", "koszary", 6, 0, 0),
            b("wlasny_koszary", "koszary • nieczynny — w naprawie u graczy", 10, 30, 0),
            b("koszary2", "koszary • uszkodzony 80%", 12, 60, 0));
        final Limit l = BarracksRules.limit(20, D, list);
        assertEquals(new Limit(20, 6, 6, 1, 2, false), l);
        assertEquals(26, l.total());
        // all gone: back to the land limit, never below it (soldiers above it are kept, see ArmyLimits.allowed)
        final Limit none = BarracksRules.limit(20, D, List.of(b("koszary", "koszary • uszkodzony 90%", 6, 0, 0)));
        assertEquals(20, none.total());
        assertEquals(1, none.idle());
        assertEquals(0, ArmyLimits.allowed(5, 26, none.total(), 500, 1000), "ponad limitem: rekrutacja stoi");
        assertEquals(0, ArmyLimits.allowed(5, 20, none.total(), 500, 1000));
        assertEquals(3, ArmyLimits.allowed(5, 17, none.total(), 500, 1000));
    }

    @Test
    void bonusIsCappedPerBuildingPerCountryAndByTheGlobalCap() {
        // a self-built hall full of beds counts as 12 bunks (koszary2)
        assertEquals(12, b("wlasny_koszary", "koszary • zgłoszony przez graczy", 200, 0, 0).beds());
        assertEquals(0, b("koszary", "koszary", -3, 0, 0).beds());
        // Litwa / Łęczycka in production: 3 × koszary + 2 × koszary2 = 42 bunks, all counted
        final List<Barracks> five = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            five.add(b("koszary", "koszary", 6, i * 20, 0));
        }
        five.add(b("koszary2", "koszary", 12, 80, 0));
        five.add(b("koszary2", "koszary", 12, 100, 0));
        final Limit litwa = BarracksRules.limit(60, D, five);
        assertEquals(42, litwa.bonus());
        assertEquals(102, litwa.total());
        assertFalse(litwa.atMax());
        // per-country ceiling of the bonus
        final List<Barracks> many = new ArrayList<>(five);
        many.add(b("wlasny_koszary", "koszary • zgłoszony przez graczy", 12, 0, 50));
        many.add(b("wlasny_koszary", "koszary • zgłoszony przez graczy", 12, 0, 70));
        final Limit max = BarracksRules.limit(60, D, many);
        assertEquals(60, max.bonus());
        assertEquals(66, max.beds());
        assertTrue(max.atMax());
        assertEquals(120, max.total());
        // never above the global cap of the server
        final Limit capped = BarracksRules.capped(max, 100);
        assertEquals(100, capped.total());
        assertEquals(60, capped.base());
        assertEquals(40, capped.bonus());
        assertEquals(1000, BarracksRules.capped(new Limit(60, 60, 60, 5, 0, true), 1000).total() + 880);
        assertEquals(0, BarracksRules.capped(max, 0).total());
        // 2 per bunk, bonus max 10
        final Settings two = new Settings(2, 10, 12, 50, true, 40, 24, 4, 10, true);
        assertEquals(10, BarracksRules.limit(5, two, List.of(b("koszary", "koszary", 6, 0, 0))).bonus());
        assertEquals(8, BarracksRules.limit(5, two, List.of(BarracksRules.classify("koszary", "koszary", 4, "world", 0, 64, 0, "koszary", two).orElseThrow())).bonus());
    }

    @Test
    void zeroPerBedTurnsTheLimitOffAndKeepsTheOldMessages() {
        final Settings off = new Settings(0, 60, 12, 50, true, 40, 24, 4, 10, true);
        assertFalse(off.limitEnabled());
        final Limit l = BarracksRules.limit(30, off, List.of(b("koszary", "koszary", 6, 0, 0)));
        assertEquals(30, l.total());
        assertEquals(0, l.bonus());
        assertEquals(1, l.working());
        assertEquals("recruit-limit", BarracksRules.limitKey(off, l, true, 500, 1000));
        // without Ludnosc nothing is known about barracks: old text
        assertEquals("recruit-limit", BarracksRules.limitKey(D, BarracksRules.limit(30, D, List.of()), false, 500, 1000));
        assertEquals("recruit-limit-barracks", BarracksRules.limitKey(D, BarracksRules.limit(30, D, List.of()), true, 500, 1000));
        assertEquals("recruit-limit-barracks-max", BarracksRules.limitKey(D, new Limit(60, 60, 60, 5, 0, true), true, 500, 1000));
        assertEquals("recruit-limit-global", BarracksRules.limitKey(D, BarracksRules.limit(30, D, List.of()), true, 1000, 1000));
    }

    @Test
    void npcCeilingStaysMaxZolnierzeWhateverTheBarracksGive() {
        final AiTuning t = AiTuning.defaults();
        // production: Zakon Krzyżacki max-zolnierze 40, land limit 58, 18 bunks → limit 76, desired still ≤ 40
        final Personality zakon = new Personality(90, 50, 50, 50, 300, 40, true);
        final int before = AiDecisions.desiredSoldiers(t, zakon, 106, 58);
        final int after = AiDecisions.desiredSoldiers(t, zakon, 106, BarracksRules.limit(58, D, List.of(
            b("koszary", "koszary", 6, 0, 0), b("koszary", "koszary", 6, 20, 0), b("koszary", "koszary", 6, 40, 0))).total());
        assertTrue(after <= 40, "max-zolnierze is the ceiling: " + after);
        assertEquals(before, after, "a country below its own ceiling does not change");
        // Litwa: max-zolnierze 80 was cut to 60 by the land limit; with 42 bunks the ceiling is its own 80, not 102
        final Personality litwa = new Personality(100, 100, 50, 50, 400, 80, true);
        final int litwaAfter = AiDecisions.desiredSoldiers(t, litwa, 346, 102);
        assertTrue(litwaAfter <= 80 && litwaAfter >= AiDecisions.desiredSoldiers(t, litwa, 346, 60));
    }

    @Test
    void recruitsMusterAtTheNearestWorkingBarracksOfTheSameWorld() {
        final List<Barracks> list = List.of(b("koszary", "koszary", 6, 100, 0),
            b("koszary2", "koszary", 12, -30, 10),
            b("wlasny_koszary", "koszary • nieczynny — w naprawie u graczy", 8, 2, 2),
            BarracksRules.classify("koszary", "koszary", 6, "world_nether", 1, 64, 1, "koszary", D).orElseThrow());
        assertEquals("koszary2", BarracksRules.nearest(list, "world", 0, 0).orElseThrow().type(), "nieczynne i inny świat pominięte");
        assertEquals(100, BarracksRules.nearest(list, "world", 90, 0).orElseThrow().x());
        assertEquals("world_nether", BarracksRules.nearest(list, "world_nether", 500, 500).orElseThrow().world());
        assertTrue(BarracksRules.nearest(list, "world_the_end", 0, 0).isEmpty());
        assertTrue(BarracksRules.nearest(List.of(), "world", 0, 0).isEmpty());
        // near barracks: recruits walk to the recruiter; far: they stay as the garrison
        assertTrue(BarracksRules.recruitsFollow(D, 39.0 * 39.0));
        assertTrue(BarracksRules.recruitsFollow(D, 40.0 * 40.0));
        assertFalse(BarracksRules.recruitsFollow(D, 41.0 * 41.0));
        assertTrue(BarracksRules.recruitsFollow(new Settings(1, 60, 12, 50, true, -1, 24, 4, 10, true), 1e9), "-1 = zawsze do rekrutującego");
    }

    @Test
    void garrisonHealingOnlyNearOwnWorkingBarracks() {
        final List<Barracks> list = List.of(b("koszary", "koszary", 6, 0, 0),
            b("koszary", "koszary • nieczynny — w naprawie u graczy", 6, 200, 0));
        assertTrue(BarracksRules.garrisoned(list, "world", 0.5, 64, 0.5, 24));
        assertTrue(BarracksRules.garrisoned(list, "world", 24.4, 70, 0.5, 24));
        assertFalse(BarracksRules.garrisoned(list, "world", 25.5, 64, 0.5, 24));
        assertFalse(BarracksRules.garrisoned(list, "world", 0.5, 120, 0.5, 24), "za wysoko / za nisko");
        assertFalse(BarracksRules.garrisoned(list, "world", 200.5, 64, 0.5, 24), "nieczynne koszary nie leczą");
        assertFalse(BarracksRules.garrisoned(list, "world_nether", 0.5, 64, 0.5, 24));
        assertFalse(BarracksRules.garrisoned(list, "world", 0.5, 64, 0.5, 0), "promień 0 = wyłączone");
        assertFalse(BarracksRules.garrisoned(List.of(), "world", 0, 64, 0, 24));
        // 4 HP / min in passes of 10 s
        assertEquals(4.0 / 6.0, D.healPerPass(), 1e-9);
        assertTrue(D.healEnabled());
        assertFalse(new Settings(1, 60, 12, 50, true, 40, 24, 0, 10, true).healEnabled());
        assertFalse(new Settings(1, 60, 12, 50, true, 40, 0, 4, 10, true).healEnabled());
    }

    @Test
    void settingsAreClampedAndMissingConfigMeansDefaults() throws IOException {
        // production config has no `barracks` section at all
        assertEquals(D, ArmiaConfig.barracks(null));
        assertEquals(D, ArmiaConfig.barracks(new YamlConfiguration().createSection("barracks")));
        assertEquals(D, ArmiaConfig.barracks(config().getConfigurationSection("barracks")), "config.yml w jarze = domyślne z kodu");
        assertEquals(new Settings(1, 60, 12, 50, true, 40, 24, 4.0, 10, true), D);
        final YamlConfiguration bad = new YamlConfiguration();
        bad.set("b.limit-per-bed", -5);
        bad.set("b.limit-bonus-max", 999_999);
        bad.set("b.max-beds-per-building", 0);
        bad.set("b.max-damage-percent", 500);
        bad.set("b.heal-radius", 10_000);
        bad.set("b.heal-per-minute", -1);
        bad.set("b.heal-interval-seconds", 0);
        final Settings s = ArmiaConfig.barracks(bad.getConfigurationSection("b"));
        assertEquals(0, s.limitPerBed());
        assertEquals(1000, s.limitBonusMax());
        assertEquals(1, s.maxBedsPerBuilding());
        assertEquals(100, s.maxDamagePercent());
        assertEquals(128, s.healRadius());
        assertEquals(0, s.healPerMinute());
        assertEquals(2, s.healIntervalSeconds());
        assertFalse(s.limitEnabled());
        // `recruit.barracks.required` stays off: barracks are a bonus, not a gate (separate decision of the owner)
        assertFalse(config().getBoolean("recruit.barracks.required"));
        assertEquals(1000, config().getInt("limits.global-max"));
    }

    @Test
    void newMessagesExistInTheJarAndReadWell() throws IOException {
        final YamlConfiguration cfg = config();
        for (final String key : List.of("recruit-limit-barracks", "recruit-limit-barracks-max", "recruit-limit-global",
            "recruited-at-barracks", "recruited-from-barracks", "info-limit", "info-limit-none", "info-over-limit",
            "info-barracks-idle", "info-barracks-garrison", "info-barracks-muster")) {
            assertTrue(cfg.isString("messages." + key), "messages." + key);
        }
        // keys the production config already overrides are left alone (their text there would win anyway)
        assertTrue(cfg.getString("messages.recruit-limit").contains("<cap>"));
        assertTrue(cfg.getString("messages.info-count").contains("<count>/<cap>"));
        final var tags = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
            Placeholder.parsed("prefix", ""), Placeholder.unparsed("count", "24"), Placeholder.unparsed("cap", "24"),
            Placeholder.unparsed("base", "12"), Placeholder.unparsed("bonus", "12"), Placeholder.unparsed("beds", "12"),
            Placeholder.unparsed("barracks", "2"), Placeholder.unparsed("idle", "1"), Placeholder.unparsed("perbed", "1"),
            Placeholder.unparsed("building", "koszary"), Placeholder.unparsed("radius", "24"), Placeholder.unparsed("heal", "4")};
        final String info = plain(cfg.getString("messages.info-limit"), tags);
        assertEquals("Limit wojska: 24 = 12 (kraj) + 12 (prycze w koszarach) · czynne koszary: 2, prycze: 12", info);
        final String limit = plain(cfg.getString("messages.recruit-limit-barracks"), tags);
        assertTrue(limit.startsWith("Limit wojska osiągnięty: 24/24. Limit 24 = 12 (kraj) + 12 (prycze w koszarach)."), limit);
        assertTrue(limit.contains("Zbudujcie koszary") && limit.contains("/ludnosc buduj koszary")
            && limit.contains("/ludnosc budynek zglos koszary"), limit);
        assertTrue(plain(cfg.getString("messages.info-limit-none"), tags).contains("/ludnosc buduj koszary"));
        assertTrue(plain(cfg.getString("messages.info-barracks-garrison"), tags).contains("+4 HP/min"));
        assertTrue(plain(cfg.getString("messages.info-over-limit"), tags).contains("Nikt nie zostanie zwolniony"));
    }

    private static String plain(final String mini, final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(mini, tags));
    }
}
