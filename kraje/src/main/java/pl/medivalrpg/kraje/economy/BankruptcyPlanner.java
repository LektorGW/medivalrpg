// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Picks the chunks a bankrupt country loses: the ones farthest from the capital.
 *
 * <p>Distance is measured through the territory (BFS steps from the capital, 4-neighbourhood), so each
 * removed chunk is a chunk at maximal BFS distance — removing such a chunk can never cut another chunk off
 * from the capital, and the territory stays connected. Chunks that are not connected to the capital at all
 * (other worlds, fragments left by admins) go first. Ties: larger straight-line distance first, then a
 * deterministic order. The capital is never chosen.</p>
 */
public final class BankruptcyPlanner {

    private static final int UNREACHABLE = Integer.MAX_VALUE;

    private BankruptcyPlanner() {
    }

    /**
     * @param claims  world → chunk keys of the country (not modified)
     * @param capital capital chunk
     * @param count   how many chunks to remove
     * @return chunks to remove, in removal order (at most {@code count}, never the capital)
     */
    public static List<ChunkPos> chunksToLose(final Map<String, Set<Long>> claims, final ChunkPos capital, final int count) {
        final Map<String, Set<Long>> remaining = new HashMap<>();
        claims.forEach((world, keys) -> remaining.put(world, new HashSet<>(keys)));
        final List<ChunkPos> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final ChunkPos next = farthest(remaining, capital);
            if (next == null) {
                break;
            }
            remaining.get(next.world()).remove(next.key());
            result.add(next);
        }
        return result;
    }

    private static ChunkPos farthest(final Map<String, Set<Long>> claims, final ChunkPos capital) {
        final Map<Long, Integer> steps = bfs(claims.getOrDefault(capital.world(), Set.of()), capital.key());
        ChunkPos best = null;
        int bestSteps = -1;
        long bestEuclid = -1;
        final Comparator<ChunkPos> order = Comparator.comparing(ChunkPos::world).thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z);
        for (final Map.Entry<String, Set<Long>> entry : claims.entrySet()) {
            final boolean capitalWorld = entry.getKey().equals(capital.world());
            for (final long key : entry.getValue()) {
                final ChunkPos pos = new ChunkPos(entry.getKey(), ChunkKeys.x(key), ChunkKeys.z(key));
                if (pos.equals(capital)) {
                    continue;
                }
                // Overseas territories are intentional now; do not break an outpost into more islands.
                if (!ChunkGraph.removalKeepsConnectivity(entry.getValue(),key)) continue;
                final int s = capitalWorld ? steps.getOrDefault(key, UNREACHABLE) : UNREACHABLE;
                final long dx = pos.x() - capital.x();
                final long dz = pos.z() - capital.z();
                final long euclid = capitalWorld ? dx * dx + dz * dz : Long.MAX_VALUE;
                if (s > bestSteps || (s == bestSteps && euclid > bestEuclid)
                    || (s == bestSteps && euclid == bestEuclid && order.compare(pos, best) < 0)) {
                    best = pos;
                    bestSteps = s;
                    bestEuclid = euclid;
                }
            }
        }
        return best;
    }

    private static Map<Long, Integer> bfs(final Set<Long> territory, final long start) {
        final Map<Long, Integer> steps = new HashMap<>();
        if (!territory.contains(start)) {
            return steps;
        }
        final Deque<Long> queue = new ArrayDeque<>();
        steps.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            final long current = queue.poll();
            final int next = steps.get(current) + 1;
            for (final long n : ChunkGraph.neighbours(current)) {
                if (territory.contains(n) && !steps.containsKey(n)) {
                    steps.put(n, next);
                    queue.add(n);
                }
            }
        }
        return steps;
    }
}
