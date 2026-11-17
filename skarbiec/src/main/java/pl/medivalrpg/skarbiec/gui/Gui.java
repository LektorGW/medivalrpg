// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/**
 * Base of every Skarbiec chest GUI. The inventory's holder is the GUI itself, so {@link GuiListener} can
 * recognise it. By default every click and drag is cancelled (no item ever moves) and only clicks on the top
 * inventory reach {@link #onClick}; {@link pl.medivalrpg.skarbiec.trade.TradeSession} overrides
 * {@link #handleClick}/{@link #handleDrag} to allow controlled item placement.
 */
public abstract class Gui implements InventoryHolder {

    protected final Inventory inventory;

    protected Gui(final int rows, final Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    /** A click on slot {@code slot} of the top inventory (already cancelled). */
    protected abstract void onClick(Player player, int slot, ClickType click);

    /** The player closed this GUI (or was disconnected). */
    public void onClose(final Player player) {
    }

    public void handleClick(final InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == this.inventory && event.getWhoClicked() instanceof Player player) {
            this.onClick(player, event.getSlot(), event.getClick());
        }
    }

    public void handleDrag(final InventoryDragEvent event) {
        event.setCancelled(true);
    }

    public void open(final Player player) {
        player.openInventory(this.inventory);
    }

    // ------------------------------------------------------------------ item helpers

    public static ItemStack icon(final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.lore(lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    public static ItemStack filler(final Material material) {
        final ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
        });
        return item;
    }
}
