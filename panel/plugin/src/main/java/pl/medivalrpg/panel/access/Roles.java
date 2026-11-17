// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.access;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.PanelConfig;
import pl.medivalrpg.panel.data.PanelData;

/**
 * Builds the {@link Actor} of one call from the live state: rank from Kraje, admin / NPC manager from the config
 * lists (and {@code isOp()} only with {@code ops-are-admins}), assigned NPC countries from data.json.
 * Offline players have no Bukkit permissions, so nothing here relies on them. Main thread.
 */
public final class Roles {

    private final Supplier<PanelConfig> config;
    private final Supplier<KrajeApi> kraje;
    private final PanelData data;

    public Roles(final Supplier<PanelConfig> config, final Supplier<KrajeApi> kraje, final PanelData data) {
        this.config = config;
        this.kraje = kraje;
        this.data = data;
    }

    /** Current name of the player (server cache), else {@code fallback}. */
    public static String canonicalName(final UUID uuid, final String fallback) {
        final OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        final String name = p.getName();
        return name == null || name.isBlank() ? fallback : name;
    }

    public boolean isAdmin(final UUID uuid, final String name) {
        final PanelConfig cfg = this.config.get();
        if (name != null && cfg.admins.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return cfg.opsAreAdmins && uuid != null && Bukkit.getOfflinePlayer(uuid).isOp();
    }

    public boolean isGlobalNpcManager(final String name) {
        return name != null && this.config.get().npcManagers.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Ids of all NPC countries. */
    public Set<UUID> npcCountries() {
        final Set<UUID> out = new HashSet<>();
        for (final KrajInfo k : this.kraje.get().getCountries()) {
            if (k.npc()) {
                out.add(k.id());
            }
        }
        return out;
    }

    public Actor actor(final UUID uuid, final String sessionName) {
        final String name = canonicalName(uuid, sessionName);
        final KrajeApi api = this.kraje.get();
        final KrajInfo country = api.getCountryOf(uuid).orElse(null);
        final Rank rank = country == null ? null : country.rankOf(uuid);
        final Set<UUID> managed = new HashSet<>(this.data.managedBy(uuid));
        managed.retainAll(this.npcCountries());
        return new Actor(uuid, name, this.isAdmin(uuid, name), this.isGlobalNpcManager(name), managed,
            country == null ? null : country.id(), rank);
    }
}
