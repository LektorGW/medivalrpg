// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Immutable snapshot of a country, safe to use from any thread (chat, tab list, voice chat...).
 *
 * @param id              stable country id
 * @param name            display name (no formatting)
 * @param color           country colour
 * @param ruler           ruler UUID; for an NPC country a synthetic id that is not a player
 * @param rulerName       last known ruler name (NPC ruler's display name for NPC countries)
 * @param members         player member UUID → rank (empty for a pure NPC country)
 * @param chunkCount      number of claimed chunks (all worlds)
 * @param allies          allied country ids
 * @param enemies         country ids at war with this one
 * @param npc             true when the country is run by the NPC AI (Armia plugin), not by players
 * @param capitalWorld    world name of the capital chunk
 * @param capitalX        capital chunk x
 * @param capitalZ        capital chunk z
 * @param taxPerCitizen   daily tax per player citizen, in copper (see SkarbiecApi); 0 = none
 * @param treasuryAccount Skarbiec account id of the country treasury ({@code "country:<id>"})
 */
public record KrajInfo(
    UUID id,
    String name,
    TextColor color,
    UUID ruler,
    String rulerName,
    Map<UUID, Rank> members,
    int chunkCount,
    Set<UUID> allies,
    Set<UUID> enemies,
    boolean npc,
    String capitalWorld,
    int capitalX,
    int capitalZ,
    long taxPerCitizen,
    String treasuryAccount
) {

    /** Colour as {@code #RRGGBB}. */
    public String hexColor() {
        return this.color.asHexString().toUpperCase(java.util.Locale.ROOT);
    }

    public int memberCount() {
        return this.members.size();
    }

    public Rank rankOf(final UUID player) {
        return this.members.get(player);
    }

    public boolean atWarWith(final UUID other) {
        return this.enemies.contains(other);
    }
}
