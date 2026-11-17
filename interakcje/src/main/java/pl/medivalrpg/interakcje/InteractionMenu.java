// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 27-slot "Interakcje z <nick>" menu. Recognised in listeners by this holder class. */
public final class InteractionMenu implements InventoryHolder {
    public static final int SIZE = 27;

    private final UUID target;
    private final Map<Integer, String> slots = new HashMap<>();
    private final Inventory inventory;

    private InteractionMenu(InterakcjePlugin plugin, Player viewer, Player target) {
        this.target = target.getUniqueId();
        Messages msg = plugin.messages();
        Component title = msg.get("menu-title", Messages.component("target", plugin.displayName(target)));
        this.inventory = Bukkit.createInventory(this, SIZE, title);

        List<InteractionDef> defs = new ArrayList<>();
        for (InteractionDef d : plugin.interactions().values()) {
            if (d.enabled() && viewer.hasPermission(d.permission())) {
                defs.add(d);
            }
        }
        List<Integer> auto = autoSlots(defs.size());
        int autoIndex = 0;
        for (InteractionDef d : defs) {
            int slot = d.slot();
            if (slot < 0 || slots.containsKey(slot)) {
                while (autoIndex < auto.size() && slots.containsKey(auto.get(autoIndex))) {
                    autoIndex++;
                }
                if (autoIndex >= auto.size()) {
                    plugin.getLogger().warning("Menu: brak miejsca na interakcję " + d.id());
                    continue;
                }
                slot = auto.get(autoIndex++);
            }
            slots.put(slot, d.id());
            inventory.setItem(slot, icon(plugin, d, target));
        }

        Material filler = Material.matchMaterial(plugin.settings().menuFiller());
        if (filler != null && filler.isItem() && !filler.isAir()) {
            ItemStack pane = new ItemStack(filler);
            pane.editMeta(m -> m.setHideTooltip(true));
            for (int i = 0; i < SIZE; i++) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, pane);
                }
            }
        }
    }

    public static void open(InterakcjePlugin plugin, Player viewer, Player target) {
        viewer.openInventory(new InteractionMenu(plugin, viewer, target).inventory);
    }

    /** Up to 7 icons centred in the middle row, up to 21 in the inner columns, then every slot in order. */
    static List<Integer> autoSlots(int count) {
        List<Integer> out = new ArrayList<>();
        if (count <= 7) {
            int start = 9 + (9 - count) / 2;
            for (int i = 0; i < count; i++) {
                out.add(start + i);
            }
            // fill the rest of the inventory as fallback for icons with a clashing fixed slot
        } else {
            // more than one row: the inner seven columns of all three rows (borders stay free)
            for (int row = 0; row < 3; row++) {
                for (int col = 1; col <= 7; col++) {
                    out.add(row * 9 + col);
                }
            }
        }
        for (int i = 0; i < SIZE; i++) {
            if (!out.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    private static ItemStack icon(InterakcjePlugin plugin, InteractionDef d, Player target) {
        Messages msg = plugin.messages();
        Material mat = Material.matchMaterial(d.icon());
        if (mat == null || !mat.isItem() || mat.isAir()) {
            plugin.getLogger().warning("Nieznana ikona '" + d.icon() + "' interakcji " + d.id() + " – używam PAPER.");
            mat = Material.PAPER;
        }
        ItemStack item = new ItemStack(mat);
        var names = Messages.component("target", plugin.displayName(target));
        List<Component> lore = new ArrayList<>();
        if (!d.description().isEmpty()) {
            lore.add(msg.item("<gray>" + d.description(), names)); // admin-provided MiniMessage
        }
        lore.add(msg.item(msg.raw(d.consent() ? "menu-lore-consent" : "menu-lore-no-consent"), names));
        lore.add(msg.item(msg.raw("menu-lore-command"), names, Messages.text("command", d.command())));
        lore.add(Component.empty());
        lore.add(msg.item(msg.raw("menu-lore-click"), names));
        item.editMeta(m -> {
            m.displayName(msg.item(d.name(), names));
            m.lore(lore);
        });
        try {
            item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.BANNER_PATTERNS)
                    .build());
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
        return item;
    }

    public UUID target() {
        return target;
    }

    /** Interaction id in this slot, or {@code null}. */
    public String interactionAt(int slot) {
        return slots.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
