// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

/** Inclusive block cuboid in one world. Pure value. */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Box spanned by two corners given in any order. */
    public static Box of(final String world, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        return new Box(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world) && x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY
            && z >= this.minZ && z <= this.maxZ;
    }

    /** Same as {@link #contains(String, int, int, int)} for entity coordinates (floored). */
    public boolean contains(final String world, final double x, final double y, final double z) {
        return this.contains(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public long volume() {
        return (long) (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1) * (this.maxZ - this.minZ + 1);
    }

    public double centerX() {
        return (this.minX + this.maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (this.minZ + this.maxZ + 1) / 2.0;
    }

    /** Footprint of the box in chunks overlaps the chunk ({@code cx}, {@code cz}). */
    public boolean touchesChunk(final String world, final int cx, final int cz) {
        return this.world.equals(world) && cx >= (this.minX >> 4) && cx <= (this.maxX >> 4) && cz >= (this.minZ >> 4) && cz <= (this.maxZ >> 4);
    }
}
