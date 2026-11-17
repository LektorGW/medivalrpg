// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;

/**
 * Prevents protection bypasses that do not involve a player directly: pistons, liquids, fire,
 * explosions, hoppers and dispensers crossing a border.
 */
public final class EnvironmentListener implements Listener {

    private final KrajePlugin plugin;
    private final ProtectionService protection;

    public EnvironmentListener(final KrajePlugin plugin, final ProtectionService protection) {
        this.plugin = plugin;
        this.protection = protection;
    }

    private KrajeConfig.Protection settings() {
        return this.plugin.cfg().protection;
    }

    /** {@code true} if {@code target} is claimed by a country different from {@code source}'s owner. */
    private static boolean crossesIntoForeign(final UUID sourceOwner, final UUID targetOwner) {
        return targetOwner != null && !targetOwner.equals(sourceOwner);
    }

    private static boolean sameChunk(final Block a, final Block b) {
        return a.getX() >> 4 == b.getX() >> 4 && a.getZ() >> 4 == b.getZ() >> 4;
    }

    // ------------------------------------------------------------------ pistons

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (!this.settings().enabled || this.settings().pistonsAcrossBorder) {
            return;
        }
        final Block piston = event.getBlock();
        final BlockFace dir = event.getDirection();
        final UUID pistonOwner = this.protection.owner(piston);
        if (crossesIntoForeign(pistonOwner, this.protection.owner(piston.getRelative(dir)))
            || this.movesAcross(pistonOwner, event.getBlocks(), dir)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (!this.settings().enabled || this.settings().pistonsAcrossBorder) {
            return;
        }
        final UUID pistonOwner = this.protection.owner(event.getBlock());
        // Check both neighbours along the axis: the API's retract direction has historically been ambiguous.
        if (this.movesAcross(pistonOwner, event.getBlocks(), event.getDirection())
            || this.movesAcross(pistonOwner, event.getBlocks(), event.getDirection().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    private boolean movesAcross(final UUID pistonOwner, final List<Block> blocks, final BlockFace dir) {
        for (final Block block : blocks) {
            if (crossesIntoForeign(pistonOwner, this.protection.owner(block))
                || crossesIntoForeign(pistonOwner, this.protection.owner(block.getRelative(dir)))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ liquids

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        if (!this.settings().enabled || this.settings().liquidsAcrossBorder) {
            return;
        }
        final Block from = event.getBlock();
        final Block to = event.getToBlock();
        if (sameChunk(from, to)) {
            return;
        }
        if (crossesIntoForeign(this.protection.owner(from), this.protection.owner(to))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ fire

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFireSpread(final BlockSpreadEvent event) {
        if (!this.settings().enabled || this.settings().fireSpreadFromOutside) {
            return;
        }
        if (!Tag.FIRE.isTagged(event.getNewState().getType())) {
            return;
        }
        if (crossesIntoForeign(this.protection.owner(event.getSource()), this.protection.owner(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnvironmentIgnite(final BlockIgniteEvent event) {
        if (!this.settings().enabled || this.settings().fireSpreadFromOutside) {
            return;
        }
        final BlockIgniteEvent.IgniteCause cause = event.getCause();
        if (cause != BlockIgniteEvent.IgniteCause.SPREAD && cause != BlockIgniteEvent.IgniteCause.LAVA) {
            return;
        }
        final Block source = event.getIgnitingBlock();
        if (source != null && crossesIntoForeign(this.protection.owner(source), this.protection.owner(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (!this.settings().enabled || this.settings().fireSpreadFromOutside) {
            return;
        }
        final Block source = event.getIgnitingBlock();
        if (source != null && crossesIntoForeign(this.protection.owner(source), this.protection.owner(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ explosions

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (!this.settings().enabled || this.settings().explosions) {
            return;
        }
        event.blockList().removeIf(block -> !this.plugin.tnt().allowsDamage(event.getEntity(),block));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (!this.settings().enabled || this.settings().explosions) {
            return;
        }
        event.blockList().removeIf(block -> this.protection.owner(block) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingExplode(final HangingBreakEvent event) {
        if (!this.settings().enabled || this.settings().explosions) {
            return;
        }
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION
            && this.protection.owner(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ hoppers & dispensers

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryMove(final InventoryMoveItemEvent event) {
        if (!this.settings().enabled || this.settings().hoppersAcrossBorder) {
            return;
        }
        final Location source = event.getSource().getLocation();
        final Location destination = event.getDestination().getLocation();
        if (source == null || destination == null || source.getWorld() == null) {
            return;
        }
        if (source.getBlockX() >> 4 == destination.getBlockX() >> 4 && source.getBlockZ() >> 4 == destination.getBlockZ() >> 4
            && Objects.equals(source.getWorld(), destination.getWorld())) {
            return;
        }
        final UUID sourceOwner = this.protection.owner(source);
        // Pulling items out of a country into anything owned by someone else (or the wilderness).
        if (sourceOwner != null && !sourceOwner.equals(this.protection.owner(destination))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(final BlockDispenseEvent event) {
        if (!this.settings().enabled || this.settings().dispensersAcrossBorder) {
            return;
        }
        final Block block = event.getBlock();
        final BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) {
            return;
        }
        final Block target = block.getRelative(directional.getFacing());
        if (!sameChunk(block, target) && crossesIntoForeign(this.protection.owner(block), this.protection.owner(target))) {
            event.setCancelled(true);
        }
    }
}
