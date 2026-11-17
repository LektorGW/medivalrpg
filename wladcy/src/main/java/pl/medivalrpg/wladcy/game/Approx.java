// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

/** Rounding of amounts for the prompt. Pure. */
public final class Approx {

    private Approx() {
    }

    /** Rounds to two significant digits (below 100 the value is exact): 1234 → 1200, 98765 → 99000. */
    public static long round(final long value) {
        if (value < 100) {
            return Math.max(0, value);
        }
        long scale = 1;
        while (value / scale >= 100) {
            scale *= 10;
        }
        return Math.round((double) value / scale) * scale;
    }
}
