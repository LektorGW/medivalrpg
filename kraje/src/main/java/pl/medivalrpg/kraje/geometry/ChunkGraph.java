// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Connectivity helpers for chunk territories (4-neighbourhood).
 */
public final class ChunkGraph {

    private ChunkGraph() {
    }

    public static long[] neighbours(final long key) {
        final int x = ChunkKeys.x(key);
        final int z = ChunkKeys.z(key);
        return new long[] {
            ChunkKeys.key(x + 1, z),
            ChunkKeys.key(x - 1, z),
            ChunkKeys.key(x, z + 1),
            ChunkKeys.key(x, z - 1)
        };
    }

    /** Whether the chunk shares an edge with any chunk of the territory. */
    public static boolean isAdjacent(final Set<Long> territory, final long key) {
        for (final long n : neighbours(key)) {
            if (territory.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Whether all chunks form one 4-connected component (empty and single-chunk sets count as connected). */
    public static boolean isConnected(final Set<Long> territory) {
        if (territory.size() <= 1) {
            return true;
        }
        final long start = territory.iterator().next();
        return reachable(territory, start, null).size() == territory.size();
    }

    /**
     * Whether every chunk except {@code removed} is still reachable from {@code anchor} (BFS) once
     * {@code removed} is taken away. Removing the anchor itself is never allowed.
     */
    public static boolean staysConnectedWithout(final Set<Long> territory, final long removed, final long anchor) {
        if (!territory.contains(removed)) {
            return isConnected(territory);
        }
        if (removed == anchor || !territory.contains(anchor)) {
            return false;
        }
        return reachable(territory, anchor, removed).size() == territory.size() - 1;
    }

    /** Number of 4-connected components. */
    public static int componentCount(final Set<Long> territory) {
        final Set<Long> seen = new HashSet<>();
        int components = 0;
        for (final long key : territory) {
            if (seen.contains(key)) {
                continue;
            }
            components++;
            seen.addAll(reachable(territory, key, null));
        }
        return components;
    }

    /**
     * Whether removing {@code removed} does not split the territory into more pieces than it has now
     * (for a normal, connected territory this means it stays connected).
     */
    public static boolean removalKeepsConnectivity(final Set<Long> territory, final long removed) {
        if (!territory.contains(removed)) {
            return true;
        }
        final Set<Long> after = new HashSet<>(territory);
        after.remove(removed);
        return componentCount(after) <= componentCount(territory);
    }

    /**
     * Whether removing {@code removed} from a territory keeps it usable: if the capital lies in this world and the
     * territory is connected, everything must stay reachable from the capital; if an admin already split it,
     * the removal may at least not create an additional split (so players never get stuck).
     *
     * @param capitalKey capital chunk key in this world, or {@code null} if the capital is in another world
     */
    public static boolean staysConnected(final Set<Long> territory, final long removed, final Long capitalKey) {
        if (capitalKey != null && isConnected(territory)) {
            return staysConnectedWithout(territory, removed, capitalKey);
        }
        return removalKeepsConnectivity(territory, removed);
    }

    private static Set<Long> reachable(final Set<Long> territory, final long start, final Long excluded) {
        final Set<Long> seen = new HashSet<>();
        final Deque<Long> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            final long current = queue.poll();
            for (final long n : neighbours(current)) {
                if ((excluded == null || n != excluded) && territory.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen;
    }
}
