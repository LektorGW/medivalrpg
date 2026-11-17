// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Levelling of a building plot instead of foundation pillars and trenches (pure, unit tested).
 *
 * <ul>
 *   <li>the plot (footprint + margin) gets its top ground block at the floor height {@code floorY} (the building's
 *       floor replaces that block: the threshold is level with the ground, the foundation stays hidden);</li>
 *   <li>around it the terrain is blended with slopes of at most one block per block (45°) — fill or cut — up to
 *       {@code maxRamp} blocks away: new height = clamp(old, floorY − d, floorY + d), d = distance to the plot, so
 *       neighbouring columns never differ by more than one;</li>
 *   <li>where the graded area meets untouched terrain with a step of more than two blocks, the outermost graded
 *       column becomes a retaining wall (terrace) built of stone, at most {@code maxWall} blocks of face.</li>
 * </ul>
 * Order: plot columns first, then the rings outwards (builders work from the plot to the edge).
 */
public final class Terraform {

    /**
     * New state of one column.
     *
     * @param oldTop     top ground block before
     * @param newTop     top ground block after (cut above it, fill up to it; for a wall column the top of the wall)
     * @param wallBottom first block of a retaining wall in this column ({@code Integer.MAX_VALUE} = no wall)
     * @param wallTop    last block of the retaining wall
     * @param plot       inside the levelled plot
     */
    public record Column(int x, int z, int oldTop, int newTop, int wallBottom, int wallTop, boolean plot) {

        public boolean wall() {
            return this.wallBottom <= this.wallTop;
        }

        public boolean changed() {
            return this.oldTop != this.newTop || this.wall();
        }
    }

    /** Plan: plot columns first, then the changed ring columns; {@code maxStep} = largest earth step left. */
    public record Plan(List<Column> columns, int wallColumns, int maxStep) {

        public Map<Long, Column> byKey() {
            final Map<Long, Column> m = new HashMap<>();
            for (final Column c : this.columns) {
                m.put(RoadPlanner.key(c.x(), c.z()), c);
            }
            return m;
        }
    }

    /**
     * Settings.
     *
     * @param margin  levelled ring around the footprint
     * @param maxRamp how far the slopes may reach beyond the plot
     * @param maxWall max exposed height of a retaining wall
     */
    public record Settings(int margin, int maxRamp, int maxWall) {
    }

    private Terraform() {
    }

    /**
     * @param height original top ground block per column ({@link TerrainView#UNKNOWN} → treated as {@code floorY})
     * @param minX   footprint (template box) min x … max z
     */
    public static Plan plan(final IntBinaryOperator height, final int minX, final int minZ, final int maxX, final int maxZ,
                            final int floorY, final Settings s) {
        return plan(height, (x, z) -> true, minX, minZ, maxX, maxZ, floorY, s, false);
    }

