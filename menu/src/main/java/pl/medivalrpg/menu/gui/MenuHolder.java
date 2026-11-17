// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Holder of every Menu GUI. {@link MenuListener} recognises it and cancels all clicks/drags, so items can never
 * be taken out; clicks on top slots run the handler registered for that slot.
 */
public final class MenuHolder implements InventoryHolder {

    private final String menuId;
    private final Inventory inventory;
    private final Map<Integer, BiConsumer<Player, ClickType>> handlers = new HashMap<>();

    public MenuHolder(final String menuId, final int size, final Component title) {
        this.menuId = menuId;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public String menuId() {
        return this.menuId;
    }

    public void set(final int slot, final ItemStack item, final BiConsumer<Player, ClickType> handler) {
        this.inventory.setItem(slot, item);
        if (handler == null) {
            this.handlers.remove(slot);
        } else {
            this.handlers.put(slot, handler);
        }
    }

    public BiConsumer<Player, ClickType> handler(final int slot) {
        return this.handlers.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
