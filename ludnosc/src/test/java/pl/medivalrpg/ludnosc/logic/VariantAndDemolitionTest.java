// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Variant choice per plot and what a town redesign may demolish. */
class VariantAndDemolitionTest {

    @Test
    void samePlotSameVariantAndTownsGetEveryVariant() {
        assertEquals(VariantPick.start(3, "world", 120, -40), VariantPick.start(3, "world", 120, -40));
        assertEquals(VariantPick.start(3, "world", 120, -40), VariantPick.start(3, "world", 121, -39)); // same plot cell
        final Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 200; x += 12) {
            for (int z = 0; z < 200; z += 12) {
                final int i = VariantPick.start(3, "world", x, z);
                assertTrue(i >= 0 && i < 3);
                seen.add(i);
            }
        }
        assertEquals(3, seen.size(), "a town of plots uses every variant");
        assertEquals(0, VariantPick.start(1, "world", 5, 5));
        assertEquals(0, VariantPick.start(0, "world", 5, 5));
    }

    @Test
    void redesignNeverDemolishesPlayersWork() {
        // copies of players' buildings, registered houses, heritage, unknown templates: never
        assertFalse(DemolitionRules.demolishable(true, false, false, "kopia_1a2b3c4d_abc123", true, "AI"));
        assertFalse(DemolitionRules.demolishable(true, false, true, "dom", true, "AI"));
        assertFalse(DemolitionRules.demolishable(true, true, false, "kosciol", true, "AI"));
        assertFalse(DemolitionRules.demolishable(false, false, false, "dom", true, "AI"));
        // ordered by a player (name) or drawn in the panel plan: never, in any country
        assertFalse(DemolitionRules.demolishable(true, false, false, "dom", true, "WorldSteward"));
        assertFalse(DemolitionRules.demolishable(true, false, false, "dom", false, "plan"));
        // a player's country: unknown origin (older building) is protected, the planner's own work may go
        assertFalse(DemolitionRules.demolishable(true, false, false, "chata", false, null));
        assertTrue(DemolitionRules.demolishable(true, false, false, "chata", false, "przebudowa"));
        assertTrue(DemolitionRules.demolishable(true, false, false, "chata", false, "obywatele"));
        // an NPC country keeps the old behaviour for its planner buildings
        assertTrue(DemolitionRules.demolishable(true, false, false, "chata", true, null));
        assertTrue(DemolitionRules.demolishable(true, false, false, "chata", true, "AI"));
        assertTrue(DemolitionRules.playerOrigin("WorldSteward"));
        assertFalse(DemolitionRules.playerOrigin(null));
    }
}
