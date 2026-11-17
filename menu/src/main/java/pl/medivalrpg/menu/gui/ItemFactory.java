// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Builds GUI icons: name/lore without italics, hidden technical tooltips, optional glint and player heads. */
public final class ItemFactory {

    private final Logger logger;
    private final Set<String> warned = new HashSet<>();

    public ItemFactory(final Logger logger) {
        this.logger = logger;
    }

    /** Material by name; unknown or non-item → {@code fallback} (logged once). */
    public Material material(final String name, final Material fallback) {
        if (name == null) {
            return fallback;
        }
        final Material m = Material.matchMaterial(name);
        if (m == null || !m.isItem() || m.isAir()) {
            if (this.warned.add(name)) {
                this.logger.warning("Nieznany materiał '" + name + "' w menu – używam " + fallback + ".");
            }
            return fallback;
        }
        return m;
    }

    public ItemStack icon(final String materialName, final Component name, final List<Component> lore,
                          final boolean glow, final Player head) {
        final Material material = head != null ? Material.PLAYER_HEAD : this.material(materialName, Material.BARRIER);
        final ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.customName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (head != null && meta instanceof SkullMeta skull) {
                // the online profile carries the skin textures (SkinsRestorer in offline mode)
                skull.setPlayerProfile(head.getPlayerProfile());
            }
        });
        hideTechnicalTooltip(item);
        return item;
    }

    /**
     * Banner icon ({@code type: countries}). With {@code mark} (NPC countries) it gets a curly border and a circle
     * in that colour, so NPC and player countries differ at a glance. The pattern list is hidden in the tooltip.
     */
    public ItemStack banner(final String materialName, final DyeColor mark, final Component name,
                            final List<Component> lore) {
        final ItemStack item = this.icon(materialName, name, lore, false, null);
        if (mark != null) {
            item.editMeta(BannerMeta.class, meta -> {
                meta.addPattern(new Pattern(mark, PatternType.CIRCLE));
                meta.addPattern(new Pattern(mark, PatternType.CURLY_BORDER));
            });
            hideTechnicalTooltip(item); // the meta round trip must not bring the pattern lines back
        }
        return item;
    }

    /** Decoration pane without any tooltip. */
    public ItemStack decoration(final String materialName) {
        final ItemStack item = ItemStack.of(this.material(materialName, Material.GRAY_STAINED_GLASS_PANE));
        try {
            item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        } catch (final RuntimeException e) {
            item.editMeta(meta -> meta.customName(Component.space()));
        }
        return item;
    }

    private static void hideTechnicalTooltip(final ItemStack item) {
        try {
            item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.BANNER_PATTERNS,
                    DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS,
                    DataComponentTypes.POTION_CONTENTS, DataComponentTypes.MAP_ID, DataComponentTypes.INSTRUMENT,
                    DataComponentTypes.JUKEBOX_PLAYABLE, DataComponentTypes.TRIM, DataComponentTypes.DYED_COLOR)
                .build());
        } catch (final RuntimeException ignored) {
            // cosmetic only
        }
    }
}
