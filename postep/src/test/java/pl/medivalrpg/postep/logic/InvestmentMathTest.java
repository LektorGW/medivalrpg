// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InvestmentMathTest {

    private static final double RATE = InvestmentMath.perCopper(4); // 4 points per gold
    private static final double CAP = 20;

    @Test
    void firstCoinsGiveTheFullRate() {
        // 1 copper ≈ 0.04 points at the start
        assertEquals(0.04, InvestmentMath.gain(0, 1, RATE, CAP), 1e-4);
        assertEquals(0.04, InvestmentMath.marginal(0, RATE, CAP), 1e-12);
    }

    @Test
    void diminishingReturns() {
        final double first = InvestmentMath.gain(0, 500, RATE, CAP);
        final double second = InvestmentMath.gain(500, 500, RATE, CAP);
        final double third = InvestmentMath.gain(1000, 500, RATE, CAP);
        assertTrue(first > second && second > third, first + " > " + second + " > " + third);
        assertTrue(InvestmentMath.marginal(1000, RATE, CAP) < InvestmentMath.marginal(100, RATE, CAP));
        // 5 gold (scale) → 1 - 1/e of the cap
        assertEquals(CAP * (1 - Math.exp(-1)), first, 1e-9);
    }

    @Test
    void neverExceedsDailyCap() {
        assertTrue(InvestmentMath.total(1_000_000_000L, RATE, CAP) <= CAP);
        assertTrue(InvestmentMath.gain(100_000, 1_000_000, RATE, CAP) < 1e-6);
    }

    @Test
    void splittingGivesTheSameTotal() {
        final double once = InvestmentMath.gain(0, 3000, RATE, CAP);
        double parts = 0;
        double invested = 0;
        for (int i = 0; i < 30; i++) {
            parts += InvestmentMath.gain(invested, 100, RATE, CAP);
            invested += 100;
        }
        assertEquals(once, parts, 1e-9);
    }

    @Test
    void investedForFractionIsTheInverse() {
        final long t = InvestmentMath.investedForFraction(0.85, RATE, CAP);
        assertEquals(0.85 * CAP, InvestmentMath.total(t, RATE, CAP), 0.01);
        assertEquals(0, InvestmentMath.investedForFraction(0, RATE, CAP));
    }

    @Test
    void invalidInputsGiveNothing() {
        assertEquals(0, InvestmentMath.gain(0, 0, RATE, CAP));
        assertEquals(0, InvestmentMath.gain(0, -5, RATE, CAP));
        assertEquals(0, InvestmentMath.gain(0, 100, 0, CAP));
        assertEquals(0, InvestmentMath.gain(0, 100, RATE, 0));
    }

    @Test
    void usefulNeverTakesMoneyBeyondTheDailyCap() {
        // epoch 1 numbers: 4 points per gold, cap 20 → 1000 zł would buy ~20 points for the price of ~12 zł
        final double rate = InvestmentMath.perCopper(4);
        final double cap = 20;
        final long offered = 100_000;
        final long pay = InvestmentMath.useful(0, offered, rate, cap);
        assertTrue(pay < offered, "only the useful part is charged: " + pay);
        assertTrue(InvestmentMath.total(pay, rate, cap) >= 0.99 * cap - 1e-6, "still reaches 99% of the cap");
        // a small offer is taken in full
        assertEquals(50, InvestmentMath.useful(0, 50, rate, cap));
        // the cap already reached → nothing more is taken
        assertEquals(0, InvestmentMath.useful(pay, 500, rate, cap));
        assertEquals(0, InvestmentMath.useful(0, 0, rate, cap));
    }
}
