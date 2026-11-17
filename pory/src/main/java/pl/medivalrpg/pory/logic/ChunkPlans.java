// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Small pure helpers for refresh ordering and snow reconciliation (unit tested). */
public final class ChunkPlans {

    private ChunkPlans() {
    }

    public static int chunkX(final long key) {
        return (int) key;
    }

    public static int chunkZ(final long key) {
        return (int) (key >>> 32);
    }

    /** Chunk keys (Paper {@code Chunk#getChunkKey} layout: x low, z high) nearest first. */
    public static long[] nearestFirst(final Collection<Long> keys, final int centerX, final int centerZ) {
        final long[] a = new long[keys.size()];
        int n = 0;
        for (final Long k : keys) {
            if (k != null) {
                a[n++] = k;
            }
        }
        final Long[] boxed = new Long[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = a[i];
        }
        java.util.Arrays.sort(boxed, (p, q) -> Long.compare(dist(p, centerX, centerZ), dist(q, centerX, centerZ)));
        final long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = boxed[i];
        }
        return out;
    }

    private static long dist(final long key, final int cx, final int cz) {
        final long dx = chunkX(key) - cx;
        final long dz = chunkZ(key) - cz;
        return dx * dx + dz * dz;
    }

    /** Column change: {@code oldY} snow to remove (or NONE), {@code newY} snow to add (or NONE). */
    public record ColumnChange(int index, int oldY, int newY) {
    }

    /**
     * Differences between what the client shows ({@code current}) and what it should show ({@code desired}); arrays of
     * 256 heights indexed {@code z << 4 | x}, {@link SnowTracker#NONE} = no snow, null = no snow anywhere.
     */
    public static List<ColumnChange> diff(final short[] current, final short[] desired) {
        final List<ColumnChange> out = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            final int c = current == null ? SnowTracker.NONE : current[i];
            final int d = desired == null ? SnowTracker.NONE : desired[i];
            if (c != d) {
                out.add(new ColumnChange(i, c, d));
            }
        }
        return out;
    }
}
