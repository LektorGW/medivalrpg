// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.UUID;

/** Deterministic per-villager pseudo random numbers (stable for a villager, a salt and e.g. a day). */
public final class Hashing {

    private Hashing() {
    }

    /** SplitMix64 finaliser. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform value in [0, 1) for the id, a salt and an extra number (e.g. the day). */
    public static double unit(final UUID id, final String salt, final long extra) {
        long h = id.getMostSignificantBits() * 31 + id.getLeastSignificantBits();
        h = mix(h ^ (salt == null ? 0L : salt.hashCode() * 0x9E3779B97F4A7C15L) ^ mix(extra + 0x632BE59BD9B4E019L));
        return (h >>> 11) * 0x1.0p-53;
    }

    public static double unit(final UUID id, final String salt) {
        return unit(id, salt, 0L);
    }

    /** Integer in [-range, range] for the id and salt (e.g. a stable offset around a building). */
    public static int offset(final UUID id, final String salt, final int range) {
        if (range <= 0) {
            return 0;
        }
        return (int) Math.floor(unit(id, salt) * (2 * range + 1)) - range;
    }
}
