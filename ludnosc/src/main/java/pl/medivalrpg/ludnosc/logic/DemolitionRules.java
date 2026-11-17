// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Set;

/**
 * Which registered buildings a town redesign may pull down (pure, unit tested). Never: unknown templates, heritage,
 * houses registered by an admin, copies of players' buildings, anything a player ordered — and, in a player's country,
 * nothing whose origin is unknown (built before origins were recorded): only what the planner itself put up.
 */
public final class DemolitionRules {

    /** Orderers that are the plugin itself (planner, redesign, citizens' huts, clean-up, admin API). */
    public static final Set<String> SYSTEM = Set.of("AI", "przebudowa", "obywatele", "porządki", "API");

    private DemolitionRules() {
    }

    public static boolean playerOrigin(final String origin) {
        return origin != null && !SYSTEM.contains(origin);
    }

    public static boolean demolishable(final boolean knownTemplate, final boolean heritage, final boolean registered,
                                       final String type, final boolean npcCountry, final String origin) {
        if (!knownTemplate || heritage || registered || BlueprintRules.isBlueprintId(type) || playerOrigin(origin)) {
            return false;
        }
        return npcCountry || origin != null;
    }
}
