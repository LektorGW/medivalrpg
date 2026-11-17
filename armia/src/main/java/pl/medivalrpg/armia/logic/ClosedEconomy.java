// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * NPC money in the closed world pool (Skarbiec, 100 000 000 md since 2026-10-28). Pure.
 *
 * <p>In the closed pool {@code SkarbiecApi.deposit} never creates money: Skarbiec moves it from {@code world:payments}
 * (the operating budget fed by fees, wages and other withdrawals). The AI's old "virtual income" therefore drew on a
 * budget shared with every other system without any limit. Now it takes at most {@code share} of the surplus above
 * {@code floor} per tick, and the army is sized to what income and savings can pay for {@code sustainDays}, so an NPC
 * country does not recruit itself into desertions when the operating budget runs dry.</p>
 */
public final class ClosedEconomy {

    private ClosedEconomy() {
    }

    /** The closed pool is active when the world accounts hold money ({@code auto}), or as forced by config. */
    public static boolean closedPool(final String mode, final long worldReserve, final long worldCash, final long worldPayments) {
        final String m = mode == null ? "auto" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(m) || "tak".equals(m)) {
            return true;
        }
        if ("false".equals(m) || "nie".equals(m)) {
            return false;
        }
        return worldReserve > 0 || worldCash > 0 || worldPayments > 0;
    }

    /** Income for this tick: the personality formula, capped by the operating budget's surplus share. */
    public static long income(final long formula, final boolean closed, final long payments, final long floor, final double share) {
        if (formula <= 0) {
            return 0;
        }
        if (!closed) {
            return formula;
        }
        final long surplus = Math.max(0, payments - Math.max(0, floor));
        final long cap = (long) Math.floor(surplus * Math.max(0.0, share));
        return Math.max(0, Math.min(formula, cap));
    }

    /**
     * Soldiers the country can keep paying: (daily income + savings / days) / wage per soldier. {@link Integer#MAX_VALUE}
     * when the wage is unknown (no soldiers yet → use the first unit's wage via {@code wagePerSoldier}).
     */
    public static int sustainableArmy(final long dailyIncome, final long balance, final long wagePerSoldier, final double sustainDays) {
        if (wagePerSoldier <= 0) {
            return Integer.MAX_VALUE;
        }
        final double perDay = Math.max(0, dailyIncome) + Math.max(0, balance) / Math.max(1.0, sustainDays);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.floor(perDay / wagePerSoldier)));
    }

    /** Desired army in the closed pool: never above what can be paid, but never forces disbanding existing soldiers. */
    public static int cappedDesired(final int desired, final int current, final int sustainable) {
        return Math.max(0, Math.min(desired, Math.max(current, sustainable)));
    }
}
