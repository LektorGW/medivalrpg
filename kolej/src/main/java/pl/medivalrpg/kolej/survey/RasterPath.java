// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.survey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import pl.medivalrpg.kolej.geom.Polyline;
import pl.medivalrpg.kolej.geom.Vec3;

/**
 * Turns an 8-connected grid path into a smooth, 4-connected chain of rail cells (vanilla rails only connect to the
 * four horizontal neighbours; a diagonal is a staircase). The corners of the grid path are smoothed by Chaikin corner
 * cutting and the curve is rasterised again; if the smooth curve would touch a forbidden cell the plain path is used.
 */
public final class RasterPath {

    private RasterPath() {
    }

    /**
     * @param path    8-connected cells as {@code {x, z}}
     * @param allowed cell filter (known, not blocked)
     * @return 4-connected cells {@code {x, z}} from the first to the last cell of {@code path}
     */
    public static List<int[]> smooth(final List<int[]> path, final BiPredicate<Integer, Integer> allowed) {
        if (path.size() < 3) {
            return fourConnected(path, allowed);
        }
        final List<Vec3> corners = new ArrayList<>();
        corners.add(center(path.getFirst()));
        for (int i = 1; i < path.size() - 1; i++) {
            final int[] a = path.get(i - 1);
            final int[] b = path.get(i);
            final int[] c = path.get(i + 1);
            if (b[0] - a[0] != c[0] - b[0] || b[1] - a[1] != c[1] - b[1]) {
                corners.add(center(b));
            }
        }
        corners.add(center(path.getLast()));
        final Polyline curve = new Polyline(Polyline.chaikin(corners, 3));
        final List<int[]> out = new ArrayList<>();
        out.add(path.getFirst().clone());
        final double step = 0.2;
        for (double s = step; s <= curve.length() + step; s += step) {
            final Vec3 p = curve.pointAt(Math.min(s, curve.length()));
            final int cx = (int) Math.floor(p.x());
            final int cz = (int) Math.floor(p.z());
            final int[] last = out.getLast();
            if (cx == last[0] && cz == last[1]) {
                continue;
            }
            final int dx = Integer.signum(cx - last[0]);
            final int dz = Integer.signum(cz - last[1]);
            if (Math.abs(cx - last[0]) > 1 || Math.abs(cz - last[1]) > 1) {
                return fourConnected(path, allowed);
            }
            if (dx != 0 && dz != 0) {
                // diagonal jump: insert the in-between cell whose centre is closer to the curve point
                final int[] optA = {last[0] + dx, last[1]};
                final int[] optB = {last[0], last[1] + dz};
                final double da = dist2(center(optA), p);
                final double db = dist2(center(optB), p);
                out.add(da <= db ? optA : optB);
            }
            out.add(new int[] {cx, cz});
        }
        final int[] end = path.getLast();
        if (out.getLast()[0] != end[0] || out.getLast()[1] != end[1]) {
            return fourConnected(path, allowed);
        }
        if (!valid(out, allowed)) {
            return fourConnected(path, allowed);
        }
        return out;
    }

    /** Plain conversion: every diagonal step gets an in-between cell (the allowed one, preferring x first). */
    public static List<int[]> fourConnected(final List<int[]> path, final BiPredicate<Integer, Integer> allowed) {
        final List<int[]> out = new ArrayList<>();
        for (final int[] c : path) {
            if (!out.isEmpty()) {
                final int[] last = out.getLast();
                final int dx = c[0] - last[0];
                final int dz = c[1] - last[1];
                if (dx != 0 && dz != 0) {
                    final int[] optA = {last[0] + dx, last[1]};
                    final int[] optB = {last[0], last[1] + dz};
                    out.add(allowed.test(optA[0], optA[1]) || !allowed.test(optB[0], optB[1]) ? optA : optB);
                }
            }
            out.add(c.clone());
        }
        return out;
    }

    /** No repeated cells, 4-connected, every cell allowed. */
    public static boolean valid(final List<int[]> cells, final BiPredicate<Integer, Integer> allowed) {
        final Set<Long> seen = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            final int[] c = cells.get(i);
            if (!allowed.test(c[0], c[1]) || !seen.add(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL))) {
                return false;
            }
            if (i > 0) {
                final int[] p = cells.get(i - 1);
                if (Math.abs(c[0] - p[0]) + Math.abs(c[1] - p[1]) != 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Vec3 center(final int[] c) {
        return new Vec3(c[0] + 0.5, 0, c[1] + 0.5);
    }

    private static double dist2(final Vec3 a, final Vec3 b) {
        final double dx = a.x() - b.x();
        final double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /**
     * Moves height steps off curved cells: the cell holding an ascending rail (the lower cell of a step) must have its
     * two neighbours in line with it, otherwise vanilla rails cannot slope there. A step on a curve is shifted to the
     * nearest straight spot within {@code radius} cells (heights in between change by one block). Heights are modified
     * in place; returns the number of steps that could not be fixed.
     */
    public static int fixSteps(final List<int[]> cells, final int[] heights, final int radius) {
        int unfixed = 0;
        final int n = cells.size();
        for (int i = 0; i + 1 < n; i++) {
            if (heights[i + 1] == heights[i]) {
                continue;
            }
            final boolean up = heights[i + 1] > heights[i];
            if (straightAround(cells, up ? i : i + 1)) {
                continue;
            }
            boolean fixed = false;
            for (int r = 1; r <= radius && !fixed; r++) {
                for (final int j : new int[] {i + r, i - r}) {
                    if (j < 0 || j + 1 >= n || !straightAround(cells, up ? j : j + 1)) {
                        continue;
                    }
                    final int lo = Math.min(i, j);
                    final int hi = Math.max(i, j);
                    boolean otherStep = false;
                    for (int k = lo; k <= hi; k++) {
                        if (k != i && k + 1 < n && heights[k + 1] != heights[k]) {
                            otherStep = true;
                            break;
                        }
                    }
                    if (otherStep) {
                        continue;
                    }
                    if (j > i) {
                        for (int k = i + 1; k <= j; k++) {
                            heights[k] = heights[i];
                        }
                    } else {
                        for (int k = j + 1; k <= i; k++) {
                            heights[k] = heights[i + 1];
                        }
                    }
                    fixed = true;
                    break;
                }
            }
            if (!fixed) {
                unfixed++;
            }
        }
        return unfixed;
    }

    /** True when cell {@code k} and its neighbours lie on a straight line (ends of the chain count as straight). */
    public static boolean straightAround(final List<int[]> cells, final int k) {
        if (k <= 0 || k >= cells.size() - 1) {
            return true;
        }
        final int[] a = cells.get(k - 1);
        final int[] b = cells.get(k);
        final int[] c = cells.get(k + 1);
        return b[0] - a[0] == c[0] - b[0] && b[1] - a[1] == c[1] - b[1];
    }
}
