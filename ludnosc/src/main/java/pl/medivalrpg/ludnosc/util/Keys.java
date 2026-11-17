// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.util;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** PDC keys (namespace {@code ludnosc}) marking Ludnosc entities. */
public final class Keys {

    /** Villager → country UUID (string). */
    public final NamespacedKey country;
    /** Builder NPC → construction site UUID. */
    public final NamespacedKey builder;
    /** Site hologram → construction site UUID. */
    public final NamespacedKey hologram;

    /** Villager → name given by Ludnosc (to remove the custom name when the villager is released). */
    public final NamespacedKey citizenName;

    public Keys(final Plugin plugin) {
        this.country = new NamespacedKey(plugin, "country");
        this.citizenName = new NamespacedKey(plugin, "name");
        this.builder = new NamespacedKey(plugin, "builder");
        this.hologram = new NamespacedKey(plugin, "site_hologram");
    }

    public static UUID getUuid(final Entity entity, final NamespacedKey key) {
        final PersistentDataContainer pdc = entity.getPersistentDataContainer();
        final String s = pdc.get(key, PersistentDataType.STRING);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public static void setUuid(final Entity entity, final NamespacedKey key, final UUID value) {
        if (value == null) {
            entity.getPersistentDataContainer().remove(key);
        } else {
            entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, value.toString());
        }
    }
}
