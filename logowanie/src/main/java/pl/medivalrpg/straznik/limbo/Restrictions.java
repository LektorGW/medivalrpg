// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.limbo;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import pl.medivalrpg.straznik.config.Messages;
import pl.medivalrpg.straznik.config.Settings;

/**
 * Everything a player may not do before logging in: move, chat, commands other than login/register, interact,
 * take or deal damage, be targeted by mobs, pick up, drop, open or click inventories, hunger, beds, portals and
 * foreign teleports. Command suggestions are limited to the login commands.
 */
public final class Restrictions implements Listener {

    private final Predicate<java.util.UUID> authenticated;
    private final Predicate<java.util.UUID> ownTeleport;
    private final Supplier<Settings> settings;
    private final Supplier<Messages> messages;
    private final Set<String> allowedCommands;

    public Restrictions(final Predicate<java.util.UUID> authenticated, final Predicate<java.util.UUID> ownTeleport,
                        final Supplier<Settings> settings, final Supplier<Messages> messages, final Set<String> allowedCommands) {
        this.authenticated = authenticated;
        this.ownTeleport = ownTeleport;
        this.settings = settings;
        this.messages = messages;
        this.allowedCommands = allowedCommands;
    }

    private boolean locked(final Entity e) {
        return e instanceof Player p && !this.authenticated.test(p.getUniqueId());
    }

    private void deny(final Player p, final Cancellable event) {
        if (!this.authenticated.test(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Root name of a typed command, without "/" and plugin prefix, lower case. */
    public static String root(final String message) {
        String m = message.startsWith("/") ? message.substring(1) : message;
        final int space = m.indexOf(' ');
        if (space >= 0) {
            m = m.substring(0, space);
        }
        m = m.toLowerCase(Locale.ROOT);
        final int colon = m.indexOf(':');
        return colon >= 0 ? m.substring(colon + 1) : m;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (this.authenticated.test(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!this.allowedCommands.contains(root(event.getMessage()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.messages.get().get("najpierw-zaloguj"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandList(final PlayerCommandSendEvent event) {
        if (!this.authenticated.test(event.getPlayer().getUniqueId())) {
            event.getCommands().removeIf(c -> !this.allowedCommands.contains(root(c)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        if (!this.authenticated.test(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.messages.get().get("najpierw-zaloguj"));
            return;
        }
        if (this.settings.get().limboHideChat) {
            event.viewers().removeIf(a -> a instanceof Player p && !this.authenticated.test(p.getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (this.authenticated.test(event.getPlayer().getUniqueId())) {
            return;
        }
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            // cancelling makes the server put the player back itself (teleport cause UNKNOWN, allowed below)
            event.setCancelled(true);
        }
    }

    /**
     * Foreign teleports are refused before login. Allowed: Straznik's own (limbo / restore) and the server's internal
     * position corrections (cause UNKNOWN, e.g. after a cancelled move) — plugins teleport with cause PLUGIN/COMMAND.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final java.util.UUID id = event.getPlayer().getUniqueId();
        if (!this.authenticated.test(id) && !this.ownTeleport.test(id)
            && event.getCause() != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlight(final PlayerToggleFlightEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(final BlockBreakEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(final BlockPlaceEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(final EntityPickupItemEvent event) {
        if (this.locked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(final PlayerSwapHandItemsEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(final PlayerItemHeldEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(final PlayerItemConsumeEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBed(final PlayerBedEnterEvent event) {
        this.deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(final InventoryOpenEvent event) {
        if (this.locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        if (this.locked(event.getWhoClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(final InventoryDragEvent event) {
        if (this.locked(event.getWhoClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageEvent event) {
        if (this.locked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(final EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (this.locked(damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(final EntityTargetEvent event) {
        if (this.locked(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(final FoodLevelChangeEvent event) {
        if (this.locked(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
