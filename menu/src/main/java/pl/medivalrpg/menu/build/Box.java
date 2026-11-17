// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

/** Axis-aligned block box, both corners inclusive. Pure. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min > max: " + minX + "," + minY + "," + minZ + " / "
                + maxX + "," + maxY + "," + maxZ);
        }
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

    /** Grown horizontally by {@code margin} blocks; Y replaced by {@code minY..maxY}. */
    public Box expandHorizontally(final int margin, final int minY, final int maxY) {
        return new Box(this.minX - margin, minY, this.minZ - margin, this.maxX + margin, maxY, this.maxZ + margin);
    }

    public boolean containsColumn(final int x, final int z) {
        return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
    }

    /** Chebyshev distance of a column from the box footprint (0 inside). */
    public int columnDistance(final int x, final int z) {
        final int dx = x < this.minX ? this.minX - x : Math.max(0, x - this.maxX);
        final int dz = z < this.minZ ? this.minZ - z : Math.max(0, z - this.maxZ);
        return Math.max(dx, dz);
    }

    public int minChunkX() {
        return this.minX >> 4;
    }

    public int maxChunkX() {
        return this.maxX >> 4;
    }

    public int minChunkZ() {
        return this.minZ >> 4;
    }

    public int maxChunkZ() {
        return this.maxZ >> 4;
    }

    public String describe() {
        return this.sizeX() + "×" + this.sizeY() + "×" + this.sizeZ() + " (" + this.minX + ", " + this.minY + ", "
            + this.minZ + " → " + this.maxX + ", " + this.maxY + ", " + this.maxZ + ")";
    }
}
