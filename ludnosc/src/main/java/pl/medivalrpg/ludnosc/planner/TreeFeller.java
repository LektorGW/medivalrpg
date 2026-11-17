// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Felling one tree (pure, unit tested): the trunk and branches (logs connected also diagonally, from the base up)
 * and the crown — every natural leaf that belonged to this tree (reachable through leaves within 6 blocks of its
 * logs) and is not held by a neighbouring tree. Nothing is left floating. Then a sapling of the same kind goes on the
 * base. Trees on foreign land are never touched (the caller passes {@code own}).
 */
public final class TreeFeller {

    /** What is at a position: {@link #LOG}, {@link #LEAF} (natural, decaying), {@link #OTHER}. */
    @FunctionalInterface
    public interface Blocks {
        int at(int x, int y, int z);
    }

    @FunctionalInterface
    public interface Own {
        boolean own(int x, int z);
    }

    public static final int OTHER = 0;
    public static final int LOG = 1;
    public static final int LEAF = 2;

    /** The plan: blocks to remove in order (leaves top-down, then logs top-down) and the sapling spot. */
    public record Plan(List<int[]> remove, int[] sapling, int logs, int leaves) {
    }

    private TreeFeller() {
    }

    private static long key(final int x, final int y, final int z) {
        return MineDigger.key(x, y, z);
    }

    /**
     * Plans felling the tree whose trunk contains (x, y, z). Null when it is not a tree (no leaves: a log house, a
     * fence post) or too big / partly on foreign land.
     */
    public static Plan plan(final Blocks w, final Own own, final int x, final int y, final int z, final int maxLogs) {
        if (w.at(x, y, z) != LOG) {
            return null;
        }
        // base: go down the trunk
        int by = y;
        while (w.at(x, by - 1, z) == LOG) {
            by--;
        }
        final Set<Long> logs = new LinkedHashSet<>();
        final List<int[]> logList = new ArrayList<>();
        final ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[] {x, by, z});
        logs.add(key(x, by, z));
        while (!q.isEmpty()) {
            final int[] c = q.poll();
            logList.add(c);
            if (logList.size() > maxLogs || !own.own(c[0], c[2])) {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        final int nx = c[0] + dx;
                        final int ny = c[1] + dy;
                        final int nz = c[2] + dz;
                        if (ny < by || w.at(nx, ny, nz) != LOG || !logs.add(key(nx, ny, nz))) {
                            continue;
                        }
                        q.add(new int[] {nx, ny, nz});
                    }
                }
            }
        }
        // crown: leaves reachable from the logs through leaves, at most 6 steps
        final Set<Long> leaves = new HashSet<>();
        final List<int[]> leafList = new ArrayList<>();
        final ArrayDeque<int[]> lq = new ArrayDeque<>();
        for (final int[] l : logList) {
            lq.add(new int[] {l[0], l[1], l[2], 0});
        }
        final int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!lq.isEmpty()) {
            final int[] c = lq.poll();
            if (c[3] >= 6) {
                continue;
            }
            for (final int[] d : n6) {
                final int nx = c[0] + d[0];
                final int ny = c[1] + d[1];
                final int nz = c[2] + d[2];
                if (w.at(nx, ny, nz) != LEAF || !leaves.add(key(nx, ny, nz))) {
                    continue;
                }
                if (heldByOtherTree(w, logs, nx, ny, nz)) {
                    continue; // the neighbour's crown: stays
                }
                if (!own.own(nx, nz)) {
                    continue; // never touch foreign land (the leaf decays by itself there)
                }
                leafList.add(new int[] {nx, ny, nz});
                lq.add(new int[] {nx, ny, nz, c[3] + 1});
            }
        }
        if (leafList.isEmpty()) {
            return null; // logs without leaves: a building, not a tree
        }
        final List<int[]> remove = new ArrayList<>();
        leafList.sort(Comparator.comparingInt((int[] c) -> -c[1]));
        logList.sort(Comparator.comparingInt((int[] c) -> -c[1]));
        remove.addAll(leafList);
        remove.addAll(logList);
        return new Plan(remove, new int[] {x, by, z}, logList.size(), leafList.size());
    }

    /** A log of another tree within 4 blocks keeps this leaf (it is part of that crown). */
    private static boolean heldByOtherTree(final Blocks w, final Set<Long> ours, final int x, final int y, final int z) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 4) {
                        continue;
                    }
                    if (w.at(x + dx, y + dy, z + dz) == LOG && !ours.contains(key(x + dx, y + dy, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Sapling block for a log type ({@code minecraft:spruce_log} → {@code minecraft:spruce_sapling}). */
    public static String sapling(final String log) {
        final String id = log.replace("minecraft:", "").replace("stripped_", "");
        if (id.startsWith("mangrove")) {
            return "minecraft:mangrove_propagule";
        }
        for (final String kind : new String[] {"spruce", "birch", "jungle", "acacia", "cherry"}) {
            if (id.startsWith(kind)) {
                return "minecraft:" + kind + "_sapling";
            }
        }
        return "minecraft:oak_sapling"; // oak; dark oak and pale oak need 2×2 saplings, an oak grows from one
    }
}
