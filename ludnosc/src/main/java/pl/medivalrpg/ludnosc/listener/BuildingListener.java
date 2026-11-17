// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import java.util.List;
import java.util.UUID;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.service.GranaryService;

/** Bed changes inside buildings (housing recount) and granary containers (food deposit on close). */
public final class BuildingListener implements Listener {

    private final LudnoscPlugin plugin;

    public BuildingListener(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    /** Water and lava never flow into active construction sites, mine galleries or underground works being dug. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOW, ignoreCancelled = true)
    public void onLiquidFlow(final org.bukkit.event.block.BlockFromToEvent event) {
        final org.bukkit.Material m = event.getBlock().getType();
        if (m != org.bukkit.Material.WATER && m != org.bukkit.Material.LAVA) {
            return;
        }
        final Block to = event.getToBlock();
        if (this.plugin.construction().liquidBlocked(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            event.setCancelled(true);
        }
    }

    private void changed(final Block b) {
        if (Tag.BEDS.isTagged(b.getType())) {
            this.plugin.buildings().bedChangedAt(b);
        }
        if (this.plugin.playerBuildings() != null) {
            this.plugin.playerBuildings().changed(b);
        }
    }

    /** Water poured into / taken from a registered building (wells). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        this.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final org.bukkit.event.player.PlayerBucketFillEvent event) {
        this.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        this.changed(event.getBlock());
        this.plugin.granary().removePos(GranaryService.pos(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        this.changed(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        this.changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        this.exploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        this.exploded(event.blockList());
    }

    private void exploded(final List<Block> blocks) {
        for (final Block b : blocks) {
            this.changed(b);
            this.plugin.granary().removePos(GranaryService.pos(b));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        final UUID country = this.plugin.granary().countryOf(event.getInventory());
        if (country != null) {
            this.plugin.granary().absorb(p, event.getInventory(), country);
        }
    }
}
