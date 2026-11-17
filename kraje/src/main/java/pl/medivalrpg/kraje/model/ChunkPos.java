// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import org.bukkit.Location;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * A chunk position in a named world.
 */
public record ChunkPos(String world, int x, int z) {

    public static ChunkPos of(final Location location) {
        return new ChunkPos(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public long key() {
        return ChunkKeys.key(this.x, this.z);
    }

    public int centerBlockX() {
        return (this.x << 4) + 8;
    }

    public int centerBlockZ() {
        return (this.z << 4) + 8;
    }
}
