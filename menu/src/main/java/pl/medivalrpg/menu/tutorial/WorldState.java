// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import java.util.Set;

/**
 * What the game knows about a player's start, read from the other plugins' APIs. Every value is {@code null} when it
 * could not be read (plugin missing, older API), so a rule can tell "not yet" from "unknown".
 *
 * @param money        bank account plus coins in the inventory, in copper (Skarbiec)
 * @param hasCountry   the player belongs to a country (Kraje)
 * @param chunks       chunks of that country
 * @param housing      beds in the country's housing (Ludnosc)
 * @param buildings    building types the country has, out of {@link TutorialRules#WATCHED_BUILDINGS}
 * @param construction the country has any building or construction site
 * @param soldiers     soldiers of the country (Armia)
 */
public record WorldState(Long money, Boolean hasCountry, Integer chunks, Integer housing, Set<String> buildings,
                         Boolean construction, Integer soldiers) {

    /** Nothing could be read. */
    public static final WorldState UNKNOWN = new WorldState(null, null, null, null, null, null, null);
}
