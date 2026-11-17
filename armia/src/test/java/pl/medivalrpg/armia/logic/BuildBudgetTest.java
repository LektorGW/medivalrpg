// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildBudgetTest {

    private static final BuildBudget.Rules R = BuildBudget.Rules.defaults();

    private static Personality p(final int expansion, final int wealth) {
        return new Personality(50, expansion, 50, wealth, 40, 20, true);
    }

    @Test
    void wealthAndExpansionRaiseTheShare() {
        assertEquals(0.15, BuildBudget.share(R, p(0, 0), false), 1e-9);
        assertTrue(BuildBudget.share(R, p(0, 100), false) > BuildBudget.share(R, p(0, 0), false));
        assertTrue(BuildBudget.share(R, p(100, 0), false) > BuildBudget.share(R, p(0, 0), false));
        assertEquals(0.55, BuildBudget.share(R, p(100, 100), false), 1e-9);
    }

    @Test
    void shareIsCapped() {
        final BuildBudget.Rules generous = new BuildBudget.Rules(0.5, 0.5, 0.5, 0.6, 0.6, 100, 70, 5000, 0.5);
        assertEquals(0.6, BuildBudget.share(generous, p(100, 100), false), 1e-9);
    }

    @Test
    void warMovesMoneyToTheArmy() {
        assertEquals(0.4 * 0.55, BuildBudget.share(R, p(100, 100), true), 1e-9);
        assertTrue(BuildBudget.budget(R, p(50, 50), 10_000, 1000, true) < BuildBudget.budget(R, p(50, 50), 10_000, 1000, false));
    }

    @Test
    void budgetOnlyFromMoneyAboveReserve() {
        // spendable 9000 × share 0.15 + 0.125 + 0.075 = 0.35 → 3150
        assertEquals(3150, BuildBudget.budget(R, p(50, 50), 10_000, 1000, false));
        assertEquals(0, BuildBudget.budget(R, p(50, 50), 900, 1000, false));
        assertEquals(0, BuildBudget.budget(R, p(0, 0), 1200, 1000, false)); // 30 < minBudget 100
    }

    @Test
    void richCountrySavesForRepresentativeBuildings() {
        final Personality rich = p(50, 90);
        assertTrue(BuildBudget.isRich(R, rich, 20_000));
        assertFalse(BuildBudget.isRich(R, rich, 1_000));
        assertFalse(BuildBudget.isRich(R, p(50, 40), 1_000_000));
        assertTrue(BuildBudget.keepSaving(R, rich, 20_000, "INSUFFICIENT_FUNDS"));
        assertFalse(BuildBudget.keepSaving(R, rich, 20_000, "OK"));
        assertFalse(BuildBudget.keepSaving(R, p(50, 40), 20_000, "INSUFFICIENT_FUNDS"));
        assertEquals(10_000, BuildBudget.savingsReserve(R, rich, 20_000, true, false));
        assertEquals(4_000, BuildBudget.savingsReserve(R, rich, 20_000, true, true));
        assertEquals(0, BuildBudget.savingsReserve(R, rich, 20_000, false, false));
        assertEquals(0, BuildBudget.savingsReserve(R, p(50, 40), 20_000, true, false));
    }

    @Test
    void savingsLimitRecruitment() {
        final AiTuning t = AiTuning.defaults();
        final Personality rich = p(50, 90);
        final long balance = 20_000;
        final long saved = BuildBudget.savingsReserve(R, rich, balance, true, false);
        final int free = AiDecisions.recruitBatch(t, rich, 100, 0, balance, 4000, 0);
        final int saving = AiDecisions.recruitBatch(t, rich, 100, 0, balance - saved, 4000, 0);
        assertTrue(saving < free);
    }
}
