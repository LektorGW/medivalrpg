// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

/**
 * Packs chunk coordinates into a single long (same layout as Paper's {@code Chunk.getChunkKey}).
 */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long key(final int x, final int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int x(final long key) {
        return (int) key;
    }

    public static int z(final long key) {
        return (int) (key >>> 32);
    }

    public static int blockToChunk(final int block) {
        return block >> 4;
    }
}
