// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * Roads (pure, unit tested): A* over columns from the cell in front of a door to the nearest existing road cell or
 * the town centre. Steps of more than one block, water, foreign land and other buildings are not passable; slopes
 * cost extra so roads follow the terrain.
 */
public final class RoadPlanner {

    private RoadPlanner() {
    }

    public static long key(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(final long k) {
        return (int) (k >> 32);
    }

    public static int keyZ(final long k) {
        return (int) k;
    }

    private record Node(long key, double f) {
    }

    private static boolean blocked(final TerrainView view, final int x, final int z, final List<BlockBox> obstacles) {
        if (!view.own(x, z) || view.liquid(x, z) || view.obstacle(x, z) || view.height(x, z) == TerrainView.UNKNOWN) {
            return true;
        }
        for (final BlockBox b : obstacles) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Path of columns {x, z} from (sx, sz) to a goal (any of {@code roads}, or the centre); empty when unreachable
     * within {@code maxNodes} expansions. The start itself is included; a start already on a road gives [start].
     */
    public static List<int[]> path(final TerrainView view, final int sx, final int sz, final Set<Long> roads,
                                   final int centreX, final int centreZ, final List<BlockBox> obstacles, final int maxNodes) {
        final long start = key(sx, sz);
        final long centre = key(centreX, centreZ);
        if (roads.contains(start) || start == centre) {
            return List.of(new int[] {sx, sz});
        }
        if (blocked(view, sx, sz, obstacles)) {
            return List.of();
        }
        final Map<Long, Double> g = new HashMap<>();
        final Map<Long, Long> parent = new HashMap<>();
        final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        g.put(start, 0.0);
        open.add(new Node(start, heuristic(sx, sz, centreX, centreZ)));
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            final Node cur = open.poll();
            final long k = cur.key();
            final int x = keyX(k);
            final int z = keyZ(k);
            if (roads.contains(k) || k == centre) {
                return rebuild(parent, k);
            }
            expanded++;
            final double gk = g.get(k);
            final int h = view.height(x, z);
            for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int nx = x + dir[0];
                final int nz = z + dir[1];
                final long nk = key(nx, nz);
                final boolean goal = roads.contains(nk) || nk == centre;
                if (!goal && blocked(view, nx, nz, obstacles)) {
                    continue;
                }
                final int nh = view.height(nx, nz);
                final int rise = nh == TerrainView.UNKNOWN ? 0 : Math.abs(nh - h);
                if (rise > 1 && !goal) {
                    continue;
                }
                final double cost = gk + 1 + rise * 2.0;
                if (cost < g.getOrDefault(nk, Double.MAX_VALUE)) {
                    g.put(nk, cost);
                    parent.put(nk, k);
                    open.add(new Node(nk, cost + heuristic(nx, nz, centreX, centreZ)));
                }
            }
        }
        return List.of();
    }

    private static double heuristic(final int x, final int z, final int cx, final int cz) {
        return Math.abs(x - cx) + Math.abs(z - cz);
    }

    private static List<int[]> rebuild(final Map<Long, Long> parent, final long end) {
        final List<int[]> out = new ArrayList<>();
        Long k = end;
        while (k != null) {
            out.add(new int[] {keyX(k), keyZ(k)});
            k = parent.get(k);
        }
        Collections.reverse(out);
        return out;
    }
}
