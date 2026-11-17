// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

/** A chunk of a world (by name). */
public record ChunkRef(String world, int cx, int cz) {

    /** Kraje's chunk key: {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)}. */
    public long key() {
        return ((long) this.cx << 32) | (this.cz & 0xFFFFFFFFL);
    }

    public static ChunkRef ofBlock(final String world, final int x, final int z) {
        return new ChunkRef(world, x >> 4, z >> 4);
    }
}
