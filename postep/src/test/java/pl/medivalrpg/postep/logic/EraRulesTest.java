// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EraRulesTest {

    private static final long DAY = EraRules.DAY;
    private static final long NOW = 1_800_000_000_000L;

    private static EraRules.Check check(final Set<String> owned, final double daysInEra, final double points,
                                        final int pop, final int minPop, final int tier, final int minTier, final boolean skip) {
        final TechTree tree = TestData.small();
        return EraRules.evaluate(new EraRules.Input(1, 4, NOW - (long) (daysInEra * DAY), NOW, 21, owned,
                tree.requiredToLeave(1), points, pop, minPop, tier, tier < 0 ? "?" : "poziom " + tier, minTier,
                "poziom " + minTier, skip),
            EraRules.Weights.DEFAULT, EraRules.Texts.DEFAULT);
    }

    @Test
    void readyWhenEverythingIsMet() {
        final EraRules.Check c = check(Set.of("a", "b", "c"), 21, 0, 30, 25, 2, 2, false);
        assertTrue(c.ready());
        assertEquals(1.0, c.progress());
        assertTrue(c.missing().isEmpty());
    }

    @Test
    void minimumTimeBlocks() {
        final EraRules.Check c = check(Set.of("a", "b", "c"), 20, 0, 30, 25, 2, 2, false);
        assertFalse(c.ready());
        assertTrue(c.timeLeftMillis() > 0 && c.timeLeftMillis() <= DAY);
        assertTrue(c.missing().get(0).startsWith("czas w epoce"));
        assertTrue(c.progress() < 1.0);
    }

    @Test
    void missingRequiredDiscoveryBlocks() {
        final EraRules.Check c = check(Set.of("a", "b", "x"), 40, 0, 30, 25, 2, 2, false);
        assertFalse(c.ready());
        assertEquals(List.of("c"), c.missingDiscoveries());
        assertEquals(List.of("odkrycie: C"), c.missing());
        // owned 200 of 400 required cost
        assertEquals(0.5, c.discoveryPart(), 1e-9);
    }

    @Test
    void spendablePointsCountTowardTheDiscoveryPart() {
        final EraRules.Check c = check(Set.of("a"), 0, 150, 30, 25, 2, 2, false);
        assertEquals((100 + 150) / 400.0, c.discoveryPart(), 1e-9);
        final EraRules.Check rich = check(Set.of("a"), 0, 10_000, 30, 25, 2, 2, false);
        assertEquals(1.0, rich.discoveryPart(), 1e-9);
        assertFalse(rich.ready(), "points alone do not unlock anything");
    }

    @Test
    void populationAndTierThresholds() {
        final EraRules.Check c = check(Set.of("a", "b", "c"), 30, 0, 10, 25, 1, 2, false);
        assertFalse(c.ready());
        assertEquals(List.of("ludność ≥ 25 (jest 10)", "poziom osady ≥ poziom 2 (jest poziom 1)"), c.missing());
        assertEquals((10 / 25.0 + 1 / 2.0) / 2, c.thresholdPart(), 1e-9);
    }

    @Test
    void unknownThresholdsSkippedOrRequired() {
        assertTrue(check(Set.of("a", "b", "c"), 30, 0, -1, 25, -1, 2, true).ready());
        final EraRules.Check strict = check(Set.of("a", "b", "c"), 30, 0, -1, 25, -1, 2, false);
        assertFalse(strict.ready());
        assertEquals(2, strict.missing().size());
    }

    @Test
    void progressGrowsAndStaysBelowOneUntilReady() {
        final double early = check(Set.of(), 1, 0, 0, 25, 1, 2, false).progress();
        final double mid = check(Set.of("a", "b"), 10, 0, 15, 25, 1, 2, false).progress();
        final double late = check(Set.of("a", "b", "c"), 20.9, 0, 25, 25, 2, 2, false).progress();
        assertTrue(early < mid && mid < late, early + " < " + mid + " < " + late);
        assertTrue(late <= 0.99);
    }

    @Test
    void lastEraIsMax() {
        final EraRules.Check c = EraRules.evaluate(new EraRules.Input(4, 4, 0, NOW, 0, Set.of(), List.of(), 0, 0, 0, 0, "", 0, "", true),
            EraRules.Weights.DEFAULT, EraRules.Texts.DEFAULT);
        assertTrue(c.atMaxEra());
        assertFalse(c.ready());
        assertEquals(1.0, c.progress());
    }

    @Test
    void durationText() {
        assertEquals("3 dni 4 h", EraRules.duration(3 * DAY + 4 * 3_600_000L));
        assertEquals("1 dzień", EraRules.duration(DAY));
        assertEquals("2 h 5 min", EraRules.duration(2 * 3_600_000L + 5 * 60_000L));
        assertEquals("1 min", EraRules.duration(1_000));
    }
}
