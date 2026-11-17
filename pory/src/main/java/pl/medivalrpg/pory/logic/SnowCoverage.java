// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How much of the ground is white on a given day of a season (pure, unit tested). Values are fractions 0..1 per day of
 * the season (day 1 = first value); days beyond the list repeat its last value; seasons without a list have none.
 * Whether a column is covered is a fixed function of its coordinates, so a higher fraction only adds columns and the
 * picture is identical for every player and every resend.
 */
public final class SnowCoverage {

    private final Map<VisualSeason, double[]> byDay = new EnumMap<>(VisualSeason.class);

    public SnowCoverage(final Map<VisualSeason, List<Double>> perDay) {
        if (perDay != null) {
            perDay.forEach((season, values) -> {
                if (season != null && values != null && !values.isEmpty()) {
                    final double[] a = new double[values.size()];
                    for (int i = 0; i < a.length; i++) {
                        final Double v = values.get(i);
                        a[i] = v == null || Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
                    }
                    this.byDay.put(season, a);
                }
            });
        }
    }

    /** Fraction of covered columns on {@code day} (1-based) of {@code season}. */
    public double coverage(final VisualSeason season, final int day) {
        final double[] a = season == null ? null : this.byDay.get(season);
        if (a == null) {
            return 0;
        }
        final int i = Math.max(0, Math.min(a.length - 1, day - 1));
        return a[i];
    }

    /** Highest fraction the season reaches (used for previews). */
    public double peak(final VisualSeason season) {
        final double[] a = season == null ? null : this.byDay.get(season);
        double max = 0;
        if (a != null) {
            for (final double v : a) {
                max = Math.max(max, v);
            }
        }
        return max;
    }

    /** Day with the highest coverage (1-based), for previews. */
    public int peakDay(final VisualSeason season) {
        final double[] a = season == null ? null : this.byDay.get(season);
        int best = 1;
        if (a != null) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] > a[best - 1]) {
                    best = i + 1;
                }
            }
        }
        return best;
    }

    /** Patch size of partial snow cover in blocks. */
    static final int CELL = 8;

    /**
     * Stable value 0..1 of a block column: smooth value noise in {@value #CELL}-block cells (patches of snow rather than
     * single speckles) with a quarter of per-column noise for ragged edges.
     */
    public static double columnValue(final int x, final int z) {
        final int cx = Math.floorDiv(x, CELL);
        final int cz = Math.floorDiv(z, CELL);
        final double fx = smooth((x - cx * CELL + 0.5) / CELL);
        final double fz = smooth((z - cz * CELL + 0.5) / CELL);
        final double a = hash(cx, cz, 1);
        final double b = hash(cx + 1, cz, 1);
        final double c = hash(cx, cz + 1, 1);
        final double d = hash(cx + 1, cz + 1, 1);
        final double patch = (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
        return 0.75 * patch + 0.25 * hash(x, z, 2);
    }

    private static double smooth(final double t) {
        return t * t * (3 - 2 * t);
    }

    static double hash(final int x, final int z, final long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L) ^ 0x5EA5_0115L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Whether a column is white at this coverage. */
    public static boolean covered(final int x, final int z, final double coverage) {
        return coverage > 0 && (coverage >= 1 || columnValue(x, z) < coverage);
    }
}
