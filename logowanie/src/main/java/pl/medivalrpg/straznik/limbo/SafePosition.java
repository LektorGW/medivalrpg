// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.limbo;

import java.util.OptionalDouble;
import java.util.function.DoublePredicate;
import java.util.function.IntToDoubleFunction;

/** Pure column selection: preserve the exact position when supported, otherwise descend at most 64 blocks. */
public final class SafePosition {
    public static final int DEPTH = 64;
    private SafePosition() { }

    public static OptionalDouble select(double original, int min, int max,
                                        IntToDoubleFunction surface, DoublePredicate safe) {
        if (!Double.isFinite(original) || original < min || original >= max) return OptionalDouble.empty();
        for (int block = Math.min(max - 1, (int) Math.floor(original));
             block >= Math.max(min, (int) Math.floor(original) - DEPTH - 1); block--) {
            double top = surface.applyAsDouble(block);
            if (!Double.isFinite(top) || top > original + 0.001 || original - top > DEPTH) continue;
            if (Math.abs(original - top) < 0.001 && safe.test(original)) return OptionalDouble.of(original);
            if (safe.test(top)) return OptionalDouble.of(top);
        }
        return OptionalDouble.empty();
    }
}
