// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/** High-road routing: normal terrain steps plus straight, bounded river crossings between reachable banks. */
public final class BridgeRoute {
    public static final int MAX_SPAN = 24;
    private record Node(long key, double score) { }
    private BridgeRoute() { }

    private static boolean clear(final TerrainView v, final int x, final int z, final List<BlockBox> obstacles, final Set<Long> roads) {
        if (!v.own(x, z) || v.obstacle(x, z) && !roads.contains(RoadPlanner.key(x, z)) || v.height(x, z) == TerrainView.UNKNOWN) {
            return false;
        }
        for (final BlockBox b : obstacles) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return false;
            }
        }
        return true;
    }

    public static List<int[]> path(final TerrainView v, final int sx, final int sz, final Set<Long> roads,
                                  final int gx, final int gz, final List<BlockBox> obstacles, final int maxNodes) {
        final long start = RoadPlanner.key(sx, sz);
        final long goal = RoadPlanner.key(gx, gz);
        final Map<Long, Double> costs = new HashMap<>();
        final Map<Long, Long> parents = new HashMap<>();
        final Set<Long> closed = new HashSet<>();
        final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.score(), b.score()));
        costs.put(start, 0.0);
        open.add(new Node(start, 0));
        while (!open.isEmpty() && closed.size() < maxNodes) {
            final long current = open.poll().key();
            if (!closed.add(current)) {
                continue;
            }
            if (current == goal || roads.contains(current)) {
                return rebuild(parents, current);
            }
            final int x = RoadPlanner.keyX(current);
            final int z = RoadPlanner.keyZ(current);
            for (final int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!clear(v, nx, nz, obstacles, roads)) {
                    continue;
                }
                int distance = 1;
                final int bankY = v.height(x, z);
                if (v.liquid(nx, nz)) {
                    // All three deck columns must fit inside the country and above the river surface.
                    boolean crossing = true;
                    while (distance <= MAX_SPAN && v.liquid(nx, nz)) {
                        for (int side = -1; side <= 1; side++) {
                            final int bx = nx + d[1] * side;
                            final int bz = nz + d[0] * side;
                            if (!clear(v, bx, bz, obstacles, roads) || v.height(bx, bz) >= bankY) {
                                crossing = false;
                            }
                        }
                        nx += d[0];
                        nz += d[1];
                        distance++;
                    }
                    if (!crossing || distance > MAX_SPAN + 1 || !clear(v, nx, nz, obstacles, roads) || v.liquid(nx, nz)) {
                        continue;
                    }
                }
                if (Math.abs(v.height(nx, nz) - bankY) > 1) {
                    continue;
                }
                if (distance > 1) {
                    final int deckY = Math.max(bankY, v.height(nx, nz));
                    boolean banksFit = true;
                    for (int side = -1; side <= 1; side++) {
                        for (final int[] bank : new int[][] {{x, z}, {nx, nz}}) {
                            final int bx = bank[0] + d[1] * side;
                            final int bz = bank[1] + d[0] * side;
                            if (!clear(v, bx, bz, obstacles, roads) || v.height(bx, bz) > deckY) {
                                banksFit = false;
                            }
                        }
                    }
                    if (!banksFit) {
                        continue;
                    }
                }
                final long next = RoadPlanner.key(nx, nz);
                final double cost = costs.get(current) + distance * (distance > 1 ? 4 : 1)
                    + Math.abs(v.height(nx, nz) - bankY) * 2;
                if (cost < costs.getOrDefault(next, Double.MAX_VALUE)) {
                    costs.put(next, cost);
                    parents.put(next, current);
                    // Dijkstra: the nearest existing road is a goal too, so centre-only heuristics are misleading.
                    open.add(new Node(next, cost));
                }
            }
        }
        return List.of();
    }

    private static List<int[]> rebuild(final Map<Long, Long> parents, final long end) {
        final List<Long> nodes = new ArrayList<>();
        Long at = end;
        while (at != null) {
            nodes.add(at);
            at = parents.get(at);
        }
        Collections.reverse(nodes);
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            final int x = RoadPlanner.keyX(nodes.get(i));
            final int z = RoadPlanner.keyZ(nodes.get(i));
            if (i > 0) {
                final int px = RoadPlanner.keyX(nodes.get(i - 1));
                final int pz = RoadPlanner.keyZ(nodes.get(i - 1));
                final int dx = Integer.signum(x - px);
                final int dz = Integer.signum(z - pz);
                for (int j = 1; j < Math.abs(x - px) + Math.abs(z - pz); j++) {
                    out.add(new int[] {px + j * dx, pz + j * dz});
                }
            }
            out.add(new int[] {x, z});
        }
        return out;
    }
}
