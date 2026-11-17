// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Safe standing spots on the surface (main thread; loads the chunk if needed). */
public final class SafeSpot {

    private SafeSpot() {
    }

    /** True when a player can stand on top of this block: solid, not liquid, not fire/magma/cactus. */
    public static boolean standable(final Block top) {
        final Material m = top.getType();
        if (top.isLiquid() || !m.isSolid()) {
            return false;
        }
        return switch (m) {
            case MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW, SWEET_BERRY_BUSH -> false;
            default -> true;
        };
    }

    /**
     * The nearest standable column to (x, z) within {@code radius} blocks (spiral), as a location on top of it facing
     * {@code yaw}; null when none.
     */
    public static Location near(final World w, final int x, final int z, final int radius, final float yaw) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    final Block top = w.getHighestBlockAt(x + dx, z + dz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if (standable(top) && top.getRelative(0, 1, 0).isPassable() && top.getRelative(0, 2, 0).isPassable()) {
                        return new Location(w, x + dx + 0.5, top.getY() + 1.0, z + dz + 0.5, yaw, 0f);
                    }
                }
            }
        }
        return null;
    }
}
