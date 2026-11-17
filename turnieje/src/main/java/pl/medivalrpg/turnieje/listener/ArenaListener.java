// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import pl.medivalrpg.turnieje.game.Horses;
import pl.medivalrpg.turnieje.game.SessionManager;

/** Arena protection while a session runs on it, and the joust horses (no dismount mid-fight, no saddle theft, no orphans). */
public final class ArenaListener implements Listener {

    public static final String PERM_BYPASS = "turnieje.bypass";

    private final SessionManager sessions;
    private final Horses horses;

    public ArenaListener(final SessionManager sessions, final Horses horses) {
        this.sessions = sessions;
        this.horses = horses;
    }

    private boolean protectedBlock(final Block b) {
        return this.sessions.isProtected(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private boolean denied(final Player p, final Block b) {
        if (this.sessions.sessionInside(p.getUniqueId()) != null) {
            return true;
        }
        return !p.hasPermission(PERM_BYPASS) && this.protectedBlock(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (this.denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            this.sessions.messages().send(event.getPlayer(), "arena-protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (this.denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            this.sessions.messages().send(event.getPlayer(), "arena-protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (this.denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (this.denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (this.protectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && this.protectedBlock(event.getBlock())) {
            event.setCancelled(true);
        } else if (event.getPlayer() != null && this.denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ---- horses ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDismount(final EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player p) || !this.horses.isOurs(event.getDismounted()) || this.horses.isReleasing(p.getUniqueId())) {
            return;
        }
        if (this.sessions.liveFightSession(p.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHorseInventory(final InventoryOpenEvent event) {
        final InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof Entity e && this.horses.isOurs(e)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHorseDeath(final EntityDeathEvent event) {
        if (this.horses.isOurs(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            if (this.horses.isOrphan(e)) {
                e.remove();
            }
        }
    }
}
