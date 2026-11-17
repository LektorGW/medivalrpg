// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All event handling: menu, menu protection, cleanup on quit, sitting / lying triggers. */
public final class InterakcjeListener implements Listener {
    private static final long MENU_DEBOUNCE_MS = 300;

    private final InterakcjePlugin plugin;
    private final Map<UUID, Long> lastMenuOpen = new HashMap<>();

    public InterakcjeListener(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.poses().onJoin(p);
        plugin.ceremonies().applyDisplayName(p);
        plugin.data().rememberName(p.getUniqueId(), p.getName());
        plugin.snapshot().refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.service().playerQuit(p);
        plugin.holds().playerQuit(p);
        plugin.poses().onQuit(p); // before the server saves the player, so no seat is stored as vehicle
        plugin.animations().onGone(p);
        plugin.effects().forget(p.getUniqueId());
        lastMenuOpen.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ menu

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractPlayer(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player target)) {
            return;
        }
        Player p = e.getPlayer();
        Settings s = plugin.settings();
        if (!s.menuShiftRightClick() || !p.isSneaking() || target.hasMetadata("NPC")) {
            return;
        }
        if (s.menuRequireEmptyHand() && !p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        if (!p.hasPermission(InteractionService.PERM_USE) || !plugin.service().canTarget(p, target)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastMenuOpen.get(p.getUniqueId());
        if (last != null && now - last < MENU_DEBOUNCE_MS) {
            return;
        }
        lastMenuOpen.put(p.getUniqueId(), now);
        e.setCancelled(true);
        InteractionMenu.open(plugin, p, target);
    }

    private static InteractionMenu menuOf(Inventory top) {
        return top != null && top.getHolder(false) instanceof InteractionMenu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InteractionMenu menu = menuOf(top);
        if (menu == null) {
            return;
        }
        // cancel everything: shift-click, number keys, double click, drop, clicks in the player inventory
        e.setCancelled(true);
        if (e.getClickedInventory() != top || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) {
            return;
        }
        String id = menu.interactionAt(e.getRawSlot());
        if (id == null) {
            return;
        }
        UUID targetId = menu.target();
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                plugin.messages().send(p, "player-not-found", Messages.text("player", "?"));
                return;
            }
            plugin.service().attempt(p, plugin.interaction(id), target);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (menuOf(e.getView().getTopInventory()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropWhileMenu(PlayerDropItemEvent e) {
        if (menuOf(e.getPlayer().getOpenInventory().getTopInventory()) != null) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ sitting / lying

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClickBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND
                || e.getBlockFace() != BlockFace.UP || e.getClickedBlock() == null) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() || !hand.getType().isAir() || p.isInsideVehicle()) {
            return;
        }
        if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return; // protected area etc.
        }
        Block block = e.getClickedBlock();
        if (plugin.poses().trySitOnBlock(p, block)) {
            e.setCancelled(true);
        }
    }

    /** Optional: right-click with a drink (bottle, BreweryX brew...) plays the tankard emote. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrinkClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND
                || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)
                || e.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        MotionGroup emotes = plugin.motionGroups().get(InterakcjePlugin.EMOTES);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (emotes == null || p.isSneaking() || hand.getType().isAir() || !emotes.clickTrigger().matches(hand.getType().name())) {
            return;
        }
        plugin.service().performMotion(p, emotes, emotes.get(emotes.clickTrigger().motion()), null, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.poses().onDismount(p, e.getDismounted());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) {
            plugin.poses().onSneak(e.getPlayer());
            plugin.animations().onSneak(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) {
            return;
        }
        if (plugin.poses().isLying(e.getPlayer().getUniqueId())) {
            plugin.poses().onMove(e.getPlayer(), e.getTo());
        }
        plugin.animations().onMove(e.getPlayer(), e.getTo()); // walking ends an animation
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (plugin.poses().isLying(e.getPlayer().getUniqueId())) {
            plugin.poses().standUp(e.getPlayer(), false);
        }
        // sitting: the teleport dismounts the player -> EntityDismountEvent
        plugin.animations().onTeleport(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.animations().onDamage(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        plugin.poses().standUp(e.getPlayer(), false);
        plugin.animations().onGone(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        plugin.poses().standUp(e.getPlayer(), false);
        plugin.animations().onGone(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        plugin.poses().onBlockGone(e.getBlock());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        int n = plugin.poses().removeStraySeats(e.getEntities());
        if (n > 0) {
            plugin.getLogger().info("Usunięto osierocone siedziska: " + n);
        }
    }
}
