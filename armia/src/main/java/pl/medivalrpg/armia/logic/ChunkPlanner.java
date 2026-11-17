// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Chunk geometry for the AI: where to expand and where to put border guards. Chunk keys use the KrajeApi format
 * {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)}. Pure.
 */
public final class ChunkPlanner {

    private ChunkPlanner() {
    }

    public static long key(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int cx(final long key) {
        return (int) (key >> 32);
    }

    public static int cz(final long key) {
        return (int) key;
    }

    public static long[] neighbors4(final long key) {
        final int x = cx(key);
        final int z = cz(key);
        return new long[] {key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)};
    }

    /**
     * Free chunks touching our land, best first: most own neighbours (compact shape), then closest to the capital.
     */
    public static List<Long> expansionCandidates(final Set<Long> own, final LongPredicate free, final int capitalX, final int capitalZ, final int limit) {
        final Map<Long, Integer> touching = new HashMap<>();
        for (final long k : own) {
            for (final long n : neighbors4(k)) {
                if (!own.contains(n) && free.test(n)) {
                    touching.merge(n, 1, Integer::sum);
                }
            }
        }
        final List<Long> out = new ArrayList<>(touching.keySet());
        out.sort(Comparator
            .comparing((Long k) -> -touching.get(k))
            .thenComparingLong(k -> distSq(k, capitalX, capitalZ))
            .thenComparingLong(k -> k));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    /**
     * Own chunks on the border, best first: facing an enemy, then facing another country, then facing wilderness.
     */
    public static List<Long> borderChunks(final Set<Long> own, final LongPredicate enemy, final LongPredicate foreign) {
        final Map<Long, Integer> priority = new HashMap<>();
        for (final long k : own) {
            int best = 0;
            for (final long n : neighbors4(k)) {
                if (own.contains(n)) {
                    continue;
                }
                final int p = enemy.test(n) ? 3 : foreign.test(n) ? 2 : 1;
                best = Math.max(best, p);
            }
            if (best > 0) {
                priority.put(k, best);
            }
        }
        final List<Long> out = new ArrayList<>(priority.keySet());
        out.sort(Comparator.comparing((Long k) -> -priority.get(k)).thenComparingLong(k -> k));
        return List.copyOf(out);
    }

    /** {@code n} guard posts spread over the border chunks (round robin over the priority order). */
    public static List<Long> guardPosts(final List<Long> border, final int n) {
        final List<Long> out = new ArrayList<>();
        if (border.isEmpty()) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(border.get(i % border.size()));
        }
        return out;
    }

    /** Enemy chunks touching our land (siege candidates), deduplicated. */
    public static List<Long> enemyFront(final Set<Long> own, final LongPredicate enemy) {
        final Set<Long> out = new LinkedHashSet<>();
        for (final long k : own) {
            for (final long n : neighbors4(k)) {
                if (!own.contains(n) && enemy.test(n)) {
                    out.add(n);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Would losing {@code removed} split {@code territory} into more than one piece (4-neighbour BFS)? Kraje only
     * allows sieges of chunks whose loss keeps the defender connected.
     */
    public static boolean wouldDisconnect(final Set<Long> territory, final long removed) {
        if (!territory.contains(removed) || territory.size() <= 2) {
            return false;
        }
        final Set<Long> rest = new java.util.HashSet<>(territory);
        rest.remove(removed);
        final long start = rest.iterator().next();
        final java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        final Set<Long> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            for (final long n : neighbors4(queue.poll())) {
                if (rest.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen.size() != rest.size();
    }

    /**
     * Kraje's siege rule ({@code ChunkGraph.staysConnected}) for territories that may already have overseas bridgeheads:
     * a connected territory with its capital in this world must stay reachable from the capital; a territory that is
     * already split (bridgehead, admin) may lose a chunk as long as that does not add another piece. Losing a whole
     * one-chunk bridgehead is therefore allowed — {@link #wouldDisconnect} wrongly refused every chunk of a split land.
     *
     * @param capitalKey capital chunk in this world, or {@code null} when the capital lies in another world
     */
    public static boolean siegeKeepsConnectivity(final Set<Long> territory, final long removed, final Long capitalKey) {
        if (!territory.contains(removed)) {
            return true;
        }
        final int before = components(territory);
        if (capitalKey != null && before <= 1) {
            if (removed == capitalKey || !territory.contains(capitalKey)) {
                return false;
            }
            return !wouldDisconnect(territory, removed) || territory.size() <= 2;
        }
        final Set<Long> after = new java.util.HashSet<>(territory);
        after.remove(removed);
        return components(after) <= before;
    }

    /** Number of 4-connected pieces of {@code territory}. */
    public static int components(final Set<Long> territory) {
        final Set<Long> seen = new java.util.HashSet<>();
        int n = 0;
        for (final long start : territory) {
            if (!seen.add(start)) {
                continue;
            }
            n++;
            final java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                for (final long nb : neighbors4(queue.poll())) {
                    if (territory.contains(nb) && seen.add(nb)) {
                        queue.add(nb);
                    }
                }
            }
        }
        return n;
    }

    private static long distSq(final long key, final int x, final int z) {
        final long dx = cx(key) - (long) x;
        final long dz = cz(key) - (long) z;
        return dx * dx + dz * dz;
    }
}
