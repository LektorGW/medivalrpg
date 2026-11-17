// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.ArrayList;
import java.util.List;

/** Inclusive block box inside one world (pure). */
public record BlockBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public BlockBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Pusty obszar");
        }
    }

    public static BlockBox of(final String world, final int x1, final int y1, final int z1, final int x2, final int y2,
                              final int z2) {
        return new BlockBox(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public int sizeX() {
        return this.maxX - this.minX + 1;
    }

    public int sizeY() {
        return this.maxY - this.minY + 1;
    }

    public int sizeZ() {
        return this.maxZ - this.minZ + 1;
    }

    public long volume() {
        return (long) this.sizeX() * this.sizeY() * this.sizeZ();
    }

    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world) && x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY
            && z >= this.minZ && z <= this.maxZ;
    }

    public boolean intersects(final BlockBox o) {
        return this.world.equals(o.world) && this.minX <= o.maxX && this.maxX >= o.minX && this.minY <= o.maxY
            && this.maxY >= o.minY && this.minZ <= o.maxZ && this.maxZ >= o.minZ;
    }

    public int centerX() {
        return Math.floorDiv(this.minX + this.maxX, 2);
    }

    public int centerZ() {
        return Math.floorDiv(this.minZ + this.maxZ, 2);
    }

    /** The chunk of the box centre — decides which country owns a building. */
    public ChunkRef anchorChunk() {
        return ChunkRef.ofBlock(this.world, this.centerX(), this.centerZ());
    }

    /** All chunks overlapped by the box footprint. */
    public List<ChunkRef> chunks() {
        final List<ChunkRef> out = new ArrayList<>();
        for (int cx = this.minX >> 4; cx <= this.maxX >> 4; cx++) {
            for (int cz = this.minZ >> 4; cz <= this.maxZ >> 4; cz++) {
                out.add(new ChunkRef(this.world, cx, cz));
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "(" + this.minX + " " + this.minY + " " + this.minZ + ") → (" + this.maxX + " " + this.maxY + " " + this.maxZ + ")";
    }
}
