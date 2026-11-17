// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.money;

import java.util.Arrays;

/**
 * Pure coin arithmetic used by the physical-coin methods (no Bukkit, fully unit tested).
 *
 * <p>{@link #plan} decides which coins to take for a payment: it never overpays more than necessary
 * (smallest possible change), and among payments with the same change it uses the fewest coins. The change is
 * always paid back with the fewest coins ({@link #split}).</p>
 */
public final class CoinMath {

    private CoinMath() {
    }

    /**
     * Coins taken from the payer and change given back.
     *
     * @param copper  copper coins to remove
     * @param silver  silver coins to remove
     * @param gold    gold coins to remove
     * @param change  value returned to the payer (as {@link #split(long)} coins)
     */
    public record Payment(long copper, long silver, long gold, long change) {

        public long takenValue() {
            return this.copper + this.silver * Denomination.SILVER.value() + this.gold * Denomination.GOLD.value();
        }

        public long coinsTaken() {
            return this.copper + this.silver + this.gold;
        }
    }

    /** Minimal coin counts worth exactly {@code amount}: index 0 copper, 1 silver, 2 gold. */
    public static long[] split(final long amount) {
        if (amount <= 0) {
            return new long[3];
        }
        final long gold = amount / Denomination.GOLD.value();
        final long rest = amount % Denomination.GOLD.value();
        return new long[] {rest % Denomination.SILVER.value(), rest / Denomination.SILVER.value(), gold};
    }

    /** Value of the given coin counts; {@link ArithmeticException} on overflow. */
    public static long value(final long copper, final long silver, final long gold) {
        return Math.addExact(copper, Math.addExact(Math.multiplyExact(silver, Denomination.SILVER.value()),
            Math.multiplyExact(gold, Denomination.GOLD.value())));
    }

    /**
     * How to pay {@code amount} out of a purse holding {@code c} copper, {@code s} silver and {@code g} gold coins.
     *
     * @return the payment, or {@code null} when the purse is worth less than {@code amount} (or amount ≤ 0)
     */
    public static Payment plan(final long c, final long s, final long g, final long amount) {
        if (amount <= 0 || c < 0 || s < 0 || g < 0) {
            return null;
        }
        final long sv = Denomination.SILVER.value();
        final long gv = Denomination.GOLD.value();
        final long total;
        try {
            total = value(c, s, g);
        } catch (final ArithmeticException e) {
            return plan(c, s, Math.min(g, amount / gv + 1), amount); // more gold than any payment could need
        }
        if (total < amount) {
            return null;
        }
        // Gold coins strictly below the amount: the rest (n1 > 0) is paid with silver and copper.
        final long goldUp = ceilDiv(amount, gv);          // gold coins that alone cover the amount
        Payment best = null;
        final long rgMax = Math.min(g, goldUp - 1);
        if (rgMax >= 0) {
            best = better(best, smallCoins(c, s, rgMax, amount - rgMax * gv, sv));
        }
        if (goldUp <= g) {
            best = better(best, new Payment(0, 0, goldUp, goldUp * gv - amount));
        }
        // Fewer gold coins than rgMax never helps: the silver/copper rest only grows (more change, more coins).
        // CoinMathTest checks this against an exhaustive search over small purses.
        return best;
    }

    /** Pays {@code rest} (> 0) with silver and copper next to {@code gold} gold coins; null if impossible. */
    private static Payment smallCoins(final long c, final long s, final long gold, final long rest, final long sv) {
        final long rs = Math.min(s, rest / sv);
        final long rc = rest - rs * sv;
        if (rc <= c) {
            return new Payment(rc, rs, gold, 0);
        }
        // Not enough copper for the remainder: one silver coin more and change back, if there is one.
        if (rs < s && rs == rest / sv) {
            return new Payment(0, rs + 1, gold, (rs + 1) * sv - rest);
        }
        return null;
    }

    private static Payment better(final Payment a, final Payment b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.change() != b.change()) {
            return a.change() < b.change() ? a : b;
        }
        return a.coinsTaken() <= b.coinsTaken() ? a : b;
    }

    private static long ceilDiv(final long a, final long b) {
        return -Math.floorDiv(-a, b);
    }

    // ------------------------------------------------------------------ slots

    /**
     * Removal of a {@link Payment} from concrete inventory slots.
     *
     * @param slotDenoms denomination of each slot ({@code null} = not a coin)
     * @param slotCounts stack size of each slot
     * @return new stack size for each slot (0 = slot emptied); slots that are not coins keep their size
     * @throws IllegalArgumentException when the slots do not hold the coins of the payment
     */
    public static int[] removeFromSlots(final Denomination[] slotDenoms, final int[] slotCounts, final Payment payment) {
        if (slotDenoms.length != slotCounts.length) {
            throw new IllegalArgumentException("slot arrays differ in length");
        }
        final int[] result = Arrays.copyOf(slotCounts, slotCounts.length);
        final long[] remaining = {payment.copper(), payment.silver(), payment.gold()};
        // Take from the last slots first, so the hotbar (slots 0-8) is touched last.
        for (int i = result.length - 1; i >= 0; i--) {
            final Denomination d = slotDenoms[i];
            if (d == null || result[i] <= 0) {
                continue;
            }
            final int idx = d.ordinal();
            final long take = Math.min(remaining[idx], result[i]);
            result[i] -= (int) take;
            remaining[idx] -= take;
        }
        if (remaining[0] != 0 || remaining[1] != 0 || remaining[2] != 0) {
            throw new IllegalArgumentException("slots do not hold the planned coins");
        }
        return result;
    }

    /** Counts of each denomination (0 copper, 1 silver, 2 gold) in the slots. */
    public static long[] count(final Denomination[] slotDenoms, final int[] slotCounts) {
        final long[] counts = new long[3];
        for (int i = 0; i < slotDenoms.length; i++) {
            if (slotDenoms[i] != null && slotCounts[i] > 0) {
                counts[slotDenoms[i].ordinal()] += slotCounts[i];
            }
        }
        return counts;
    }

    /**
     * Whether coins worth {@code amount} (as {@link #split}) fit into the slots.
     *
     * @param slotDenoms denomination of each slot, {@code null} for a non-coin item
     * @param slotCounts stack size, 0 for an empty slot
     * @param maxStack   max stack size of a coin
     */
    public static boolean fits(final Denomination[] slotDenoms, final int[] slotCounts, final int maxStack, final long amount) {
        final long[] need = split(amount);
        long empty = 0;
        for (int i = 0; i < slotDenoms.length; i++) {
            if (slotCounts[i] <= 0) {
                empty++;
            } else if (slotDenoms[i] != null) {
                final int idx = slotDenoms[i].ordinal();
                need[idx] = Math.max(0, need[idx] - (maxStack - slotCounts[i]));
            }
        }
        long stacks = 0;
        for (final long n : need) {
            stacks += ceilDiv(n, maxStack);
        }
        return stacks <= empty;
    }
}
