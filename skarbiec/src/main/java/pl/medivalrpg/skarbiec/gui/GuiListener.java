// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Routes inventory events of Skarbiec GUIs to their {@link Gui}. Runs first so nothing else moves items. */
public final class GuiListener implements Listener {

    private static Gui gui(final org.bukkit.inventory.InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof Gui g ? g : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        final Gui gui = gui(event.getView());
        if (gui != null) {
            try {
                gui.handleClick(event);
            } catch (final RuntimeException e) {
                event.setCancelled(true);
                throw e;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(final InventoryDragEvent event) {
        final Gui gui = gui(event.getView());
        if (gui != null) {
            try {
                gui.handleDrag(event);
            } catch (final RuntimeException e) {
                event.setCancelled(true);
                throw e;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        final Gui gui = gui(event.getView());
        if (gui != null && event.getPlayer() instanceof Player player) {
            gui.onClose(player);
        }
    }
}
