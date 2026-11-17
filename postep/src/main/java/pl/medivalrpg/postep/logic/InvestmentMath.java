// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

/**
 * Diminishing returns of treasury investments. Within one day (between two daily ticks) the total points from
 * investing {@code T} copper are
 *
 * <pre>F(T) = cap × (1 − e^(−T × rate / cap))</pre>
 *
 * where {@code rate} = points per copper for the first coin and {@code cap} = the daily maximum. The first coins give
 * {@code rate} points each, every next one a bit less, and the total never exceeds {@code cap}. Splitting an
 * investment into parts gives exactly the same total as paying it at once.
 */
public final class InvestmentMath {

    private InvestmentMath() {
    }

    /** Total points for {@code investedCopper} invested in one day. */
    public static double total(final double investedCopper, final double ratePerCopper, final double cap) {
        if (investedCopper <= 0 || ratePerCopper <= 0 || cap <= 0) {
            return 0.0;
        }
        return cap * (1.0 - Math.exp(-investedCopper * ratePerCopper / cap));
    }

    /** Points gained by investing {@code amount} more when {@code alreadyInvested} copper was invested today. */
    public static double gain(final double alreadyInvested, final long amount, final double ratePerCopper, final double cap) {
        if (amount <= 0) {
            return 0.0;
        }
        final double before = total(Math.max(0, alreadyInvested), ratePerCopper, cap);
        final double after = total(Math.max(0, alreadyInvested) + amount, ratePerCopper, cap);
        return Math.max(0.0, after - before);
    }

    /** Points the next single copper gives (marginal rate). */
    public static double marginal(final double alreadyInvested, final double ratePerCopper, final double cap) {
        if (ratePerCopper <= 0 || cap <= 0) {
            return 0.0;
        }
        return ratePerCopper * Math.exp(-Math.max(0, alreadyInvested) * ratePerCopper / cap);
    }

    /**
     * Copper that must be invested in total today to reach {@code fraction} (0..1, exclusive) of the daily cap; used
     * by the NPC AI so it never throws money at an almost exhausted cap.
     */
    public static long investedForFraction(final double fraction, final double ratePerCopper, final double cap) {
        if (fraction <= 0 || ratePerCopper <= 0 || cap <= 0) {
            return 0L;
        }
        final double f = Math.min(fraction, 0.999);
        return (long) Math.ceil(-cap / ratePerCopper * Math.log(1.0 - f));
    }

    /**
     * Part of an offered {@code amount} worth paying today: what reaches 99% of the daily cap, never more than
     * offered. Money above it would buy almost nothing (the curve is flat there), so it stays in the treasury.
     */
    public static long useful(final double alreadyInvested, final long amount, final double ratePerCopper, final double cap) {
        if (amount <= 0) {
            return 0L;
        }
        final long room = investedForFraction(0.99, ratePerCopper, cap) - (long) Math.ceil(Math.max(0, alreadyInvested));
        return Math.max(0L, Math.min(amount, room));
    }

    /** Rate per copper from the configured "points per gold" (1 gold = 100 copper). */
    public static double perCopper(final double pointsPerGold) {
        return pointsPerGold / 100.0;
    }
}
