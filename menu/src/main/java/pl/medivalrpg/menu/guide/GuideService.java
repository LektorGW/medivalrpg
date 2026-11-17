// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.guide;

import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.gui.ItemFactory;

/** The "Przewodnik" compass: creation, recognition (PDC only) and handing out (one per player). */
public final class GuideService {

    public enum GiveResult { GIVEN, ALREADY_HAS, NO_SPACE, DISABLED }

    private final NamespacedKey key;
    private final Supplier<MenuSettings> settings;
    private final ItemFactory items;

    public GuideService(final Plugin plugin, final Supplier<MenuSettings> settings, final ItemFactory items) {
        this.key = new NamespacedKey(plugin, "guide");
        this.settings = settings;
        this.items = items;
    }

    public ItemStack create() {
        final MenuSettings s = this.settings.get();
        final Material material = this.items.material(s.guideMaterial, Material.COMPASS);
        final ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.customName(Messages.item(s.guideName, TagResolver.empty()));
            meta.lore(Messages.itemLines(s.guideLore, TagResolver.empty()));
            if (s.guideGlint) {
                meta.setEnchantmentGlintOverride(true);
            }
            meta.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
            if (meta instanceof CompassMeta compass && s.guidePointToSpawn) {
                final Location spawn = this.spawn(s);
                if (spawn != null) {
                    compass.setLodestone(spawn);
                    compass.setLodestoneTracked(false); // no lodestone block needed, never "breaks"
                }
            }
        });
        return item;
    }

    private Location spawn(final MenuSettings s) {
        World world = s.guideSpawnWorld.isEmpty() ? null : Bukkit.getWorld(s.guideSpawnWorld);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        return world == null ? null : world.getSpawnLocation();
    }

    public boolean isGuide(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
    }

    /** Guide anywhere in the inventory (incl. offhand/armor), on the cursor or in the 2×2 crafting grid. */
    public boolean has(final Player player) {
        for (final ItemStack item : player.getInventory().getContents()) {
            if (this.isGuide(item)) {
                return true;
            }
        }
        if (this.isGuide(player.getItemOnCursor())) {
            return true;
        }
        final org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (final ItemStack item : top.getContents()) {
                if (this.isGuide(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gives one guide: preferred hotbar slot when free, else the first free slot. Never drops it on the ground. */
    public GiveResult give(final Player player) {
        final MenuSettings s = this.settings.get();
        if (!s.guideEnabled) {
            return GiveResult.DISABLED;
        }
        if (this.has(player)) {
            return GiveResult.ALREADY_HAS;
        }
        final PlayerInventory inv = player.getInventory();
        int slot = s.guidePreferredSlot;
        final ItemStack current = inv.getItem(slot);
        if (current != null && !current.getType().isAir()) {
            slot = inv.firstEmpty();
        }
        if (slot < 0) {
            return GiveResult.NO_SPACE;
        }
        inv.setItem(slot, this.create());
        return GiveResult.GIVEN;
    }

    /** Removes every guide from the player's inventory (used when a duplicate shows up). */
    public int removeExtra(final Player player) {
        int found = 0;
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (this.isGuide(contents[i]) && found++ > 0) {
                player.getInventory().setItem(i, null);
            }
        }
        return Math.max(0, found - 1);
    }
}
