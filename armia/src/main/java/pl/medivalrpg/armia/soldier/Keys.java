// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** PDC keys (namespace {@code armia}) on every entity and item the plugin creates. */
public final class Keys {

    public static final String PART_BRAIN = "brain";
    public static final String PART_BODY = "body";
    public static final String PART_RULER = "ruler";
    public static final String PART_RULER_HITBOX = "ruler_hitbox";
    public static final String PART_ARROW = "arrow";
    public static final String PART_SIEGE_MODEL = "siege_model";
    public static final String PART_SIEGE_SHOT = "siege_shot";

    public final NamespacedKey soldier;
    public final NamespacedKey country;
    public final NamespacedKey part;
    public final NamespacedKey mace;

    public Keys(final Plugin plugin) {
        this.soldier = new NamespacedKey(plugin, "soldier");
        this.country = new NamespacedKey(plugin, "country");
        this.part = new NamespacedKey(plugin, "part");
        this.mace = new NamespacedKey(plugin, "mace");
    }

    public void tag(final Entity entity, final UUID soldierId, final UUID countryId, final String part) {
        final PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (soldierId != null) {
            pdc.set(this.soldier, PersistentDataType.STRING, soldierId.toString());
        }
        if (countryId != null) {
            pdc.set(this.country, PersistentDataType.STRING, countryId.toString());
        }
        pdc.set(this.part, PersistentDataType.STRING, part);
    }

    public String part(final Entity entity) {
        return entity.getPersistentDataContainer().get(this.part, PersistentDataType.STRING);
    }

    public UUID soldierId(final Entity entity) {
        return uuid(entity.getPersistentDataContainer().get(this.soldier, PersistentDataType.STRING));
    }

    public UUID countryId(final Entity entity) {
        return uuid(entity.getPersistentDataContainer().get(this.country, PersistentDataType.STRING));
    }

    static UUID uuid(final String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
