// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Persistent position (world name + coordinates). {@code y == SURFACE} means "on the ground at x/z", resolved when
 * the chunk is loaded (used by the AI for posts in unloaded chunks).
 */
public record Pos(String world, double x, double y, double z, float yaw) {

    public static final double SURFACE = -10000;

    public static Pos of(final Location l) {
        return new Pos(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw());
    }

    public static Pos surface(final String world, final double x, final double z) {
        return new Pos(world, x, SURFACE, z, 0f);
    }

    /** Centre of chunk ({@code cx}, {@code cz}) on the surface. */
    public static Pos chunkCenter(final String world, final int cx, final int cz) {
        return surface(world, (cx << 4) + 8.5, (cz << 4) + 8.5);
    }

    public boolean isSurface() {
        return this.y <= SURFACE + 1;
    }

    public int chunkX() {
        return ((int) Math.floor(this.x)) >> 4;
    }

    public int chunkZ() {
        return ((int) Math.floor(this.z)) >> 4;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(this.world);
    }

    public boolean isChunkLoaded() {
        final World w = this.bukkitWorld();
        return w != null && w.isChunkLoaded(this.chunkX(), this.chunkZ());
    }

    /**
     * Bukkit location; surface positions are resolved with the highest block (main thread, chunk must be loaded).
     * Null if the world is missing or the surface cannot be resolved yet.
     */
    public Location toLocation() {
        final World w = this.bukkitWorld();
        if (w == null) {
            return null;
        }
        if (this.isSurface()) {
            if (!w.isChunkLoaded(this.chunkX(), this.chunkZ())) {
                return null;
            }
            final int top = w.getHighestBlockYAt((int) Math.floor(this.x), (int) Math.floor(this.z));
            return new Location(w, this.x, top + 1.0, this.z, this.yaw, 0f);
        }
        return new Location(w, this.x, this.y, this.z, this.yaw, 0f);
    }

    /** Same position with a concrete y (after resolving a surface position). */
    public Pos resolved(final Location l) {
        return new Pos(this.world, this.x, l.getY(), this.z, this.yaw);
    }

    public double distanceSq(final Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(this.world)) {
            return Double.MAX_VALUE;
        }
        final double dx = l.getX() - this.x;
        final double dz = l.getZ() - this.z;
        final double dy = this.isSurface() ? 0 : l.getY() - this.y;
        return dx * dx + dy * dy + dz * dz;
    }

    public String describe() {
        return this.world + " " + (int) Math.floor(this.x) + " " + (this.isSurface() ? "~" : Integer.toString((int) Math.floor(this.y))) + " " + (int) Math.floor(this.z);
    }
}
