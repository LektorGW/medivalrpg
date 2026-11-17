// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

/** A block position in a world (by name). */
public record BlockPos(String world, int x, int y, int z) {

    public ChunkRef chunk() {
        return ChunkRef.ofBlock(this.world, this.x, this.z);
    }

    @Override
    public String toString() {
        return this.x + " " + this.y + " " + this.z;
    }
}
