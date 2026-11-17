// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.guide;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.gui.MenuHolder;

/**
 * Behaviour and protection of the guide compass.
 *
 * <ul>
 *   <li>Right click opens {@code /menu} (blocks with a GUI such as doors or chests still work normally;
 *       the compass itself is never "used", so it cannot be bound to a lodestone).</li>
 *   <li>It can live only in the player's own inventory: no chests (incl. shop chests), ender chests, crafting
 *       grids, anvils, bundles, item frames, armor stands, allays, decorated pots, shelves, hoppers.</li>
 *   <li>Dropping deletes it (or is blocked); on death it is not dropped and comes back after respawn (config).</li>
 * </ul>
 */
public final class GuideListener implements Listener {

    /** Blocks that take the held item on right click: the compass must not be inserted. */
    private static final Set<Material> INSERT_BLOCKS = EnumSet.of(Material.DECORATED_POT, Material.CHISELED_BOOKSHELF,
        Material.JUKEBOX, Material.LECTERN, Material.COMPOSTER, Material.FLOWER_POT, Material.CAULDRON,
        Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON, Material.VAULT,
        Material.LODESTONE, Material.RESPAWN_ANCHOR, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private final Plugin plugin;
    private final GuideService guide;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final Consumer<Player> openMenu;
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private final Set<UUID> returnOnRespawn = new HashSet<>();

    public GuideListener(final Plugin plugin, final GuideService guide, final Supplier<MenuSettings> settings,
                         final Supplier<Messages> messages, final Consumer<Player> openMenu) {
        this.plugin = plugin;
        this.guide = guide;
        this.settings = settings;
        this.messages = messages;
        this.openMenu = openMenu;
    }

    // ------------------------------------------------------------------ use

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!this.guide.isGuide(event.getItem())) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && !player.isSneaking()
            && usable(block.getType())) {
            event.setUseItemInHand(Event.Result.DENY); // open the door/chest, but never use the compass
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        this.open(player);
    }

    @SuppressWarnings("deprecation") // Material#isInteractable: no replacement that works on Material
    private static boolean usable(final Material type) {
        return type.isInteractable() && !INSERT_BLOCKS.contains(type) && !type.name().endsWith("_SHELF");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (this.guide.isGuide(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true); // item frames, allays, villagers, leashes...
            if (!(event instanceof PlayerInteractAtEntityEvent)) {
                this.open(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        if (this.guide.isGuide(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        if (this.guide.isGuide(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    private void open(final Player player) {
        final long now = System.currentTimeMillis();
        final Long last = this.lastOpen.get(player.getUniqueId());
        if (last != null && now - last < 400) {
            return; // main + off hand events, double clicks
        }
        this.lastOpen.put(player.getUniqueId(), now);
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.openMenu.accept(player);
            }
        });
    }

    // ------------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (top.getHolder(false) instanceof MenuHolder || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final ItemStack current = event.getCurrentItem();
        final ItemStack cursor = event.getCursor();
        final ItemStack hotbar = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
            ? player.getInventory().getItem(event.getHotbarButton()) : null;
        final ItemStack offhand = event.getClick() == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand() : null;
        final boolean guideCurrent = this.guide.isGuide(current);
        final boolean guideCursor = this.guide.isGuide(cursor);
        if (!guideCurrent && !guideCursor && !this.guide.isGuide(hotbar) && !this.guide.isGuide(offhand)) {
            return;
        }
        if (!this.settings.get().guideMovable) {
            event.setCancelled(true);
            return;
        }
        final boolean clickedTop = event.getClickedInventory() == top;
        if (clickedTop) {
            // Only taking a stray guide OUT of a container is allowed, never putting one in.
            final boolean takeOut = guideCurrent && !guideCursor && (cursor == null || cursor.getType().isAir())
                && (event.getAction() == InventoryAction.PICKUP_ALL || event.getAction() == InventoryAction.PICKUP_HALF
                    || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY);
            if (!takeOut) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.isShiftClick() && guideCurrent && top.getType() != InventoryType.CRAFTING) {
            event.setCancelled(true); // shift-click would move it into the open container
            return;
        }
        if (guideCursor && isBundle(current) || guideCurrent && isBundle(cursor)) {
            event.setCancelled(true);
        }
    }

    private static boolean isBundle(final ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!this.guide.isGuide(event.getOldCursor())) {
            return;
        }
        if (!this.settings.get().guideMovable) {
            event.setCancelled(true);
            return;
        }
        final int topSize = event.getView().getTopInventory().getSize();
        for (final int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        if (!this.settings.get().guideMovable
            && (this.guide.isGuide(event.getMainHandItem()) || this.guide.isGuide(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(final InventoryMoveItemEvent event) {
        if (this.guide.isGuide(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(final PrepareItemCraftEvent event) {
        for (final ItemStack item : event.getInventory().getMatrix()) {
            if (this.guide.isGuide(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ drop, pickup, death

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final Item drop = event.getItemDrop();
        if (!this.guide.isGuide(drop.getItemStack())) {
            return;
        }
        final Messages msg = this.messages.get();
        if (this.settings.get().guideDropMode == MenuSettings.DropMode.BLOCK) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg.get("guide-drop-blocked"));
            return;
        }
        drop.setItemStack(ItemStack.empty());
        drop.remove();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (drop.isValid()) {
                drop.remove();
            }
        });
        event.getPlayer().sendMessage(msg.get("guide-drop-deleted"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && this.guide.isGuide(event.getItem().getItemStack())
            && this.guide.has(player)) {
            event.setCancelled(true);
            event.getItem().remove(); // never two guides
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        if (!this.settings.get().guideKeepOnDeath) {
            return;
        }
        if (event.getDrops().removeIf(this.guide::isGuide)) {
            this.returnOnRespawn.add(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        if (!this.returnOnRespawn.remove(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && this.guide.give(player) == GuideService.GiveResult.GIVEN) {
                player.sendMessage(this.messages.get().get("guide-returned"));
            }
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.lastOpen.remove(event.getPlayer().getUniqueId());
    }
}
