// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.medivalrpg.postep.logic.TestData.d;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.postep.model.Discovery;

class NpcStrategyTest {

    private static final UUID COUNTRY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Same era, same cost, no requirements, one per category; none required. */
    private static TechTree categories() {
        return TechTree.of(List.of(
            d("woj", 1, 100, false, "wojsko"),
            d("gos", 1, 100, false, "gospodarka"),
            d("bud", 1, 100, false, "budownictwo"),
            d("dyp", 1, 100, false, "dyplomacja"),
            d("nau", 1, 100, false, "nauka")));
    }

    private static String choose(final TechTree t, final Set<String> owned, final NpcStrategy.Personality p, final boolean urgent) {
        return NpcStrategy.chooseResearch(t, owned, 1, p, COUNTRY, urgent).map(Discovery::id).orElse(null);
    }

    @Test
    void personalityDrivesTheCategory() {
        final TechTree t = categories();
        assertEquals("woj", choose(t, Set.of(), new NpcStrategy.Personality(95, 10, 10, 10), false));
        assertEquals("gos", choose(t, Set.of(), new NpcStrategy.Personality(10, 10, 10, 95), false));
        assertEquals("bud", choose(t, Set.of(), new NpcStrategy.Personality(10, 95, 10, 10), false));
        assertEquals("dyp", choose(t, Set.of(), new NpcStrategy.Personality(10, 10, 95, 10), false));
    }

    @Test
    void requiredDiscoveriesWinWhenUrgent() {
        final TechTree t = TechTree.of(List.of(
            d("woj", 1, 100, false, "wojsko"),
            d("wym", 1, 100, true, "nauka")));
        final NpcStrategy.Personality warlike = new NpcStrategy.Personality(100, 0, 0, 0);
        assertEquals("woj", choose(t, Set.of(), warlike, false));
        assertEquals("wym", choose(t, Set.of(), warlike, true));
    }

    @Test
    void neverPicksLockedOrOwned() {
        final TechTree t = TestData.small();
        for (int i = 0; i < 20; i++) {
            final UUID id = new UUID(i * 7919L, i * 104729L);
            final String pick = NpcStrategy.chooseResearch(t, Set.of("a"), 1, NpcStrategy.Personality.NEUTRAL, id, false)
                .map(Discovery::id).orElseThrow();
            assertTrue(Set.of("b", "x").contains(pick), pick);
        }
        assertTrue(NpcStrategy.chooseResearch(t, Set.of("a", "b", "c", "x"), 1, NpcStrategy.Personality.NEUTRAL, COUNTRY, false).isEmpty());
        assertEquals("d", NpcStrategy.chooseResearch(t, Set.of("a", "b", "c", "x"), 2, NpcStrategy.Personality.NEUTRAL, COUNTRY, false)
            .map(Discovery::id).orElseThrow());
    }

    @Test
    void cheaperIsPreferredAtEqualWeight() {
        final TechTree t = TechTree.of(List.of(d("drogie", 1, 900, false, "nauka"), d("tanie", 1, 100, false, "nauka")));
        assertEquals("tanie", choose(t, Set.of(), NpcStrategy.Personality.NEUTRAL, false));
    }

    @Test
    void jitterIsDeterministicAndSmall() {
        final double a = NpcStrategy.jitter(COUNTRY, "druk");
        assertEquals(a, NpcStrategy.jitter(COUNTRY, "druk"));
        assertTrue(a >= 0.9 && a <= 1.1);
    }

    private static final NpcStrategy.InvestSettings S = new NpcStrategy.InvestSettings(12, 35, 0.4, 500, 0.2, 0.85);

    @Test
    void investShareFromPersonalityWarAndOverride() {
        final double neutral = NpcStrategy.investShare(S, NpcStrategy.Personality.NEUTRAL, false, -1);
        assertEquals(12 * 1.0 * 1.0, neutral, 1e-9);
        final double rich = NpcStrategy.investShare(S, new NpcStrategy.Personality(50, 50, 100, 100), false, -1);
        assertTrue(rich > neutral);
        assertTrue(rich <= 35);
        assertEquals(neutral * 0.4, NpcStrategy.investShare(S, NpcStrategy.Personality.NEUTRAL, true, -1), 1e-9);
        assertEquals(7, NpcStrategy.investShare(S, NpcStrategy.Personality.NEUTRAL, true, 7));
        assertEquals(0, NpcStrategy.investShare(S, NpcStrategy.Personality.NEUTRAL, false, 0));
    }

    @Test
    void investAmountRespectsReserveBalanceAndCap() {
        final double rate = InvestmentMath.perCopper(4);
        // 10 % of 5000 income = 500; balance 100 000 → 20 % = 20 000; cap fraction 85 % of 20 pts ≈ 949 copper
        assertEquals(500, NpcStrategy.investAmount(S, 5000, 100_000, 10, 0, rate, 20));
        // reserve: balance 700, reserve 500 → at most 200 ... and 20 % of 700 = 140
        assertEquals(140, NpcStrategy.investAmount(S, 5000, 700, 10, 0, rate, 20));
        assertEquals(0, NpcStrategy.investAmount(S, 5000, 400, 10, 0, rate, 20));
        // cap: huge income, the amount stops near 85 % of the daily cap
        final long capped = NpcStrategy.investAmount(S, 10_000_000, 10_000_000, 30, 0, rate, 20);
        assertEquals(InvestmentMath.investedForFraction(0.85, rate, 20), capped);
        // already invested beyond the useful amount → nothing
        assertEquals(0, NpcStrategy.investAmount(S, 10_000_000, 10_000_000, 30, capped + 10, rate, 20));
        assertEquals(0, NpcStrategy.investAmount(S, 0, 10_000, 30, 0, rate, 20));
    }
}
