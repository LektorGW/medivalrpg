// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrizeSplitTest {

    private static final List<Integer> SPLIT = List.of(60, 30, 10);

    @Test
    void standardSplitWithSharedThirdPlace() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(1000, SPLIT,
            List.of(List.of("w"), List.of("r"), List.of("s1", "s2")), false);
        assertEquals(Map.of("w", 600L, "r", 300L, "s1", 50L, "s2", 50L), r.payouts());
        assertEquals(0, r.remainder());
        assertEquals(1000, r.paid());
    }

    @Test
    void roundingDustGoesToFirstMemberAndNothingIsLost() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(1001, SPLIT,
            List.of(List.of("w"), List.of("r"), List.of("s1", "s2")), false);
        // 60% = 600 (floor of 600.6), 30% = 300, 10% = 100 → 50/50
        assertEquals(600L, r.payouts().get("w"));
        assertEquals(300L, r.payouts().get("r"));
        assertEquals(1001, r.paid() + r.remainder());
        final PrizeSplit.Result<String> odd = PrizeSplit.split(1030, List.of(0, 0, 10),
            List.of(List.of(), List.of(), List.of("x", "y")), false);
        assertEquals(52L, odd.payouts().get("x"), "103 split as 52 + 51");
        assertEquals(51L, odd.payouts().get("y"));
    }

    @Test
    void emptyPlacesReturnToSource() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(1000, SPLIT,
            List.of(List.of("w"), List.of("r"), List.of()), false);
        assertEquals(Map.of("w", 600L, "r", 300L), r.payouts());
        assertEquals(100, r.remainder());
    }

    @Test
    void emptyPlacesCanGoToChampion() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(1000, SPLIT,
            List.of(List.of("w"), List.of(), List.of()), true);
        assertEquals(Map.of("w", 1000L), r.payouts());
        assertEquals(0, r.remainder());
    }

    @Test
    void percentBelowHundredLeavesRemainder() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(500, List.of(50, 20),
            List.of(List.of("w"), List.of("r")), true);
        assertEquals(Map.of("w", 250L, "r", 100L), r.payouts());
        assertEquals(150, r.remainder());
    }

    @Test
    void noChampionEverythingReturns() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(700, SPLIT, List.of(List.of(), List.of(), List.of()), true);
        assertTrue(r.payouts().isEmpty());
        assertEquals(700, r.remainder());
    }

    @Test
    void zeroPool() {
        final PrizeSplit.Result<String> r = PrizeSplit.split(0, SPLIT, List.of(List.of("w")), false);
        assertTrue(r.payouts().isEmpty());
        assertEquals(0, r.remainder());
    }

    @Test
    void hugePoolDoesNotOverflow() {
        final long pool = Long.MAX_VALUE / 2;
        final PrizeSplit.Result<String> r = PrizeSplit.split(pool, SPLIT,
            List.of(List.of("w"), List.of("r"), List.of("s")), false);
        assertEquals(pool, r.paid() + r.remainder());
        assertTrue(r.payouts().get("w") > 0);
    }

    @Test
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> PrizeSplit.split(-1, SPLIT, List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> PrizeSplit.split(10, List.of(60, 50), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> PrizeSplit.split(10, List.of(-5), List.of(), false));
    }

    @Test
    void shareHelper() {
        assertEquals(600, PrizeSplit.share(1000, 60));
        assertEquals(0, PrizeSplit.share(1, 60));
        assertEquals(60, PrizeSplit.share(100, 60));
        assertEquals(61, PrizeSplit.share(102, 60));
    }
}
