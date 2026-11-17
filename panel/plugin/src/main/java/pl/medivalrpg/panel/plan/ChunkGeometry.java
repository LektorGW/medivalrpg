// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Chunk-set helpers shared by the plan validator, the map render and the territory editor. Chunk keys are Kraje's
 * {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)}. Pure, no Bukkit.
 */
public final class ChunkGeometry {

    private ChunkGeometry() {
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

    /** Chunks within {@code radius} chunks (Chebyshev) of any chunk of {@code set}, the set itself included. */
    public static Set<Long> dilate(final Collection<Long> set, final int radius) {
        final Set<Long> out = new HashSet<>(set);
        if (radius <= 0) {
            return out;
        }
        for (final long k : set) {
            final int x = cx(k);
            final int z = cz(k);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    out.add(key(x + dx, z + dz));
                }
            }
        }
        return out;
    }

    /**
     * Whether block ({@code x}, {@code z}) lies on one of the chunks or at most {@code margin} blocks (Chebyshev) from
     * one of them.
     */
    public static boolean nearChunks(final Set<Long> chunks, final int x, final int z, final int margin) {
        final int cx = x >> 4;
        final int cz = z >> 4;
        if (chunks.contains(key(cx, cz))) {
            return true;
        }
        final int r = (margin + 15) >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final int ox = cx + dx;
                final int oz = cz + dz;
                if (!chunks.contains(key(ox, oz))) {
                    continue;
                }
                final int minX = ox << 4;
                final int minZ = oz << 4;
                final int ddx = Math.max(0, Math.max(minX - x, x - (minX + 15)));
                final int ddz = Math.max(0, Math.max(minZ - z, z - (minZ + 15)));
                if (Math.max(ddx, ddz) <= margin) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Chunks of {@code set} reachable from {@code start} through shared edges (4-neighbourhood). */
    public static Set<Long> reachable(final Set<Long> set, final long start) {
        final Set<Long> seen = new HashSet<>();
        if (!set.contains(start)) {
            return seen;
        }
        final ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            final long k = queue.poll();
            final int x = cx(k);
            final int z = cz(k);
            for (final long n : new long[] {key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)}) {
                if (set.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** Whether {@code set} is one edge-connected piece containing {@code anchor}. */
    public static boolean connected(final Set<Long> set, final long anchor) {
        return set.contains(anchor) && reachable(set, anchor).size() == set.size();
    }
}
