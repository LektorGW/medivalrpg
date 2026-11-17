// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.world;

import org.bukkit.World;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;

/** Deliberately denies all regions, including __global__; no administrative bypass. */
public final class WorldGuardProtection {
    private WorldGuardProtection() { }
    public static boolean protectedAt(World world, int x, int y, int z) {
        var manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) throw new IllegalStateException("Region manager unavailable");
        return manager.hasRegion("__global__") || manager.getApplicableRegions(BlockVector3.at(x, y, z)).size() > 0;
    }
}
