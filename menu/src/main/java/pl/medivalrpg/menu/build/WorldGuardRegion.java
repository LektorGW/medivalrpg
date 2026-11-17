// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.World;

/**
 * WorldGuard 7.0 implementation of {@link RegionProtector}. Loaded only by reflection when WorldGuard is enabled.
 *
 * <p>Flags: build, pvp, tnt, creeper-explosion, other-explosion, fire-spread, lava-fire, lava-flow, chest-access,
 * item-frame-rotation, sleep = deny; water-flow, use, interact, ride = allow; deny-spawn on hostile mobs (or
 * mob-spawning deny when the list is empty); greeting / farewell; invincible = allow (optional). No members:
 * ops bypass through WorldGuard's own permissions.</p>
 */
public final class WorldGuardRegion implements RegionProtector {

    private static RegionManager manager(final World world) throws IOException {
        final RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (rm == null) {
            throw new IOException("WorldGuard nie zarządza regionami w świecie " + world.getName());
        }
        return rm;
    }

    @Override
    public int apply(final World world, final Box box, final Spec spec) throws Exception {
        final RegionManager rm = manager(world);
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(spec.id(),
            BlockVector3.at(box.minX(), box.minY(), box.minZ()), BlockVector3.at(box.maxX(), box.maxY(), box.maxZ()));
        region.setPriority(spec.priority());
        int flags = 0;
        for (final StateFlag deny : new StateFlag[] {Flags.BUILD, Flags.PVP, Flags.TNT, Flags.CREEPER_EXPLOSION,
            Flags.OTHER_EXPLOSION, Flags.FIRE_SPREAD, Flags.LAVA_FIRE, Flags.LAVA_FLOW, Flags.CHEST_ACCESS,
            Flags.ITEM_FRAME_ROTATE, Flags.SLEEP}) {
            region.setFlag(deny, StateFlag.State.DENY);
            flags++;
        }
        for (final StateFlag allow : new StateFlag[] {Flags.WATER_FLOW, Flags.USE, Flags.INTERACT, Flags.RIDE}) {
            region.setFlag(allow, StateFlag.State.ALLOW);
            flags++;
        }
        final Set<EntityType> denied = new HashSet<>();
        for (final String key : spec.denySpawn()) {
            final EntityType type = EntityType.REGISTRY.get(key.strip().toLowerCase(Locale.ROOT).contains(":")
                ? key.strip().toLowerCase(Locale.ROOT) : "minecraft:" + key.strip().toLowerCase(Locale.ROOT));
            if (type != null) {
                denied.add(type);
            }
        }
        if (denied.isEmpty()) {
            region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
        } else {
            region.setFlag(Flags.DENY_SPAWN, denied);
        }
        flags++;
        if (spec.invincible()) {
            region.setFlag(Flags.INVINCIBILITY, StateFlag.State.ALLOW);
            flags++;
        }
        flags += setText(region, "greeting", spec.greeting());
        flags += setText(region, "farewell", spec.farewell());
        if (rm.hasRegion(spec.id())) {
            rm.removeRegion(spec.id());
        }
        rm.addRegion(region);
        rm.save();
        return flags;
    }

    /** String flags looked up by name in the flag registry (the static fields are deprecated in 7.0.20). */
    private static int setText(final ProtectedCuboidRegion region, final String name, final String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        if (WorldGuard.getInstance().getFlagRegistry().get(name) instanceof StringFlag flag) {
            region.setFlag(flag, text);
            return 1;
        }
        return 0;
    }

    @Override
    public boolean remove(final World world, final String id) throws Exception {
        final RegionManager rm = manager(world);
        if (!rm.hasRegion(id)) {
            return false;
        }
        rm.removeRegion(id);
        rm.save();
        return true;
    }

    @Override
    public boolean exists(final World world, final String id) {
        try {
            return manager(world).hasRegion(id);
        } catch (final IOException e) {
            return false;
        }
    }
}
