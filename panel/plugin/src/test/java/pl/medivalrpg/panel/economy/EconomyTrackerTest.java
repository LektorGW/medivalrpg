// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Production estimate from store growth, chart points and site waiting clocks. */
class EconomyTrackerTest {

    static final long M = EconomyTracker.MINUTE;
    final UUID c = UUID.randomUUID();

    private static Map<String, Map<String, Long>> stores(final Object... kv) {
        final java.util.LinkedHashMap<String, Map<String, Long>> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") final Map<String, Long> g = (Map<String, Long>) kv[i + 1];
            m.put((String) kv[i], g);
        }
        return m;
    }

    @Test
    void positiveGrowthOfKnownStoresIsScaledToADay() {
        final EconomyTracker t = new EconomyTracker();
        final long t0 = 1_000_000_000L;
        assertTrue(t.sample(c, t0, stores("kopalnia", Map.of("minecraft:cobblestone", 10L)), null));
        assertTrue(t.sample(c, t0 + 15 * M, stores("kopalnia", Map.of("minecraft:cobblestone", 40L)), null));
        assertTrue(t.sample(c, t0 + 30 * M, stores("kopalnia", Map.of("minecraft:cobblestone", 70L, "minecraft:coal", 2L)), null));
        final EconomyTracker.Estimate e = t.estimate(c, t0 + 30 * M, EconomyTracker.DAY);
        assertEquals(30 * M, e.coveredMs());
        assertEquals(60L, e.gained().get("minecraft:cobblestone"));
        assertEquals(60L * 48, e.perDay().get("minecraft:cobblestone"), "60 w 30 min → 2880 na dobę");
        assertEquals(2L * 48, e.perDay().get("minecraft:coal"));
        assertEquals("minecraft:cobblestone", e.perDay().keySet().iterator().next(), "od największego");
    }

    @Test
    void haulersMovingBetweenKnownStoresCancelOutAndUnloadedStoresAreIgnored() {
        final EconomyTracker t = new EconomyTracker();
        final long t0 = 5_000_000L;
        t.sample(c, t0, stores("tartak", Map.of("minecraft:oak_log", 64L), "magazyn", Map.of()), null);
        // 32 logs carried from the sawmill to the warehouse, nothing produced
        t.sample(c, t0 + 10 * M, stores("tartak", Map.of("minecraft:oak_log", 32L), "magazyn", Map.of("minecraft:oak_log", 32L)), null);
        // the warehouse chunk unloads, then comes back: its goods are not "produced"
        t.sample(c, t0 + 20 * M, stores("tartak", Map.of("minecraft:oak_log", 32L)), null);
        t.sample(c, t0 + 30 * M, stores("tartak", Map.of("minecraft:oak_log", 32L), "magazyn", Map.of("minecraft:oak_log", 500L)), null);
        final EconomyTracker.Estimate e = t.estimate(c, t0 + 30 * M, EconomyTracker.DAY);
        assertTrue(e.gained().isEmpty(), "nic nie wyprodukowano: " + e.gained());
    }

    @Test
    void longGapsAreNotCountedAndShortCoverageGivesNoDailyFigure() {
        final EconomyTracker t = new EconomyTracker();
        final long t0 = 9_000_000L;
        t.sample(c, t0, stores("pole", Map.of("minecraft:wheat", 0L)), null);
        t.sample(c, t0 + 3 * EconomyTracker.HOUR, stores("pole", Map.of("minecraft:wheat", 900L)), null); // gap > 30 min
        t.sample(c, t0 + 3 * EconomyTracker.HOUR + 10 * M, stores("pole", Map.of("minecraft:wheat", 910L)), null);
        final EconomyTracker.Estimate e = t.estimate(c, t0 + 3 * EconomyTracker.HOUR + 10 * M, EconomyTracker.DAY);
        assertEquals(10 * M, e.coveredMs());
        assertEquals(10L, e.gained().get("minecraft:wheat"));
        assertTrue(e.perDay().isEmpty(), "10 min obserwacji to za mało na dobę");
    }

    @Test
    void samplesCloserThanAMinuteAreSkippedAndOldOnesExpire() {
        final EconomyTracker t = new EconomyTracker();
        final long t0 = 1_000L;
        assertTrue(t.sample(c, t0, Map.of(), new EconomyTracker.Point(t0, 10L, 5L, 0, 0, 0, 0, 0)));
        assertFalse(t.sample(c, t0 + 30_000, Map.of(), new EconomyTracker.Point(t0 + 30_000, 10L, 5L, 0, 0, 0, 0, 0)));
        assertEquals(1, t.points(c, 0).size());
        t.sample(c, t0 + 49 * EconomyTracker.HOUR, Map.of(), new EconomyTracker.Point(t0 + 49 * EconomyTracker.HOUR, 1L, 6L, 0, 0, 0, 0, 0));
        assertEquals(1, t.points(c, 0).size(), "starsze niż 48 h odpadają");
        assertEquals(t0 + 49 * EconomyTracker.HOUR, t.lastSample(c));
    }

    @Test
    void waitingClockStartsOnceAndRestartsForAnotherMaterial() {
        final EconomyTracker t = new EconomyTracker();
        assertEquals(100L, t.waitingSince(c, "site1", "kamień", 100L, 0));
        assertEquals(100L, t.waitingSince(c, "site1", "kamień", 900L, 0));
        assertEquals(950L, t.waitingSince(c, "site1", "drewno", 950L, 0), "inny materiał — nowy zegar");
        assertEquals(42L, t.waitingSince(c, "site1", "drewno", 990L, 42L), "czas z Ludnosc wygrywa");
        t.keepWaiting(c, Set.of());
        assertEquals(2000L, t.waitingSince(c, "site1", "drewno", 2000L, 0), "po zakończeniu czekania zegar od nowa");
    }

    @Test
    void retainDropsDeletedCountries() {
        final EconomyTracker t = new EconomyTracker();
        t.sample(c, 1000L, Map.of(), null);
        t.retain(Set.of());
        assertEquals(0L, t.lastSample(c));
    }
}
