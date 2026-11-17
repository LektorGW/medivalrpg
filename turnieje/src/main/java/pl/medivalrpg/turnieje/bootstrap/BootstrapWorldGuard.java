// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bootstrap;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.World;

/** Loaded only with an enabled WorldGuard. Any overlapping region fails closed. */
final class BootstrapWorldGuard {
    static void validate(World world) {
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if(manager==null)throw new IllegalStateException("Brak managera regionów WorldGuard.");
        var probe=new ProtectedCuboidRegion("turnieje_bootstrap_probe",BlockVector3.at(4933,89,892),BlockVector3.at(4955,97,910));
        if(!manager.getApplicableRegions(probe).getRegions().isEmpty())throw new IllegalStateException("Obrys przecina chroniony region WorldGuard.");
    }
}
