// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.access;

import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.kraje.model.Rank;

/**
 * The panel user as seen by the plugin at the moment of one RPC call (recomputed for every call from the current
 * Kraje state and the Panel role lists — never trusted from the browser or the Worker).
 *
 * @param uuid                player UUID (from the Worker session)
 * @param name                player name
 * @param admin               panel admin ({@code admins} list, or op with {@code ops-are-admins: true})
 * @param npcManager          global NPC manager ({@code npc-managers})
 * @param managedNpcCountries NPC countries assigned to this player
 * @param countryId           the player's country (null = none)
 * @param rank                rank in that country (null = none)
 */
public record Actor(UUID uuid, String name, boolean admin, boolean npcManager, Set<UUID> managedNpcCountries,
                    UUID countryId, Rank rank) {

    public Actor {
        managedNpcCountries = managedNpcCountries == null ? Set.of() : Set.copyOf(managedNpcCountries);
    }

    /** Rank in {@code country}, or null when the player is not its member. */
    public Rank rankIn(final UUID country) {
        return country != null && country.equals(this.countryId) ? this.rank : null;
    }
}
