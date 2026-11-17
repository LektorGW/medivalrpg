// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A stored teleport point (world by name, so it survives restarts).
 */
public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public static SpawnPoint of(final Location location) {
        return new SpawnPoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
    }

    /** Returns the Bukkit location, or {@code null} if the world is not loaded. */
    public Location toLocation() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? null : new Location(w, this.x, this.y, this.z, this.yaw, this.pitch);
    }
}
