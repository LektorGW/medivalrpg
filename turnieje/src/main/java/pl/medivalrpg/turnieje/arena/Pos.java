// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Serializable position (world name + coordinates + rotation). */
public record Pos(String world, double x, double y, double z, float yaw, float pitch) {

    public static Pos of(final Location l) {
        return new Pos(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** Null when the world is not loaded. */
    public Location toLocation() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? null : new Location(w, this.x, this.y, this.z, this.yaw, this.pitch);
    }
}
