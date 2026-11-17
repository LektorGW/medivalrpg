// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Which columns of one client currently show a client-only snow layer, and at which height (pure, unit tested).
 * Snow exists only in the client's world: the server never has these blocks. The tracker lets the packet handler keep
 * the illusion consistent — when the block under the snow changes into something that cannot carry snow, the snow is
 * removed too; a full chunk or a dimension change wipes the client's copy, so the entries go as well.
 *
 * <p>Thread-safe (the network thread writes, the main thread reads snapshots).</p>
 */
public final class SnowTracker {

    public static final short NONE = Short.MIN_VALUE;

    private final Map<Long, short[]> chunks = new HashMap<>();
    private int columns;

    public static long chunkKey(final int cx, final int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    private static int index(final int x, final int z) {
        return (z & 15) << 4 | (x & 15);
    }

    /** Remembers a snow layer at block {@code (x, y, z)}. */
    public synchronized void add(final int x, final int y, final int z) {
        final short[] col = this.chunks.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> {
            final short[] a = new short[256];
            Arrays.fill(a, NONE);
            return a;
        });
        final int i = index(x, z);
        if (col[i] == NONE) {
            this.columns++;
        }
        col[i] = (short) y;
    }

    /** Forgets the snow of a column if it is at height {@code y}; true when something was removed. */
    public synchronized boolean remove(final int x, final int y, final int z) {
        final short[] col = this.chunks.get(chunkKey(x >> 4, z >> 4));
        final int i = index(x, z);
        if (col == null || col[i] == NONE || col[i] != y) {
            return false;
        }
        col[i] = NONE;
        this.columns--;
        return true;
    }

    /** Height of the snow of a column, or {@link #NONE}. */
    public synchronized int snowAt(final int x, final int z) {
        final short[] col = this.chunks.get(chunkKey(x >> 4, z >> 4));
        return col == null ? NONE : col[index(x, z)];
    }

    /**
     * The server changed the block at {@code (x, y, z)} on this client.
     *
     * @param supports whether the new block can still carry a snow layer on top
     * @return true when the client shows snow directly above that block, which must now be removed (air)
     */
    public synchronized boolean onBlockUpdate(final int x, final int y, final int z, final boolean supports) {
        final short[] col = this.chunks.get(chunkKey(x >> 4, z >> 4));
        if (col == null) {
            return false;
        }
        final int i = index(x, z);
        final int snow = col[i];
        if (snow == NONE) {
            return false;
        }
        if (snow == y) {
            // the snow position itself got a real block: the client now shows the real one
            col[i] = NONE;
            this.columns--;
            return false;
        }
        if (snow == y + 1 && !supports) {
            col[i] = NONE;
            this.columns--;
            return true;
        }
        return false;
    }

    /** The client received a whole new chunk or unloaded it: its snow is gone. */
    public synchronized void clearChunk(final int cx, final int cz) {
        final short[] col = this.chunks.remove(chunkKey(cx, cz));
        if (col != null) {
            for (final short s : col) {
                if (s != NONE) {
                    this.columns--;
                }
            }
        }
    }

    public synchronized void clearAll() {
        this.chunks.clear();
        this.columns = 0;
    }

    /** Copy of the heights of a chunk (index {@code z << 4 | x}), or null when none. */
    public synchronized short[] snapshot(final int cx, final int cz) {
        final short[] col = this.chunks.get(chunkKey(cx, cz));
        return col == null ? null : col.clone();
    }

    public synchronized int columns() {
        return this.columns;
    }

    public synchronized int chunks() {
        return this.chunks.size();
    }
}
