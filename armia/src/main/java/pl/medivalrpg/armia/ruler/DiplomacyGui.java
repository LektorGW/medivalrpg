// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ruler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.medivalrpg.armia.config.Messages;

/**
 * Diplomacy window of an NPC ruler. Own {@link InventoryHolder}, so {@link GuiListener} can block every way of
 * moving items. Two screens: main (info + actions) and tribute amounts.
 */
public final class DiplomacyGui implements InventoryHolder {

    public enum Screen { MAIN, TRIBUTE }

    public static final int SLOT_INFO = 4;
    public static final int SLOT_ALLIANCE = 10;
    public static final int SLOT_PEACE = 12;
    public static final int SLOT_TRIBUTE = 14;
    public static final int SLOT_WAR = 16;
    public static final int SLOT_CLOSE = 22;
    /** "Porozmawiaj" — shown only while the Wladcy plugin is enabled (runs {@code /wladca rozmowa} as the player). */
    public static final int SLOT_TALK = 18;
    public static final int[] TRIBUTE_SLOTS = {10, 12, 14, 16};

    final UUID npc;
    final UUID viewer;
    final boolean diplomat;
    final Screen screen;
    final List<Long> amounts;
    long warConfirmUntil;
    private final Inventory inventory;

    DiplomacyGui(final UUID npc, final UUID viewer, final boolean diplomat, final Screen screen, final Component title, final List<Long> amounts) {
        this.npc = npc;
        this.viewer = viewer;
        this.diplomat = diplomat;
        this.screen = screen;
        this.amounts = amounts;
        this.inventory = Bukkit.createInventory(this, 27, title);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    void set(final int slot, final ItemStack item) {
        this.inventory.setItem(slot, item);
    }

    void fill(final Material filler) {
        final ItemStack pane = item(filler, Component.space(), List.of());
        for (int i = 0; i < this.inventory.getSize(); i++) {
            if (this.inventory.getItem(i) == null) {
                this.inventory.setItem(i, pane);
            }
        }
    }

    static ItemStack item(final Material material, final Component name, final List<Component> lore) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        final List<Component> l = new ArrayList<>();
        for (final Component c : lore) {
            l.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(l);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack item(final Messages m, final Material material, final String key, final TagResolver... tags) {
        final List<Component> lore = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            final String k = key + "-lore-" + i;
            if (m.has(k) && !m.raw(k).isEmpty()) {
                lore.add(m.get(k, tags));
            }
        }
        return item(material, m.get(key, tags), lore);
    }

    static TagResolver tag(final String name, final String value) {
        return Placeholder.unparsed(name, value);
    }
}
