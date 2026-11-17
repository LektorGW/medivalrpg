// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * Where city walls run (pure, unit tested). Around the built-up area: the convex hull of the buildings (fields
 * included) grown by a margin and walked as a closed line of cells; each cell may move up to two blocks outwards or
 * inwards to the highest ground (ridges) while staying clear of buildings; cells where the terrain defends itself —
 * water (river, lake) or a cliff of four blocks and more — are left out; cells on foreign land are pulled back onto
 * the country's own land (a lost chunk: the ring closes along the new border). The ring is cut into modules ordered by
 * threat: towards foreign land and enemies first. Also the border line of the whole country ({@code follow-border}).
 */
public final class WallRoute {

    /** Bound work before rasterisation; distant colonies must never allocate an ocean-spanning ring. */
    public static final int MAX_RASTER_CELLS = 16_384;

    private WallRoute() {
    }

    /** Convex hull (Andrew's monotone chain) of points {x, z}, counter-clockwise, no duplicates. */
    public static List<int[]> hull(final List<int[]> points) {
        final List<int[]> pts = new ArrayList<>(points);
        pts.sort(Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        if (pts.size() < 3) {
            return pts;
        }
        final List<int[]> lower = new ArrayList<>();
        for (final int[] p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }
        final List<int[]> upper = new ArrayList<>();
        for (int i = pts.size() - 1; i >= 0; i--) {
            final int[] p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static long cross(final int[] o, final int[] a, final int[] b) {
        return (long) (a[0] - o[0]) * (b[1] - o[1]) - (long) (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Polygon corners of the town: the hull of the boxes' corners pushed out by {@code margin} (octagon-like). */
    public static List<int[]> outline(final List<BlockBox> town, final int margin) {
        final List<int[]> pts = new ArrayList<>();
        for (final BlockBox b : town) {
            pts.add(new int[] {b.minX() - margin, b.minZ()});
            pts.add(new int[] {b.minX(), b.minZ() - margin});
            pts.add(new int[] {b.maxX() + margin, b.minZ()});
            pts.add(new int[] {b.maxX(), b.minZ() - margin});
            pts.add(new int[] {b.minX() - margin, b.maxZ()});
            pts.add(new int[] {b.minX(), b.maxZ() + margin});
            pts.add(new int[] {b.maxX() + margin, b.maxZ()});
            pts.add(new int[] {b.maxX(), b.maxZ() + margin});
        }
        return hull(pts);
    }

    /** A closed polyline rasterised into 4-connected cells (each once, in order). */
    public static List<int[]> rasterise(final List<int[]> corners, final boolean closed) {
        long length = 0;
        for (int i = 0; i < (closed ? corners.size() : corners.size() - 1); i++) {
            final int[] a = corners.get(i), b = corners.get((i + 1) % corners.size());
            length += Math.abs((long)b[0] - a[0]) + Math.abs((long)b[1] - a[1]) + 1;
            if (length > MAX_RASTER_CELLS) return List.of();
        }
        final List<int[]> out = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        final int n = corners.size();
        for (int i = 0; i < (closed ? n : n - 1); i++) {
            final int[] a = corners.get(i);
            final int[] b = corners.get((i + 1) % n);
            int x = a[0];
            int z = a[1];
            final int dx = Math.abs(b[0] - x);
            final int dz = Math.abs(b[1] - z);
            final int sx = Integer.signum(b[0] - x);
            final int sz = Integer.signum(b[1] - z);
            int err = dx - dz;
            while (true) {
                if (seen.add(RoadPlanner.key(x, z))) {
                    out.add(new int[] {x, z});
                }
                if (x == b[0] && z == b[1]) {
                    break;
                }
                // 4-connected: move along one axis per step
                if (2 * err > -dz && (x != b[0])) {
                    err -= dz;
                    x += sx;
                } else {
                    err += dx;
                    z += sz;
                }
            }
        }
        return out;
    }

    /** Independent city walls on each claimed island; the empty sea between them is never rasterised. */
    public static List<int[]> rings(final List<BlockBox> town, final Set<Long> claims, final int margin,
                                    final TerrainView view, final BiPredicate<Integer, Integer> blocked) {
        final java.util.Map<Long, Integer> component = new java.util.HashMap<>();
        int next = 0;
        for (final long seed : claims) {
            if (component.containsKey(seed)) continue;
            final int id = next++;
            final java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(seed); component.put(seed, id);
            while (!queue.isEmpty()) {
                final long key = queue.removeFirst();
                final int x = (int)(key >> 32), z = (int)key;
                for (final long neighbour : new long[] {LandPicker.key(x-1,z),LandPicker.key(x+1,z),
                    LandPicker.key(x,z-1),LandPicker.key(x,z+1)}) {
                    if (claims.contains(neighbour) && !component.containsKey(neighbour)) {
                        component.put(neighbour,id); queue.addLast(neighbour);
                    }
                }
            }
        }
        final java.util.Map<Integer,List<BlockBox>> towns = new java.util.LinkedHashMap<>();
        for (final BlockBox b : town) {
            final Integer id = component.get(LandPicker.key(((int)(((long)b.minX()+b.maxX())/2))>>4,
                ((int)(((long)b.minZ()+b.maxZ())/2))>>4));
            if (id != null) towns.computeIfAbsent(id,ignored->new ArrayList<>()).add(b);
        }
        final List<int[]> out = new ArrayList<>();
        for (final List<BlockBox> island : towns.values()) out.addAll(ring(island,margin,view,blocked));
        return out;
    }

    /**
     * The ring of a town: outline → cells, shifted onto ridges and clear of buildings, natural defences left out,
     * pulled back onto own land.
     *
     * @param height  terrain top per column ({@link TerrainView#UNKNOWN} = unknown)
     * @param liquid  water / lava at the surface
     * @param own     the column is the country's
     * @param blocked a building / field / plot stands there
     */
    public static List<int[]> ring(final List<BlockBox> town, final int margin, final TerrainView view, final BiPredicate<Integer, Integer> blocked) {
        final List<int[]> corners = outline(town, margin);
        if (corners.size() < 3) {
            return List.of();
        }
        // centre of the town (for the normals)
        double cx = 0;
        double cz = 0;
        for (final int[] c : corners) {
            cx += c[0];
            cz += c[1];
        }
        cx /= corners.size();
        cz /= corners.size();
        // a shift along a diagonal normal or a skipped cell leaves a one-block hole: close the small ones
        return closeGaps(route(corners, cx, cz, view, blocked), GAP_CLOSE, view, blocked, cx, cz, true);
    }

    /** The ring cells before the holes are closed (the planning of versions up to 2026-10-30). */
    static List<int[]> route(final List<int[]> corners, final double cx, final double cz, final TerrainView view,
                             final BiPredicate<Integer, Integer> blocked) {
        final List<int[]> line = rasterise(corners, true);
        final List<int[]> out = new ArrayList<>();
        final Set<Long> seen = new LinkedHashSet<>();
        int lastShift = 0;
        for (final int[] c : line) {
            final double nx = c[0] - cx;
            final double nz = c[1] - cz;
            final double len = Math.max(1e-9, Math.hypot(nx, nz));
            final int ux = (int) Math.round(nx / len);
            final int uz = (int) Math.round(nz / len);
            // the best shift (−2..+2 along the outward normal, at most one more than the previous cell's: no gaps)
            int best = Integer.MIN_VALUE;
            int bestH = Integer.MIN_VALUE;
            for (int s = Math.max(-2, lastShift - 1); s <= Math.min(2, lastShift + 1); s++) {
                final int x = c[0] + ux * s;
                final int z = c[1] + uz * s;
                if (!view.own(x, z) || blocked.test(x, z)) {
                    continue;
                }
                final int h = view.height(x, z);
                if (h == TerrainView.UNKNOWN) {
                    continue;
                }
                if (h > bestH || h == bestH && Math.abs(s) < Math.abs(best)) {
                    bestH = h;
                    best = s;
                }
            }
            int[] cell;
            if (best == Integer.MIN_VALUE) {
                // foreign land / no room: pull inwards onto own free land (the ring closes along the border)
                cell = null;
                for (int s = 3; s <= 16 && cell == null; s++) {
                    final int x = c[0] - ux * s;
                    final int z = c[1] - uz * s;
                    if (view.own(x, z) && !blocked.test(x, z) && view.height(x, z) != TerrainView.UNKNOWN) {
                        cell = new int[] {x, z};
                    }
                }
                if (cell == null) {
                    continue;
                }
            } else {
                lastShift = best;
                cell = new int[] {c[0] + ux * best, c[1] + uz * best};
            }
            if (naturalDefence(view, cell[0], cell[1], ux, uz)) {
                continue;
            }
            if (seen.add(RoadPlanner.key(cell[0], cell[1]))) {
                out.add(cell);
            }
        }
        return out;
    }

    /** Widest hole (blocks) of a ring closed by joining cells; wider breaks are rivers, cliffs or foreign land. */
    public static final int GAP_CLOSE = 4;

    /** Water at the wall's place or right in front of it, or a cliff of 4+ blocks down outwards: no wall needed. */
    public static boolean naturalDefence(final TerrainView view, final int x, final int z, final int ux, final int uz) {
        if (view.liquid(x, z) || view.liquid(x + ux, z + uz) && view.liquid(x + 2 * ux, z + 2 * uz)) {
            return true;
        }
        final int h = view.height(x, z);
        final int out = view.height(x + 2 * ux, z + 2 * uz);
        return h != TerrainView.UNKNOWN && out != TerrainView.UNKNOWN && h - out >= 4;
    }

    /**
     * The line just inside the country's border ({@code follow-border}): cells {@code inset} blocks inside every chunk edge
     * that touches foreign land, in chain order.
     */
    public static List<int[]> border(final Set<Long> claims, final int inset) {
        final Set<Long> cells = new LinkedHashSet<>();
        final int in = Math.max(0, Math.min(7, inset));
        for (final long k : claims) {
            final int cx = (int) (k >> 32);
            final int cz = (int) k;
            final boolean n = !claims.contains(LandPicker.key(cx, cz - 1));
            final boolean s = !claims.contains(LandPicker.key(cx, cz + 1));
            final boolean w = !claims.contains(LandPicker.key(cx - 1, cz));
            final boolean e = !claims.contains(LandPicker.key(cx + 1, cz));
            for (int i = 0; i < 16; i++) {
                if (n) {
                    cells.add(RoadPlanner.key(cx * 16 + i, cz * 16 + in));
                }
                if (s) {
                    cells.add(RoadPlanner.key(cx * 16 + i, cz * 16 + 15 - in));
                }
                if (w) {
                    cells.add(RoadPlanner.key(cx * 16 + in, cz * 16 + i));
                }
                if (e) {
                    cells.add(RoadPlanner.key(cx * 16 + 15 - in, cz * 16 + i));
                }
            }
        }
        return RoadDecor.chains(cells);
    }

    /**
     * Modules of a ring {from, to} ordered by threat: modules near foreign land (within {@code reach}) first, those
     * facing the enemy ({@code enemyX/Z}, may be null) before them. A module never spans a break of the ring (a river,
     * a cliff, the sea between two islands): one module is one continuous stretch, so its building site only needs the
     * few chunks it stands in (a module across a break used to demand every chunk of its bounding box and starved).
     */
    public static List<int[]> modules(final List<int[]> ring, final int size, final BiPredicate<Integer, Integer> foreign, final int reach,
                                      final int[] enemy) {
        final List<int[]> mods = new ArrayList<>();
        int from = 0;
        for (int i = 1; i <= ring.size(); i++) {
            if (i == ring.size() || i - from >= size || !touching(ring.get(i - 1), ring.get(i))) {
                if (i > from) {
                    mods.add(new int[] {from, i});
                }
                from = i;
            }
        }
        mods.sort(Comparator.comparingDouble(m -> threat(ring, m, foreign, reach, enemy)));
        return mods;
    }

    /** Two cells touch (side or corner): no block of the wall is missing between them. */
    public static boolean touching(final int[] a, final int[] b) {
        return Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[1] - b[1]) <= 1;
    }

    /**
     * The first continuous stretch (cells touching one another) of at most {@code max} cells at the head of a list of
     * cell keys: a building site is one stretch, never cells scattered around the town.
     */
    public static int headRun(final List<Long> keys, final int max) {
        if (keys.isEmpty()) {
            return 0;
        }
        int n = 1;
        while (n < keys.size() && n < max) {
            final long a = keys.get(n - 1);
            final long b = keys.get(n);
            if (!touching(new int[] {RoadPlanner.keyX(a), RoadPlanner.keyZ(a)}, new int[] {RoadPlanner.keyX(b), RoadPlanner.keyZ(b)})) {
                break;
            }
            n++;
        }
        return n;
    }

    /** Cells split into continuous stretches (in order). */
    public static List<List<int[]>> stretches(final List<int[]> cells) {
        final List<List<int[]>> out = new ArrayList<>();
        List<int[]> cur = null;
        for (final int[] c : cells) {
            if (cur == null || !touching(cur.get(cur.size() - 1), c)) {
                cur = new ArrayList<>();
                out.add(cur);
            }
            cur.add(c);
        }
        return out;
    }

    /**
     * Holes of a ring closed: consecutive cells up to {@code maxGap} blocks apart (a shift along a diagonal normal, a
     * cell dropped next to a building) are joined by the 4-connected cells between them when every such cell is own,
     * free, scanned land without a natural defence; wider breaks (rivers, cliffs, foreign land) stay open.
     *
     * @param closed the last cell is joined to the first too
     * @return the ring with the joining cells inserted in order
     */
    public static List<int[]> closeGaps(final List<int[]> ring, final int maxGap, final TerrainView view,
                                        final BiPredicate<Integer, Integer> blocked, final double cx, final double cz, final boolean closed) {
        final List<int[]> out = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        for (final int[] c : ring) {
            seen.add(RoadPlanner.key(c[0], c[1]));
        }
        final int n = ring.size();
        for (int i = 0; i < n; i++) {
            final int[] a = ring.get(i);
            out.add(a);
            if (i == n - 1 && !closed || n < 2) {
                continue;
            }
            out.addAll(joining(a, ring.get((i + 1) % n), maxGap, view, blocked, cx, cz, seen));
        }
        return out;
    }

    /**
     * A stored ring with its holes closed.
     *
     * @param cells the new ring (keys)
     * @param at    new index of every old index, {@code at[n]} = new size
     * @param holes new modules {from, to} of joining cells next to stretches no longer pending (already built)
     */
    public record Spliced(List<Long> cells, int[] at, List<int[]> holes) {
    }

    /**
     * Closes the holes of a stored ring (keys in order, closed) like {@link #closeGaps}. The centre for the normals is
     * the mean of the ring cells.
     *
     * @param pending old indices still to be built (their joining cells are built with them)
     */
    public static Spliced spliceGaps(final List<Long> ring, final boolean[] pending, final TerrainView view,
                                     final BiPredicate<Integer, Integer> blocked) {
        final int n = ring.size();
        double cx = 0;
        double cz = 0;
        for (final long key : ring) {
            cx += RoadPlanner.keyX(key);
            cz += RoadPlanner.keyZ(key);
        }
        cx /= Math.max(1, n);
        cz /= Math.max(1, n);
        final Set<Long> seen = new HashSet<>(ring);
        final List<Long> out = new ArrayList<>();
        final int[] at = new int[n + 1];
        final List<int[]> holes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            at[i] = out.size();
            final long a = ring.get(i);
            out.add(a);
            if (n < 2) {
                continue;
            }
            final long b = ring.get((i + 1) % n);
            final List<int[]> add = joining(new int[] {RoadPlanner.keyX(a), RoadPlanner.keyZ(a)},
                new int[] {RoadPlanner.keyX(b), RoadPlanner.keyZ(b)}, GAP_CLOSE, view, blocked, cx, cz, seen);
            if (!add.isEmpty() && (pending == null || i >= pending.length || !pending[i])) {
                holes.add(new int[] {out.size(), out.size() + add.size()});
            }
            for (final int[] c : add) {
                out.add(RoadPlanner.key(c[0], c[1]));
            }
        }
        at[n] = out.size();
        return new Spliced(out, at, holes);
    }

    /** Pending modules of the old ring moved onto the spliced ring (with the joining cells inside them), holes first. */
    public static List<int[]> remapModules(final List<int[]> modules, final Spliced s, final int oldSize) {
        final List<int[]> out = new ArrayList<>(s.holes());
        if (modules != null) {
            for (final int[] m : modules) {
                final int a = Math.max(0, Math.min(oldSize, m[0]));
                final int b = Math.max(0, Math.min(oldSize, m[1]));
                if (b > a) {
                    out.add(new int[] {s.at()[a], s.at()[b]});
                }
            }
        }
        return out;
    }

    /** The cells between two ring cells that close the hole, or none when the hole must stay (see {@link #closeGaps}). */
    public static List<int[]> joining(final int[] a, final int[] b, final int maxGap, final TerrainView view,
                                      final BiPredicate<Integer, Integer> blocked, final double cx, final double cz, final Set<Long> seen) {
        final int gap = Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
        if (gap <= 1 || gap > maxGap) {
            return List.of();
        }
        final List<int[]> path = rasterise(List.of(a, b), false);
        final List<int[]> add = new ArrayList<>();
        for (int k = 1; k < path.size() - 1; k++) {
            final int[] c = path.get(k);
            if (seen.contains(RoadPlanner.key(c[0], c[1]))) {
                continue;
            }
            final double nx = c[0] - cx;
            final double nz = c[1] - cz;
            final double len = Math.max(1e-9, Math.hypot(nx, nz));
            if (!view.own(c[0], c[1]) || blocked.test(c[0], c[1]) || view.height(c[0], c[1]) == TerrainView.UNKNOWN
                || naturalDefence(view, c[0], c[1], (int) Math.round(nx / len), (int) Math.round(nz / len))) {
                return List.of();
            }
            add.add(c);
        }
        for (final int[] c : add) {
            seen.add(RoadPlanner.key(c[0], c[1]));
        }
        return add;
    }

    private static double threat(final List<int[]> ring, final int[] m, final BiPredicate<Integer, Integer> foreign, final int reach,
                                 final int[] enemy) {
        final int[] mid = ring.get((m[0] + m[1] - 1) / 2);
        double score = 0;
        boolean near = false;
        for (int d = 4; d <= reach && !near; d += 4) {
            for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (foreign.test(mid[0] + dir[0] * d, mid[1] + dir[1] * d)) {
                    near = true;
                    break;
                }
            }
        }
        if (!near) {
            score += 1000;
        }
        if (enemy != null) {
            score += Math.hypot(mid[0] - enemy[0], mid[1] - enemy[1]) / 10.0;
        }
        return score;
    }

    /** Cells of the old ring that the new one no longer uses (farther than 3 blocks from it). */
    public static List<Long> obsolete(final List<Long> oldRing, final List<int[]> newRing) {
        final Set<Long> near = new HashSet<>();
        for (final int[] c : newRing) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    near.add(RoadPlanner.key(c[0] + dx, c[1] + dz));
                }
            }
        }
        final List<Long> out = new ArrayList<>();
        for (final long k : oldRing) {
            if (!near.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
