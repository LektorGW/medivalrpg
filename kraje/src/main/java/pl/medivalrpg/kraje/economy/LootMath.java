// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spoils of war (pure functions, copper). Shares are rounded down so the loser never pays more than the share;
 * the fraction is used as written in the config ({@code 0.29} is exactly 29 %, no binary rounding error).
 */
public final class LootMath {

    private LootMath() {
    }

    /** {@code ⌊balance × fraction⌋}, fraction clamped to 0..1; 0 for an empty or negative balance. */
    public static long share(final long balance, final double fraction) {
        if (balance <= 0 || !(fraction > 0)) {
            return 0L;
        }
        final double f = Math.min(1.0, fraction);
        return BigDecimal.valueOf(balance).multiply(BigDecimal.valueOf(f)).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /**
     * Splits {@code total} into {@code parts} equal shares; the indivisible rest (fewer than {@code parts} coppers)
     * goes one copper each to the first parts, so the shares always add up to {@code total}.
     */
    public static long[] split(final long total, final int parts) {
        if (parts <= 0) {
            return new long[0];
        }
        final long[] out = new long[parts];
        if (total <= 0) {
            return out;
        }
        final long each = total / parts;
        final long rest = total % parts;
        for (int i = 0; i < parts; i++) {
            out[i] = each + (i < rest ? 1 : 0);
        }
        return out;
    }
}
