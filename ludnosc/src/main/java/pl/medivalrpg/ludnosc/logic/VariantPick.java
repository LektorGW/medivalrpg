// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * Which variant of a building type a plot gets (pure, unit tested): fixed by the plot position, so a restart, a
 * retry or a second look at the same place picks the same one, while neighbouring plots get different ones.
 */
public final class VariantPick {

    private VariantPick() {
    }

    /** Index into a family of {@code size} variants for the plot at {@code x, z}. */
    public static int start(final int size, final String world, final int x, final int z) {
        if (size <= 1) {
            return 0;
        }
        long h = 0x9E3779B97F4A7C15L ^ (world == null ? 0 : world.hashCode());
        h = (h ^ (x >> 2)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (z >> 2)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (int) Math.floorMod(h, (long) size);
    }
}
