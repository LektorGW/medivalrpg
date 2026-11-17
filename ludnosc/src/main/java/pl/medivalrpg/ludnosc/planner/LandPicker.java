// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Which chunk a country should claim to make room for a building (pure, unit tested): a free chunk next to the
 * country's land (Kraje only allows adjacent claims), close to the town centre, touching as much of the country as
 * possible (compact land), with flat and dry terrain when it is known.
 */
public final class LandPicker {

    private LandPicker() {
    }

    public static long key(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * @param own     the country's chunk keys
     * @param radius  max distance (chunks) from the centre chunk
     * @param free    chunk is unclaimed
     * @param view    terrain (may know the chunk or not)
     * @return the chunk key, or {@link Long#MIN_VALUE} when no candidate exists
     */
    public static long pick(final Set<Long> own, final int centreCx, final int centreCz, final int radius,
                            final BiPredicate<Integer, Integer> free, final TerrainView view) {
        long best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        final Set<Long> seen = new java.util.HashSet<>();
        for (final long k : own) {
            final int ox = (int) (k >> 32);
            final int oz = (int) k;
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int cx = ox + d[0];
                final int cz = oz + d[1];
                final long c = key(cx, cz);
                if (own.contains(c) || !seen.add(c) || Math.max(Math.abs(cx - centreCx), Math.abs(cz - centreCz)) > radius
                    || !free.test(cx, cz)) {
                    continue;
                }
                int neighbours = 0;
                for (final int[] e : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (own.contains(key(cx + e[0], cz + e[1]))) {
                        neighbours++;
                    }
                }
                final double score = Math.hypot(cx - centreCx, cz - centreCz) * 2 - neighbours * 3 + terrainPenalty(view, cx, cz);
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        return best;
    }

    /** Slope and water of a chunk (sampled every 4 blocks); unknown terrain gets a neutral penalty. */
    static double terrainPenalty(final TerrainView view, final int cx, final int cz) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int water = 0;
        int known = 0;
        for (int x = cx * 16 + 2; x < cx * 16 + 16; x += 4) {
            for (int z = cz * 16 + 2; z < cz * 16 + 16; z += 4) {
                final int h = view.height(x, z);
                if (h == TerrainView.UNKNOWN) {
                    continue;
                }
                known++;
                if (view.liquid(x, z)) {
                    water++;
                }
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (known == 0) {
            return 6;
        }
        return Math.min(12, max - min) + water * 3.0;
    }
}