    /**
     * {@code may}: columns the builders may grade (own land, not protected); the others keep their height and count as
     * untouched terrain. {@code natural} (2026-11-03): natural embankments — every graded column next to untouched
     * terrain (outer ring or a protected column) with a step of 2+ blocks becomes a face (the last resort; earth for
     * players, a low stone wall for NPC, no fences), elsewhere the slope is at most one block per block.
     */
    public static Plan plan(final IntBinaryOperator height, final java.util.function.BiPredicate<Integer, Integer> may,
                            final int minX, final int minZ, final int maxX, final int maxZ, final int floorY, final Settings s,
                            final boolean natural) {
        final int pMinX = minX - s.margin();
        final int pMinZ = minZ - s.margin();
        final int pMaxX = maxX + s.margin();
        final int pMaxZ = maxZ + s.margin();
        final List<Column> out = new ArrayList<>();
        final Map<Long, Integer> tops = new HashMap<>();
        for (int z = pMinZ; z <= pMaxZ; z++) {
            for (int x = pMinX; x <= pMaxX; x++) {
                out.add(new Column(x, z, h(height, x, z, floorY), floorY, Integer.MAX_VALUE, Integer.MIN_VALUE, true));
                tops.put(RoadPlanner.key(x, z), floorY);
            }
        }
        final List<Column> ring = new ArrayList<>();
        for (int d = 1; d <= s.maxRamp(); d++) {
            for (int z = pMinZ - d; z <= pMaxZ + d; z++) {
                for (int x = pMinX - d; x <= pMaxX + d; x++) {
                    if (ringOf(x, z, pMinX, pMinZ, pMaxX, pMaxZ) != d) {
                        continue;
                    }
                    if (natural && !may.test(x, z)) {
                        continue; // protected / foreign: untouched terrain, the slope keeps clear of it
                    }
                    final int h = h(height, x, z, floorY);
                    final int top = Math.max(floorY - d, Math.min(floorY + d, h));
                    tops.put(RoadPlanner.key(x, z), top);
                    ring.add(new Column(x, z, h, top, Integer.MAX_VALUE, Integer.MIN_VALUE, false));
                }
            }
        }
        // natural steps inside the graded area: fill low columns next to high ones, then cut high ones (plot is fixed),
        // until every pair of neighbours differs by at most one
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int iteration = 0; iteration < 4 * s.maxRamp() + 8; iteration++) {
            boolean changed = false;
            for (final boolean raise : new boolean[] {true, false}) {
                for (final Column c : ring) {
                    final long key = RoadPlanner.key(c.x(), c.z());
                    int top = tops.get(key);
                    for (final int[] dir : dirs) {
                        final Integer n = tops.get(RoadPlanner.key(c.x() + dir[0], c.z() + dir[1]));
                        if (n == null) {
                            continue;
                        }
                        top = raise ? Math.max(top, n - 1) : Math.min(top, n + 1);
                    }
                    if (top != tops.get(key)) {
                        tops.put(key, top);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int i = 0; i < ring.size(); i++) {
            final Column c = ring.get(i);
            ring.set(i, new Column(c.x(), c.z(), c.oldTop(), tops.get(RoadPlanner.key(c.x(), c.z())), c.wallBottom(),
                c.wallTop(), false));
        }
        int walls = 0;
        for (final Column c : ring) {
            Column col = c;
            if ((natural || ringOf(c.x(), c.z(), pMinX, pMinZ, pMaxX, pMaxZ) == s.maxRamp()) && c.changed()) {
                // meeting point with untouched terrain: retaining wall instead of an earth face
                int up = c.newTop();
                int down = c.newTop();
                for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    final int nx = c.x() + dir[0];
                    final int nz = c.z() + dir[1];
                    if (tops.containsKey(RoadPlanner.key(nx, nz))) {
                        continue;
                    }
                    final int hn = h(height, nx, nz, floorY);
                    up = Math.max(up, hn);
                    down = Math.min(down, hn);
                }
                final int step = natural ? 1 : 2;
                if (up - c.newTop() > step) {
                    // hill behind: wall from the graded top up to the terrain (holds the earth back)
                    final int wt = Math.min(up, c.newTop() + s.maxWall());
                    col = new Column(c.x(), c.z(), c.oldTop(), wt, c.newTop() + 1, wt, false);
                    walls++;
                } else if (c.newTop() - down > step) {
                    // drop in front: the fill of this column is a wall face
                    final int wb = Math.max(down + 1, c.newTop() - s.maxWall() + 1);
                    col = new Column(c.x(), c.z(), c.oldTop(), c.newTop(), wb, c.newTop(), false);
                    walls++;
                }
            }
            if (col.changed()) {
                out.add(col);
            }
        }
        return new Plan(List.copyOf(out), walls, maxStep(out, height, floorY));
    }

    private static int h(final IntBinaryOperator height, final int x, final int z, final int floorY) {
        final int v = height.applyAsInt(x, z);
        return v == TerrainView.UNKNOWN ? floorY : v;
    }

    /** Chebyshev distance of (x, z) to the rectangle (0 inside). */
    static int ringOf(final int x, final int z, final int minX, final int minZ, final int maxX, final int maxZ) {
        final int dx = x < minX ? minX - x : x > maxX ? x - maxX : 0;
        final int dz = z < minZ ? minZ - z : z > maxZ ? z - maxZ : 0;
        return Math.max(dx, dz);
    }

    /** Largest earth step between two neighbouring columns, ignoring wall columns (incl. untouched neighbours). */
    static int maxStep(final List<Column> cols, final IntBinaryOperator height, final int floorY) {
        final Map<Long, Column> m = new HashMap<>();
        for (final Column c : cols) {
            m.put(RoadPlanner.key(c.x(), c.z()), c);
        }
        int max = 0;
        for (final Column c : cols) {
            if (c.wall()) {
                continue;
            }
            for (final int[] dir : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                final Column n = m.get(RoadPlanner.key(c.x() + dir[0], c.z() + dir[1]));
                if (n != null && n.wall()) {
                    continue;
                }
                final int nt = n != null ? n.newTop() : h(height, c.x() + dir[0], c.z() + dir[1], floorY);
                max = Math.max(max, Math.abs(nt - c.newTop()));
            }
        }
        return max;
    }
}
