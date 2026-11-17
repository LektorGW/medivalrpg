// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

/**
 * One rail block of a segment: {@code (x, y, z)} is the block that holds the rail, the bed is at {@code y - 1}.
 * Consecutive cells of a segment are 4-neighbours (horizontally) and differ by at most one block in height.
 */
public record TrackCell(int x, int y, int z, CellKind kind) {

    public long columnKey() {
        return ((long) this.x << 32) | (this.z & 0xFFFFFFFFL);
    }

    public int chunkX() {
        return this.x >> 4;
    }

    public int chunkZ() {
        return this.z >> 4;
    }

    public TrackCell withKind(final CellKind k) {
        return new TrackCell(this.x, this.y, this.z, k);
    }
}
