// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.listener;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.turnieje.game.PlayerCare;
import pl.medivalrpg.turnieje.game.Session;
import pl.medivalrpg.turnieje.game.SessionManager;

/**
 * Participant safety: join/quit/death/respawn hooks (restore, no dupes), command and teleport lock while inside a
 * tournament, no item drops, no containers, kit items can never leave the event.
 */
public final class PlayerListener implements Listener {

    public static final String PERM_ADMIN = "turnieje.admin";

    private final Plugin plugin;
    private final SessionManager sessions;
    private final PlayerCare care;

    public PlayerListener(final Plugin plugin, final SessionManager sessions, final PlayerCare care) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.care = care;
    }

    private boolean locked(final Player p) {
        return this.sessions.sessionInside(p.getUniqueId()) != null || this.care.hasSnapshot(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(final org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && this.locked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(final org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && this.locked(p)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(final org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && this.locked(p)) event.setCancelled(true);
        else if (this.care.isKit(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(final org.bukkit.event.player.PlayerInteractEvent event) {
        if (this.care.hasSnapshot(event.getPlayer()) && this.sessions.sessionInside(event.getPlayer().getUniqueId()) == null)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlacePending(final org.bukkit.event.block.BlockPlaceEvent event) {
        if (this.locked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(final org.bukkit.event.entity.EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player p && this.locked(p)
            && event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(final org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && this.locked(p)
            && (event.getEntity() instanceof org.bukkit.entity.Trident || this.sessions.sessionInside(p.getUniqueId()) == null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumePending(final org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (this.care.hasSnapshot(event.getPlayer()) && this.sessions.sessionInside(event.getPlayer().getUniqueId()) == null)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.sessions.afterRespawnOrJoin(p), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.sessions.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(final PlayerDeathEvent event) {
        final Player p = event.getEntity();
        event.getDrops().removeIf(this.care::isKit);
        if (this.sessions.onDeath(p)) {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player p = event.getPlayer();
        final Location to = this.sessions.respawnLocation(p);
        if (to != null) {
            event.setRespawnLocation(to);
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.sessions.afterRespawnOrJoin(p), 2L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final Player p = event.getPlayer();
        final Session s = this.sessions.sessionInside(p.getUniqueId());
        if (s == null && !this.care.hasSnapshot(p)) {
            return;
        }
        String label = event.getMessage().length() > 1 ? event.getMessage().substring(1) : "";
        final int space = label.indexOf(' ');
        if (space >= 0) {
            label = label.substring(0, space);
        }
        label = label.toLowerCase(Locale.ROOT);
        final int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!this.sessions.config().allowedCommands.contains(label)) {
            event.setCancelled(true);
            this.sessions.messages().send(p, "command-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final Player p = event.getPlayer();
        if (this.care.isOwnTeleport(p.getUniqueId())) {
            return;
        }
        final Session s = this.sessions.sessionInside(p.getUniqueId());
        if (s == null) {
            if (this.care.hasSnapshot(p)) event.setCancelled(true);
            return;
        }
        final Location to = event.getTo();
        final boolean insideArena = to != null && to.getWorld() != null
            && s.arena().box.contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ());
        final PlayerTeleportEvent.TeleportCause cause = event.getCause();
        final boolean vehicleOrUnknown = cause == PlayerTeleportEvent.TeleportCause.DISMOUNT || cause == PlayerTeleportEvent.TeleportCause.EXIT_BED;
        if (!insideArena && !vehicleOrUnknown && !this.nearWaitingSpot(s, to)) {
            event.setCancelled(true);
            this.sessions.messages().send(p, "teleport-blocked");
        }
    }

    private boolean nearWaitingSpot(final Session s, final Location to) {
        final Location w = this.sessions.waitingSpot(s);
        return w != null && to != null && to.getWorld() != null && to.getWorld().equals(w.getWorld()) && to.distanceSquared(w) < 16 * 16;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(final PlayerDropItemEvent event) {
        final Player p = event.getPlayer();
        if (this.locked(p)) {
            event.setCancelled(true);
            return;
        }
        if (this.care.isKit(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p) || !this.locked(p)) {
            return;
        }
        final InventoryType type = event.getInventory().getType();
        if (type != InventoryType.PLAYER && type != InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (this.locked(event.getPlayer()) && !(event.getRightClicked() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        if (this.locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !this.sessions.isSpectator(event.getPlayer().getUniqueId())) {
            return;
        }
        this.sessions.onSpectatorMove(event.getPlayer(), event.getTo());
    }
}
