// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

/**
 * Tribute of a vassal (pure functions, copper).
 */
public final class TributeMath {

    private static final long DAY = 86_400_000L;

    private TributeMath() {
    }

    /** Daily tribute: {@code max(min, ⌊share × income⌋)}. */
    public static long daily(final double share, final long min, final long income) {
        return Math.max(Math.max(0, min), LootMath.share(income, share));
    }

    /** Settlement of one day: what is paid now and the debt left afterwards. */
    public record Settlement(long due, long paid, long debt, int debtDays) {
    }

    /**
     * One daily settlement: today's tribute plus older debt, paid as far as the treasury allows (rest = debt).
     * {@code debtDays} grows while some debt is left and resets to 0 when everything is paid.
     */
    public static Settlement settle(final long dailyTribute, final long oldDebt, final int oldDebtDays, final long balance) {
        final long due = Math.max(0, dailyTribute) + Math.max(0, oldDebt);
        final long paid = Math.max(0, Math.min(due, balance));
        final long debt = due - paid;
        return new Settlement(due, paid, debt, debt > 0 ? oldDebtDays + 1 : 0);
    }

    /** Whole days of the vassalage left, rounded up (a started day counts). */
    public static long remainingDays(final long now, final long until) {
        final long left = until - now;
        return left <= 0 ? 0 : (left + DAY - 1) / DAY;
    }

    /** Buyout: {@code ⌈multiplier × daily × remainingDays⌉ + debt}. */
    public static long buyout(final double multiplier, final long dailyTribute, final long remainingDays, final long debt) {
        final long rounded = java.math.BigDecimal.valueOf(Math.max(0, multiplier))
            .multiply(java.math.BigDecimal.valueOf(Math.max(0, dailyTribute)))
            .multiply(java.math.BigDecimal.valueOf(Math.max(0, remainingDays)))
            .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        return rounded + Math.max(0, debt);
    }
}
