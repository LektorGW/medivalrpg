// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;

/**
 * Terrain grading of a redesigned town (pure, unit tested) — one level per quarter and for the square, gentle slopes
 * between them instead of stair-step terraces.
 *
 * <ul>
 *   <li>{@link #levels}: every quarter (and the square) gets one level, the median of its terrain; neighbouring areas
 *       are pulled together until their levels differ by no more than the slope allows over the street between them
 *       (the square stays fixed). An area that would need more than {@link #MAX_CUT} blocks of cut or fill against
 *       any of its terrain columns is left natural ({@link TerrainView#UNKNOWN}).</li>
 *   <li>{@link #targets}: the flat interiors ({@link Area}, a quarter without its {@link #BAND}) are fixed at their
 *       level, everything outside the graded domain keeps its natural height; the free columns in between (streets,
 *       bands) lie inside the upper/lower envelopes of slope 1 : {@link #RUN} around those anchors and are as close to
 *       the natural terrain as the envelopes allow. Neighbouring columns never differ by more than one block.</li>
 * </ul>
 */
public final class TownGrade {

    /** Columns at the edge of a quarter that slope towards the street (outside the flat interior). */
    public static final int BAND = 2;
    /** Horizontal blocks per block of height change on the graded slopes. */
    public static final int RUN = 2;
    /** Largest cut or fill against an area's terrain columns; rougher areas stay natural. */
    public static final int MAX_CUT = 6;
    /** Anchors further away than this do not shape a column (window around the region). */
    static final int WINDOW = 16;

    /** A flat area at one level (quarter interior or the square), inclusive bounds. */
    public record Area(int minX, int minZ, int maxX, int maxZ, int level) {

        public boolean contains(final int x, final int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        boolean overlaps(final Area o) {
            return o.minX <= this.maxX && o.maxX >= this.minX && o.minZ <= this.maxZ && o.maxZ >= this.minZ;
        }

        /** Chebyshev gap between two rectangles (0 = touching or overlapping). */
        int gap(final Area o) {
            final int dx = Math.max(0, Math.max(o.minX - this.maxX, this.minX - o.maxX) - 1);
            final int dz = Math.max(0, Math.max(o.minZ - this.maxZ, this.minZ - o.maxZ) - 1);
            return Math.max(dx, dz);
        }
    }

    private TownGrade() {
    }

    /** A kept building occupies this quarter; its floor, rather than a blanket quarter plateau, shapes the gaps. */
    public static boolean occupied(final int[] quarter, final List<Area> floors) {
        final Area q = new Area(quarter[0], quarter[1], quarter[2], quarter[3], 0);
        return floors.stream().anyMatch(q::overlaps);
    }

    /**
     * Free quarters get a flat interior. Around kept buildings only the first outside column is fixed at their
     * real ground-floor level: the open gaps between different floors become ramps, without moving foundations.
     */
    public static List<Area> flats(final List<int[]> quarters, final int[] square, final List<Area> floors) {
        final List<Area> result = new ArrayList<>();
        for (final Area a : floors) {
            result.add(new Area(a.minX() - 1, a.minZ() - 1, a.maxX() + 1, a.maxZ() + 1, a.level()));
        }
        for (final int[] q : quarters) {
            if (q.length < 5 || occupied(q, floors)) {
                continue;
            }
            final boolean isSquare = square != null && q[0] == square[0] && q[1] == square[1]
                && q[2] == square[2] && q[3] == square[3];
            final int[] in = isSquare ? q : interior(q[0], q[1], q[2], q[3]);
            result.add(new Area(in[0], in[1], in[2], in[3], q[4]));
        }
        return List.copyOf(result);
    }

    /** The flat interior of a quarter: its bounds without the sloping band. */
    public static int[] interior(final int minX, final int minZ, final int maxX, final int maxZ) {
        final int b = Math.min(BAND, Math.max(0, (Math.min(maxX - minX, maxZ - minZ) - 2) / 2));
        return new int[] {minX + b, minZ + b, maxX - b, maxZ - b};
    }

