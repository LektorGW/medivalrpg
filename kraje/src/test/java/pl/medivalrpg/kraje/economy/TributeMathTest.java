// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Daily tribute of a vassal, debt accumulation and the buyout price. */
class TributeMathTest {

    private static final long DAY = 86_400_000L;

    @Test
    void dailyTributeIsShareOfIncomeWithMinimum() {
        assertEquals(100, TributeMath.daily(0.25, 20, 400));
        assertEquals(20, TributeMath.daily(0.25, 20, 40), "10 < min 20");
        assertEquals(20, TributeMath.daily(0.25, 20, 0), "no income: minimum");
        assertEquals(24, TributeMath.daily(0.25, 20, 99), "24.75 -> 24");
        assertEquals(0, TributeMath.daily(0.25, 0, 3));
    }

    @Test
    void fullPaymentClearsDebt() {
        final TributeMath.Settlement s = TributeMath.settle(100, 0, 0, 500);
        assertEquals(100, s.due());
        assertEquals(100, s.paid());
        assertEquals(0, s.debt());
        assertEquals(0, s.debtDays());
    }

    @Test
    void debtGrowsWhileTreasuryIsShortAndIsRepaidLater() {
        // day 1: 30 of 100 paid
        TributeMath.Settlement s = TributeMath.settle(100, 0, 0, 30);
        assertEquals(30, s.paid());
        assertEquals(70, s.debt());
        assertEquals(1, s.debtDays());
        // day 2: empty treasury, old debt + today's tribute
        s = TributeMath.settle(100, s.debt(), s.debtDays(), 0);
        assertEquals(170, s.due());
        assertEquals(0, s.paid());
        assertEquals(170, s.debt());
        assertEquals(2, s.debtDays());
        // day 3: still short
        s = TributeMath.settle(100, s.debt(), s.debtDays(), 200);
        assertEquals(200, s.paid());
        assertEquals(70, s.debt());
        assertEquals(3, s.debtDays());
        // day 4: everything paid, counter resets
        s = TributeMath.settle(100, s.debt(), s.debtDays(), 1000);
        assertEquals(170, s.paid());
        assertEquals(0, s.debt());
        assertEquals(0, s.debtDays());
    }

    @Test
    void negativeInputsAreSafe() {
        final TributeMath.Settlement s = TributeMath.settle(-5, -10, 2, -100);
        assertEquals(0, s.due());
        assertEquals(0, s.paid());
        assertEquals(0, s.debtDays());
    }

    @Test
    void remainingDaysRoundUp() {
        final long now = 1_000_000L;
        assertEquals(0, TributeMath.remainingDays(now, now));
        assertEquals(0, TributeMath.remainingDays(now, now - 1));
        assertEquals(1, TributeMath.remainingDays(now, now + 1));
        assertEquals(1, TributeMath.remainingDays(now, now + DAY));
        assertEquals(2, TributeMath.remainingDays(now, now + DAY + 1));
        assertEquals(7, TributeMath.remainingDays(now, now + 7 * DAY));
    }

    @Test
    void buyoutPrice() {
        assertEquals(1050, TributeMath.buyout(1.5, 100, 7, 0));
        assertEquals(1050 + 70, TributeMath.buyout(1.5, 100, 7, 70), "debt is added in full");
        assertEquals(32, TributeMath.buyout(1.5, 21, 1, 0), "31.5 rounded up");
        assertEquals(0, TributeMath.buyout(1.5, 100, 0, 0), "ended vassalage is free");
        assertEquals(40, TributeMath.buyout(0, 100, 5, 40), "multiplier 0: only the debt");
    }
}
