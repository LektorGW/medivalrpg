// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Full lock of Menu GUIs: every click (shift, number keys, double click, offhand swap, drop) and every drag is
 * cancelled while a {@link MenuHolder} is open; only clicks on top slots with a handler do something,
 * on the next tick and with an anti-spam cooldown.
 */
public final class MenuListener implements Listener {

    private final MenuService service;
    private final LongSupplier cooldownMs;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public MenuListener(final MenuService service, final LongSupplier cooldownMs) {
        this.service = service;
        this.cooldownMs = cooldownMs;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT
            && click != ClickType.SHIFT_RIGHT) {
            return; // number keys, drops, double clicks, creative middle click...
        }
        final BiConsumer<Player, ClickType> handler = holder.handler(event.getRawSlot());
        if (handler == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastClick.get(player.getUniqueId());
        if (last != null && now - last < this.cooldownMs.getAsLong()) {
            return;
        }
        this.lastClick.put(player.getUniqueId(), now);
        this.service.later(() -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == holder) {
                handler.accept(player, click);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(final InventoryMoveItemEvent event) {
        if (event.getSource().getHolder(false) instanceof MenuHolder
            || event.getDestination().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.lastClick.remove(event.getPlayer().getUniqueId());
    }
}
