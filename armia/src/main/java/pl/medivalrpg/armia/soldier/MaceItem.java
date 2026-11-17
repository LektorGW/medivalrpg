// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;

/**
 * "Buława dowódcy": a plain item (default a stick drawn with the vanilla mace model, so it has no mace combat
 * powers) recognised only by its PDC tag.
 */
public final class MaceItem {

    private final Keys keys;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;

    public MaceItem(final Keys keys, final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.keys = keys;
        this.config = config;
        this.messages = messages;
    }

    public ItemStack create() {
        final ArmiaConfig cfg = this.config.get();
        final Messages m = this.messages.get();
        final ItemStack item = new ItemStack(cfg.maceMaterial);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(m.get("mace-name").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        final List<Component> lore = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            final String key = "mace-lore-" + i;
            if (m.has(key) && !m.raw(key).isEmpty()) {
                lore.add(m.get(key).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
        }
        meta.lore(lore);
        if (cfg.maceModel != null) {
            meta.setItemModel(cfg.maceModel);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(this.keys.mace, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isMace(final ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(this.keys.mace, PersistentDataType.BYTE);
    }
}
