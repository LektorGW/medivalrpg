// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.money;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Change-making logic behind {@code takeCoins} (no server needed). */
class CoinMathTest {

    private static final Denomination C = Denomination.COPPER;
    private static final Denomination S = Denomination.SILVER;
    private static final Denomination G = Denomination.GOLD;

    @Test
    void splitUsesFewestCoins() {
        assertArrayEquals(new long[] {0, 0, 0}, CoinMath.split(0));
        assertArrayEquals(new long[] {5, 4, 3}, CoinMath.split(345));
        assertArrayEquals(new long[] {0, 0, 10}, CoinMath.split(1000));
        assertArrayEquals(new long[] {9, 9, 0}, CoinMath.split(99));
    }

    @Test
    void exactPaymentNeedsNoChange() {
        assertEquals(new CoinMath.Payment(5, 4, 3, 0), CoinMath.plan(10, 10, 10, 345));
    }

    @Test
    void breaksGoldWhenOnlyGold() {
        // 1 zł for a 15 md price → 85 md change (8 sr 5 md).
        final CoinMath.Payment p = CoinMath.plan(0, 0, 1, 15);
        assertEquals(new CoinMath.Payment(0, 0, 1, 85), p);
        assertArrayEquals(new long[] {5, 8, 0}, CoinMath.split(p.change()));
    }

    @Test
    void breaksSilverWhenCopperIsShort() {
        // 15 md with 3 md + 2 sr + 1 zł: pay 2 sr, get 5 md back (not the gold coin).
        assertEquals(new CoinMath.Payment(0, 2, 0, 5), CoinMath.plan(3, 2, 1, 15));
    }

    @Test
    void prefersFewerCoinsOnEqualChange() {
        assertEquals(new CoinMath.Payment(0, 0, 1, 0), CoinMath.plan(100, 10, 1, 100));
        assertEquals(new CoinMath.Payment(0, 5, 0, 0), CoinMath.plan(50, 5, 0, 50));
    }

    @Test
    void notEnoughMoney() {
        assertNull(CoinMath.plan(9, 9, 0, 100));
        assertNull(CoinMath.plan(0, 0, 0, 1));
        assertNull(CoinMath.plan(5, 5, 5, 0));
        assertNull(CoinMath.plan(5, 5, 5, -3));
    }

    @Test
    void hugePurseDoesNotOverflow() {
        final CoinMath.Payment p = CoinMath.plan(0, 0, Long.MAX_VALUE, 250);
        assertNotNull(p);
        assertEquals(3, p.gold());
        assertEquals(50, p.change());
    }

    /** Exhaustive check of every small purse: minimal change, fewest coins, sums add up. */
    @Test
    void planIsOptimalAgainstBruteForce() {
        for (int c = 0; c <= 12; c++) {
            for (int s = 0; s <= 12; s++) {
                for (int g = 0; g <= 4; g++) {
                    final long total = c + 10L * s + 100L * g;
                    for (long amount = 1; amount <= total + 5; amount++) {
                        final long best = bruteForceMinChange(c, s, g, amount);
                        final CoinMath.Payment p = CoinMath.plan(c, s, g, amount);
                        final String ctx = "c=" + c + " s=" + s + " g=" + g + " amount=" + amount;
                        if (best < 0) {
                            assertNull(p, ctx);
                            continue;
                        }
                        assertNotNull(p, ctx);
                        assertTrue(p.copper() >= 0 && p.copper() <= c, ctx);
                        assertTrue(p.silver() >= 0 && p.silver() <= s, ctx);
                        assertTrue(p.gold() >= 0 && p.gold() <= g, ctx);
                        assertEquals(amount + p.change(), p.takenValue(), ctx);
                        assertEquals(best, p.change(), ctx);
                        assertTrue(p.change() < 100, ctx);
                        assertEquals(bruteForceMinCoins(c, s, g, amount, best), p.coinsTaken(), ctx);
                    }
                }
            }
        }
    }

    private static long bruteForceMinChange(final int c, final int s, final int g, final long amount) {
        long best = -1;
        for (int rg = 0; rg <= g; rg++) {
            for (int rs = 0; rs <= s; rs++) {
                for (int rc = 0; rc <= c; rc++) {
                    final long v = rc + 10L * rs + 100L * rg;
                    if (v >= amount && (best < 0 || v - amount < best)) {
                        best = v - amount;
                    }
                }
            }
        }
        return best;
    }

    /** Fewest coins among payments giving exactly {@code change} back. */
    private static long bruteForceMinCoins(final int c, final int s, final int g, final long amount, final long change) {
        long best = Long.MAX_VALUE;
        for (int rg = 0; rg <= g; rg++) {
            for (int rs = 0; rs <= s; rs++) {
                final long rc = amount + change - 10L * rs - 100L * rg;
                if (rc >= 0 && rc <= c) {
                    best = Math.min(best, rc + rs + rg);
                }
            }
        }
        return best;
    }

    @Test
    void removeFromSlotsTakesFromTheBackFirst() {
        final Denomination[] d = {C, null, S, C, G, null};
        final int[] n = {5, 1, 3, 4, 2, 0};
        // 7 md + 1 sr: copper from slot 3 (4) then slot 0 (3), silver from slot 2.
        final int[] after = CoinMath.removeFromSlots(d, n, new CoinMath.Payment(7, 1, 0, 0));
        assertArrayEquals(new int[] {2, 1, 2, 0, 2, 0}, after);
        assertArrayEquals(new long[] {9, 3, 2}, CoinMath.count(d, n));
    }

    @Test
    void removeFromSlotsRejectsMissingCoins() {
        assertThrows(IllegalArgumentException.class,
            () -> CoinMath.removeFromSlots(new Denomination[] {C}, new int[] {1}, new CoinMath.Payment(2, 0, 0, 0)));
    }

    /** The whole takeCoins computation on an inventory model, as CoinService does it, including change. */
    @Test
    void takeCoinsOnInventoryModel() {
        final Denomination[] d = {G, null, C, C};
        final int[] n = {2, 64, 2, 1};
        final long[] have = CoinMath.count(d, n);
        final CoinMath.Payment p = CoinMath.plan(have[0], have[1], have[2], 47);
        // 3 md and no silver: pay 1 zł, get 53 md (5 sr 3 md) back; copper stays.
        assertEquals(new CoinMath.Payment(0, 0, 1, 53), p);
        assertArrayEquals(new int[] {1, 64, 2, 1}, CoinMath.removeFromSlots(d, n, p));
        assertEquals(203 - 47, 100 + 3 + p.change());
    }

    @Test
    void fitsCountsFreeSpace() {
        assertTrue(CoinMath.fits(new Denomination[] {C, null}, new int[] {60, 1}, 64, 4));
        assertFalse(CoinMath.fits(new Denomination[] {C, null}, new int[] {60, 1}, 64, 5));
        assertTrue(CoinMath.fits(new Denomination[] {null, null, null}, new int[] {0, 0, 0}, 64, 111));
        assertFalse(CoinMath.fits(new Denomination[] {null, null, null}, new int[] {0, 0, 1}, 64, 111));
        assertTrue(CoinMath.fits(new Denomination[0], new int[0], 64, 0));
    }
}
