// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SeasonTest {

    @Test
    void offlineTimeDoesNotExtendBlightCooldown() {
        final var rules = SeasonRules.defaults();
        final long ended = 1_800_000_000_000L;
        final long resumed = ended + 12 * SeasonClock.DAY;
        assertEquals(ended + 10 * SeasonClock.DAY, rules.cooldownUntil(ended));
        assertTrue(rules.cooldownUntil(ended) < resumed);
    }

    @Test
    void adminCanSelectEveryDayAndSeasonWithoutChangingCycleLength() {
        final var clock = new SeasonClock(1_800_000_000_000L, 7 * SeasonClock.DAY, Season.JESIEN);
        final long now = 1_810_000_000_000L;
        for (final Season season : Season.values()) {
            for (int day = 1; day <= 7; day++) {
                final var moved = clock.startingAt(season, day, now);
                assertEquals(season, moved.at(now).season());
                assertEquals(day, moved.at(now).day());
                assertEquals(season.next(), moved.at(now + (8 - day) * SeasonClock.DAY).season());
            }
        }
    }

    private static final long DAY = SeasonClock.DAY;
    private static final long T0 = 1_800_000_000_000L;

    @Test
    void parsesPolishAndEnglishNames() {
        assertEquals(Season.JESIEN, Season.parse("jesień"));
        assertEquals(Season.JESIEN, Season.parse("JESIEN"));
        assertEquals(Season.ZIMA, Season.parse(" winter "));
        assertEquals(Season.WIOSNA, Season.parse("Wiosna"));
        assertNull(Season.parse("przednówek"));
        assertNull(Season.parse(null));
        assertEquals(Season.WIOSNA, Season.ZIMA.next());
        assertEquals(Season.LATO, Season.ZIMA.plus(-6));
    }

    @Test
    void cycleFollowsRealTime() {
        final SeasonClock c = new SeasonClock(T0, 7 * DAY, Season.JESIEN);
        assertEquals(Season.JESIEN, c.at(T0).season());
        assertEquals(1, c.at(T0).day());
        assertEquals(7, c.at(T0).days());
        assertEquals(3, c.at(T0 + 2 * DAY + 5).day());
        assertEquals(7, c.at(T0 + 7 * DAY - 1).day());
        assertEquals(Season.JESIEN, c.at(T0 + 7 * DAY - 1).season());
        assertEquals(Season.ZIMA, c.at(T0 + 7 * DAY).season());
        assertEquals(Season.WIOSNA, c.at(T0 + 14 * DAY).season());
        assertEquals(Season.LATO, c.at(T0 + 21 * DAY).season());
        assertEquals(Season.JESIEN, c.at(T0 + 28 * DAY).season(), "a full year");
        assertEquals(Season.LATO, c.at(T0 - 1).season(), "before the anchor: the previous season");
        assertEquals(T0 + 7 * DAY, c.at(T0 + 3 * DAY).endsAt());
        assertEquals(1, c.at(T0 + 7 * DAY).serial());
    }

    @Test
    void serialChangesExactlyWithTheSeason() {
        final SeasonClock c = new SeasonClock(T0, 7 * DAY, Season.WIOSNA);
        long serial = c.at(T0).serial();
        int changes = 0;
        for (long t = T0; t < T0 + 28 * DAY; t += DAY / 4) {
            final long s = c.at(t).serial();
            if (s != serial) {
                changes++;
                serial = s;
            }
        }
        assertEquals(3, changes);
    }

    @Test
    void adminSetsSeasonStartingNow() {
        final SeasonClock c = new SeasonClock(T0, 7 * DAY, Season.JESIEN);
        final long now = T0 + 3 * DAY + 12345;
        final SeasonClock moved = c.startingAt(Season.ZIMA, 1, now);
        assertEquals(Season.ZIMA, moved.at(now).season());
        assertEquals(1, moved.at(now).day());
        assertEquals(now, moved.at(now).startedAt());
        final SeasonClock day5 = c.startingAt(Season.LATO, 5, now);
        assertEquals(Season.LATO, day5.at(now).season());
        assertEquals(5, day5.at(now).day());
        assertEquals(Season.JESIEN, day5.at(now + 3 * DAY).season(), "summer day 5 of 7 → autumn in 3 days");
    }

    @Test
    void shortSeasonsForTesting() {
        final SeasonClock c = new SeasonClock(T0, SeasonClock.lengthOfDays(0.5), Season.WIOSNA);
        assertEquals(1, c.at(T0).days());
        assertEquals(Season.LATO, c.at(T0 + DAY / 2).season());
        assertEquals(60_000L, new SeasonClock(T0, 1, Season.WIOSNA).length(), "at least a minute");
    }

    @Test
    void foodFactorsAndPoorHarvests() {
        final SeasonRules r = SeasonRules.defaults();
        assertTrue(r.foodMultiplier(Season.ZIMA) < 0.5, "winter: very low harvest");
        assertTrue(r.foodMultiplier(Season.JESIEN) > 1.0, "autumn: harvest bonus");
        assertEquals(1.4 * 0.4, r.productionFactor(Season.JESIEN, true, false), 1e-9);
        assertEquals(0.25, r.productionFactor(Season.ZIMA, false, true), 1e-9, "NPC countries too by default");
        final SeasonRules noNpc = new SeasonRules(true, 7, Season.JESIEN, r.food(), r.cropGrowth(), true, false, r.blightChance(),
            0.4, 2, 4, 10, 0.1);
        assertEquals(1.0, noNpc.productionFactor(Season.ZIMA, true, true), 1e-9);
        final SeasonRules off = new SeasonRules(false, 7, Season.JESIEN, r.food(), r.cropGrowth(), true, true, r.blightChance(),
            0.4, 2, 4, 10, 0.1);
        assertEquals(1.0, off.productionFactor(Season.ZIMA, true, false), 1e-9);
        assertEquals(1.0, off.growthChance(Season.ZIMA), 1e-9);
        assertFalse(off.blightStarts(Season.LATO, 0.0, false));

        assertTrue(r.blightStarts(Season.LATO, 0.01, false));
        assertFalse(r.blightStarts(Season.LATO, 0.01, true), "cooldown");
        assertFalse(r.blightStarts(Season.ZIMA, 0.0, false), "no poor harvest in winter (nothing grows)");
        assertFalse(r.blightStarts(Season.LATO, 0.5, false));
        assertEquals(2, r.blightDays(0.0));
        assertEquals(4, r.blightDays(0.999));
        assertEquals(4, r.blightDays(1.0));
        assertTrue(r.growthChance(Season.ZIMA) < 1.0);
    }

    @Test
    void configValuesAreClamped() {
        final SeasonRules r = new SeasonRules(true, -3, null, Map.of(Season.LATO, 99.0), Map.of(Season.ZIMA, -1.0), true, true,
            Map.of(Season.LATO, 7.0), 3, 5, 2, -1, 4);
        assertEquals(5.0, r.foodMultiplier(Season.LATO), 1e-9);
        assertEquals(1.0, r.foodMultiplier(Season.WIOSNA), 1e-9, "missing → 1");
        assertEquals(0.0, r.growthChance(Season.ZIMA), 1e-9);
        assertEquals(1.0, r.blightChance().get(Season.LATO), 1e-9);
        assertEquals(1.0, r.blightFactor(), 1e-9);
        assertEquals(5, r.blightMaxDays(), "max ≥ min");
        assertEquals(Season.JESIEN, r.first());
        assertTrue(r.lengthMillis() >= 60_000L);
    }

    @Test
    void starvationIsGradual() {
        final SeasonRules r = SeasonRules.defaults();
        assertEquals(10, r.capDepartures(50, 100), "at most 10% a day");
        assertEquals(3, r.capDepartures(3, 100));
        assertEquals(1, r.capDepartures(4, 5), "small villages: one at a time");
        assertEquals(0, r.capDepartures(0, 5));
        assertEquals(0, r.capDepartures(3, 0));
        // a whole winter without food: the population shrinks, it is not wiped out in days
        int population = 40;
        for (int day = 0; day < 7; day++) {
            population -= r.capDepartures(population, population);
        }
        assertTrue(population >= 18, "after a week of famine " + population + " remain");
    }

    @Test
    void economyWithSeasonFactor() {
        final EconomyRules rules = new EconomyRules(4, 10, 10, 5, false, 3, Map.of(), 0, 0.3, 0.5, 2, 0.2, 3, 0.25, 5, 15);
        final Economy.DayInput summer = new Economy.DayInput(Map.of("farmer", 4, "none", 6), 0, 10, 0, 1000, false, 0, 0, 2, 0);
        final long base = Economy.production(summer, rules);
        assertEquals(70, base);
        // EconomyService adds (factor - 1) × production as a bonus: winter 0.25 → 70 - 52 = 18 (rounded)
        final long extra = Math.round(base * (SeasonRules.defaults().foodMultiplier(Season.ZIMA) - 1));
        final Economy.DayInput winter = new Economy.DayInput(Map.of("farmer", 4, "none", 6), 0, 10, 0, 1000, false, 0, 0, 2, extra);
        assertEquals(18, Economy.production(winter, rules));
        assertEquals(1.0, Economy.day(summer, rules).fedRatio(), 1e-9, "summer: 10 people fed by 4 farmers and 2 fields");
        assertTrue(Economy.day(winter, rules).fedRatio() < 1.0, "winter without stock: hunger");
        final Economy.DayInput stocked = new Economy.DayInput(Map.of("farmer", 4, "none", 6), 0, 10, 500, 1000, false, 0, 0, 2, extra);
        assertEquals(1.0, Economy.day(stocked, rules).fedRatio(), 1e-9, "winter with an autumn stock: fed");
    }
}
