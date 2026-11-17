// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;

/** Permissions, price (materials + treasury fee), daily limit and its log for country standards. */
class StandardRulesTest {

    static final LongFunction<String> MONEY = md -> md + " md";
    static final StandardRules.Cost BANNER = StandardRules.Settings.DEFAULT.banner();
    static final StandardRules.Cost ITEM = StandardRules.Settings.DEFAULT.item();

    static String refusal(final StandardRules.Cost cost, final Rank rank, final boolean npc, final int used,
                          final Map<String, Integer> have, final boolean economy, final long balance) {
        return StandardRules.refusal(StandardRules.Kind.BANNER, cost, true, rank, npc, Rank.ADVISOR, used,
            StandardRules.missing(cost.materials(), have), economy, balance, MONEY);
    }

    static final Map<String, Integer> ENOUGH = Map.of("RED_WOOL", 4, "WHITE_WOOL", 2, "STICK", 3);

    @Test
    void defaultsAreTheProposal() {
        assertEquals(100, BANNER.treasuryCost());
        assertEquals(Map.of("WOOL", 6, "STICK", 1), BANNER.materials());
        assertEquals(8, BANNER.dailyLimit());
        assertEquals(500, ITEM.treasuryCost());
        assertEquals(4, ITEM.dailyLimit());
        assertEquals(ZoneId.of("Europe/Warsaw"), StandardRules.Settings.DEFAULT.zone());
    }

    @Test
    void permissions() {
        assertNotNull(refusal(BANNER, null, false, 0, ENOUGH, true, 10_000), "outsider");
        assertTrue(refusal(BANNER, Rank.CITIZEN, false, 0, ENOUGH, true, 10_000).contains("Władca albo Doradca"), "citizen");
        assertNull(refusal(BANNER, Rank.ADVISOR, false, 0, ENOUGH, true, 10_000), "advisor");
        assertNull(refusal(BANNER, Rank.RULER, false, 0, ENOUGH, true, 10_000), "ruler");
        assertTrue(refusal(BANNER, Rank.RULER, true, 0, ENOUGH, true, 10_000).contains("NPC"), "NPC countries only by admins");
        // upload-rank RULER: advisors are refused
        final String ruler = StandardRules.refusal(StandardRules.Kind.ITEM, ITEM, true, Rank.ADVISOR, false, Rank.RULER, 0,
            Map.of(), true, 10_000, MONEY);
        assertTrue(ruler.contains("Władca."), ruler);
        assertTrue(StandardRules.refusal(StandardRules.Kind.ITEM, ITEM, false, Rank.RULER, false, Rank.ADVISOR, 0, Map.of(), true, 10_000, MONEY)
            .contains("wyłączone"));
    }

    @Test
    void dailyLimit() {
        assertNull(refusal(BANNER, Rank.RULER, false, 7, ENOUGH, true, 10_000));
        assertTrue(refusal(BANNER, Rank.RULER, false, 8, ENOUGH, true, 10_000).contains("Limit"));
        final StandardRules.Cost none = new StandardRules.Cost(true, 0, Map.of(), 0);
        assertTrue(refusal(none, Rank.RULER, false, 0, ENOUGH, true, 10_000).contains("Limit"));
    }

    @Test
    void feeComesFromTheTreasuryOnly() {
        // the fee is checked against the treasury, never created: not enough money -> refusal with both amounts
        final String poor = refusal(BANNER, Rank.RULER, false, 0, ENOUGH, true, 99);
        assertTrue(poor.contains("100 md") && poor.contains("99 md"), poor);
        assertNull(refusal(BANNER, Rank.RULER, false, 0, ENOUGH, true, 100));
        // without Skarbiec nothing is charged (and nothing can be created either)
        assertNull(refusal(BANNER, Rank.RULER, false, 0, ENOUGH, false, 0));
    }

    @Test
    void materials() {
        assertEquals(Map.of("WOOL", 6, "STICK", 1), StandardRules.materials(List.of("wool:4", "WOOL:2", "STICK:1", "bad", "X:0", ":5", "STONE:-1")));
        assertTrue(StandardRules.matches("WOOL", "LIGHT_BLUE_WOOL"));
        assertTrue(!StandardRules.matches("WOOL", "WOOL_CARPET") && !StandardRules.matches("STICK", "STICKY_PISTON"));
        // wool of mixed colours adds up
        assertEquals(Map.of(), StandardRules.missing(BANNER.materials(), ENOUGH));
        assertEquals(Map.of("WOOL", 1), StandardRules.missing(BANNER.materials(), Map.of("RED_WOOL", 5, "STICK", 1)));
        assertEquals(Map.of("WOOL", 6, "STICK", 1), StandardRules.missing(BANNER.materials(), Map.of("RED_CARPET", 64)));
        final String missing = refusal(BANNER, Rank.RULER, false, 0, Map.of("RED_WOOL", 5), true, 10_000);
        assertTrue(missing.contains("1× wełna") && missing.contains("1× patyk"), missing);
        assertEquals("6× wełna (dowolny kolor), 1× patyk", StandardRules.describe(BANNER.materials()));
    }

    @Test
    void settingsFromConfig() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
            standards:
              time-zone: Nie/Ma
              banner:
                treasury-cost: 250
                materials: ["WHITE_WOOL:6", "STICK:1"]
                daily-limit: 2
              item:
                enabled: false
              placed:
                max-per-chunk: 999
            """);
        final StandardRules.Settings s = StandardRules.Settings.from(y.getConfigurationSection("standards"));
        assertEquals(ZoneId.of("Europe/Warsaw"), s.zone());
        assertEquals(250, s.banner().treasuryCost());
        assertEquals(Map.of("WHITE_WOOL", 6, "STICK", 1), s.banner().materials());
        assertEquals(2, s.banner().dailyLimit());
        assertTrue(!s.item().enabled());
        assertEquals(500, s.item().treasuryCost());
        assertEquals(64, s.maxPerChunk());
        assertEquals(StandardRules.Settings.DEFAULT, StandardRules.Settings.from(null));
    }

    @Test
    void logCountsPerDayAndSurvivesARestart() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final StandardRules.Log log = new StandardRules.Log();
        log.add("2026-10-29", a, StandardRules.Kind.BANNER);
        log.add("2026-10-29", a, StandardRules.Kind.BANNER);
        log.add("2026-10-29", a, StandardRules.Kind.ITEM);
        log.add("2026-10-29", b, StandardRules.Kind.ITEM);
        final StandardRules.Log back = StandardRules.Log.fromJson(log.toJson());
        assertEquals(2, back.used("2026-10-29", a, StandardRules.Kind.BANNER));
        assertEquals(1, back.used("2026-10-29", a, StandardRules.Kind.ITEM));
        assertEquals(1, back.used("2026-10-29", b, StandardRules.Kind.ITEM));
        // next day starts from zero
        assertEquals(0, back.used("2026-10-30", a, StandardRules.Kind.BANNER));
        assertEquals(0, StandardRules.Log.fromJson("{zepsute").used("2026-10-29", a, StandardRules.Kind.BANNER));
        // the day follows Europe/Warsaw: 23:30 UTC on 29.10 is already 30.10 in Poland (UTC+1 after the DST change)
        assertEquals("2026-10-30", StandardRules.Log.day(java.time.Instant.parse("2026-10-29T23:30:00Z").toEpochMilli(), ZoneId.of("Europe/Warsaw")));
    }
}