    /**
     * Levels of the areas {@code rects[i] = {minX, minZ, maxX, maxZ}} (full quarter bounds; the square at
     * {@code fixedIndex}, -1 = none). Unknown terrain (more than a third of the samples) → {@link TerrainView#UNKNOWN}
     * for that area; too rough → UNKNOWN as well.
     */
    public static int[] levels(final List<int[]> rects, final int fixedIndex, final IntBinaryOperator height) {
        final int n = rects.size();
        final int[] median = new int[n];
        final int[] level = new int[n];
        final int[] lowest = new int[n];
        final int[] highest = new int[n];
        for (int i = 0; i < n; i++) {
            final int[] r = rects.get(i);
            final List<Integer> hs = new ArrayList<>();
            int unknown = 0;
            for (int x = r[0]; x <= r[2]; x++) {
                for (int z = r[1]; z <= r[3]; z++) {
                    final int h = height.applyAsInt(x, z);
                    if (h == TerrainView.UNKNOWN) {
                        unknown++;
                    } else {
                        hs.add(h);
                    }
                }
            }
            if (hs.isEmpty() || unknown * 2 > hs.size()) {
                median[i] = TerrainView.UNKNOWN;
            } else {
                java.util.Collections.sort(hs);
                median[i] = hs.get(hs.size() / 2);
                lowest[i] = hs.getFirst();
                highest[i] = hs.getLast();
            }
            level[i] = median[i];
        }
        // allowed difference between two areas: the slope over the gap and both bands
        final int[][] allowed = new int[n][n];
        final List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final Area a = new Area(rects.get(i)[0], rects.get(i)[1], rects.get(i)[2], rects.get(i)[3], 0);
                final Area b = new Area(rects.get(j)[0], rects.get(j)[1], rects.get(j)[2], rects.get(j)[3], 0);
                final int gap = a.gap(b);
                if (gap > 6) {
                    continue;
                }
                // overlapping areas (quarters reaching into the square) share the level; the square has no band
                final int bands = i == fixedIndex || j == fixedIndex ? BAND : 2 * BAND;
                allowed[i][j] = a.overlaps(b) ? 0 : Math.max(1, (gap + bands) / RUN);
                pairs.add(new int[] {i, j});
            }
        }
        for (int round = 0; round < 200; round++) {
            boolean changed = false;
            for (final int[] p : pairs) {
                final int i = p[0];
                final int j = p[1];
                if (level[i] == TerrainView.UNKNOWN || level[j] == TerrainView.UNKNOWN) {
                    continue;
                }
                final int diff = level[i] - level[j];
                if (Math.abs(diff) <= allowed[i][j]) {
                    continue;
                }
                final int hi = diff > 0 ? i : j;
                final int lo = diff > 0 ? j : i;
                if (hi == fixedIndex) {
                    level[lo]++;
                } else if (lo == fixedIndex) {
                    level[hi]--;
                } else if (Math.abs(diff) - allowed[i][j] >= 2) {
                    level[hi]--;
                    level[lo]++;
                } else if (Math.abs(level[hi] - median[hi]) <= Math.abs(level[lo] - median[lo])) {
                    level[hi]--;
                } else {
                    level[lo]++;
                }
                changed = true;
            }
            if (!changed) {
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            if (level[i] != TerrainView.UNKNOWN && (Math.abs(level[i] - median[i]) > MAX_CUT
                || level[i] - lowest[i] > MAX_CUT || highest[i] - level[i] > MAX_CUT)) {
                level[i] = TerrainView.UNKNOWN; // too much earthwork: the quarter stays natural
            }
        }
        return level;
    }

    /**
     * Target top heights of the columns of a region (inclusive bounds) — only columns of the graded domain with a
     * known natural height (key {@link RoadPlanner#key}).
     *
     * @param flats   flat interiors with their levels (all of the town: neighbours shape the slopes too)
     * @param domain  the column may be graded (inside the planned streets and quarters, own land, not a kept building)
     * @param core    the town itself (quarters and their streets): smooth ramps between the flat areas there; elsewhere in
     *                the domain (ramps at the edge of the town) as close to the natural terrain as the slope allows
     * @param anchor  a column outside the domain whose ground the slopes must meet (around kept buildings); any other
     *                column outside the domain only ends the graded area (a step against wild terrain stays at the
     *                edge of the town, never inside it)
     * @param natural the terrain as it is
     */
    public static Map<Long, Integer> targets(final int minX, final int minZ, final int maxX, final int maxZ, final List<Area> flats,
                                             final BiPredicate<Integer, Integer> domain, final BiPredicate<Integer, Integer> core,
                                             final BiPredicate<Integer, Integer> anchor, final IntBinaryOperator natural) {
        final int wx = minX - WINDOW;
        final int wz = minZ - WINDOW;
        final int sx = maxX - minX + 1 + 2 * WINDOW;
        final int sz = maxZ - minZ + 1 + 2 * WINDOW;
        final int inf = Integer.MAX_VALUE / 4;
        final int[] up = new int[sx * sz];
        final int[] down = new int[sx * sz];
        final int[] nat = new int[sx * sz];
        final int[] flat = new int[sx * sz];
        final boolean[] free = new boolean[sx * sz];
        final boolean[] fixed = new boolean[sx * sz];
        final boolean[] smooth = new boolean[sx * sz];
        Arrays.fill(up, inf);
        Arrays.fill(down, -inf);
        final List<Area> near = new ArrayList<>();
        for (final Area a : flats) {
            if (a.maxX() >= wx && a.minX() < wx + sx && a.maxZ() >= wz && a.minZ() < wz + sz) {
                near.add(a);
            }
        }
        for (int i = 0; i < sx; i++) {
            for (int k = 0; k < sz; k++) {
                final int x = wx + i;
                final int z = wz + k;
                final int idx = k * sx + i;
                nat[idx] = natural.applyAsInt(x, z);
                flat[idx] = TerrainView.UNKNOWN;
                for (final Area a : near) {
                    if (a.contains(x, z) && domain.test(x, z)) {
                        flat[idx] = a.level();
                        break;
                    }
                }
                if (flat[idx] != TerrainView.UNKNOWN) {
                    up[idx] = flat[idx] * RUN;
                    down[idx] = flat[idx] * RUN;
                    fixed[idx] = true;
                } else if (domain.test(x, z)) {
                    free[idx] = true;
                    smooth[idx] = core.test(x, z);
                } else if (nat[idx] != TerrainView.UNKNOWN && anchor.test(x, z)) {
                    up[idx] = nat[idx] * RUN;
                    down[idx] = nat[idx] * RUN;
                    fixed[idx] = true;
                }
            }
        }
        // envelopes of slope 1 : RUN (chamfer passes, 8-neighbourhood, cost 1 per column)
        for (int pass = 0; pass < 3; pass++) {
            for (int k = 0; k < sz; k++) {
                for (int i = 0; i < sx; i++) {
                    relax(up, down, sx, sz, i, k, -1, 0, -1, -1, 0, -1, 1, -1);
                }
            }
            for (int k = sz - 1; k >= 0; k--) {
                for (int i = sx - 1; i >= 0; i--) {
                    relax(up, down, sx, sz, i, k, 1, 0, 1, 1, 0, 1, -1, 1);
                }
            }
        }
        final Map<Long, Integer> out = new HashMap<>();
        final int[] target = new int[sx * sz];
        for (int idx = 0; idx < sx * sz; idx++) {
            if (flat[idx] != TerrainView.UNKNOWN) {
                target[idx] = flat[idx];
            } else if (free[idx] && nat[idx] != TerrainView.UNKNOWN) {
                final int u = up[idx];
                final int d = down[idx];
                if (u >= inf / 2 || d <= -inf / 2) {
                    target[idx] = nat[idx];
                } else if (d > u || smooth[idx]) {
                    target[idx] = Math.round((u + d) / (2f * RUN)); // between the anchors (or they disagree): halfway
                } else {
                    final int lo = Math.floorDiv(d + RUN - 1, RUN);
                    final int hi = Math.floorDiv(u, RUN);
                    target[idx] = lo > hi ? Math.round((u + d) / (2f * RUN)) : Math.max(lo, Math.min(hi, nat[idx]));
                }
            } else {
                target[idx] = nat[idx];
            }
        }
        // no step of more than one block between free columns and their neighbours (fill the low side first)
        for (int round = 0; round < 8; round++) {
            boolean changed = false;
            for (int k = 0; k < sz; k++) {
                for (int i = 0; i < sx; i++) {
                    final int idx = k * sx + i;
                    if (!free[idx] || nat[idx] == TerrainView.UNKNOWN) {
                        continue;
                    }
                    int t = target[idx];
                    for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        final int ni = i + d[0];
                        final int nk = k + d[1];
                        final int n = nk * sx + ni;
                        if (ni < 0 || nk < 0 || ni >= sx || nk >= sz || target[n] == TerrainView.UNKNOWN || !free[n] && !fixed[n]) {
                            continue;
                        }
                        t = Math.max(t, target[nk * sx + ni] - 1);
                    }
                    if (t != target[idx]) {
                        target[idx] = t;
                        changed = true;
                    }
                }
            }
            for (int k = 0; k < sz; k++) {
                for (int i = 0; i < sx; i++) {
                    final int idx = k * sx + i;
                    if (!free[idx] || nat[idx] == TerrainView.UNKNOWN) {
                        continue;
                    }
                    int t = target[idx];
                    for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        final int ni = i + d[0];
                        final int nk = k + d[1];
                        final int n = nk * sx + ni;
                        if (ni < 0 || nk < 0 || ni >= sx || nk >= sz || target[n] == TerrainView.UNKNOWN || !free[n] && !fixed[n]) {
                            continue;
                        }
                        t = Math.min(t, target[nk * sx + ni] + 1);
                    }
                    if (t != target[idx]) {
                        target[idx] = t;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final int idx = (z - wz) * sx + (x - wx);
                if ((free[idx] || flat[idx] != TerrainView.UNKNOWN) && nat[idx] != TerrainView.UNKNOWN) {
                    out.put(RoadPlanner.key(x, z), target[idx]);
                }
            }
        }
        return out;
    }

    /** One chamfer step: the cell takes the envelope of its already visited neighbours (offset pairs). */
    private static void relax(final int[] up, final int[] down, final int sx, final int sz, final int i, final int k,
                              final int... d) {
        final int idx = k * sx + i;
        int u = up[idx];
        int dn = down[idx];
        for (int p = 0; p + 1 < d.length; p += 2) {
            final int ni = i + d[p];
            final int nk = k + d[p + 1];
            if (ni < 0 || nk < 0 || ni >= sx || nk >= sz) {
                continue;
            }
            final int n = nk * sx + ni;
            u = Math.min(u, up[n] + 1);
            dn = Math.max(dn, down[n] - 1);
        }
        up[idx] = u;
        down[idx] = dn;
    }
}
