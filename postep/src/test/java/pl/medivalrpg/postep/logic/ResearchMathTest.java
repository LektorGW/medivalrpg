// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchMathTest {

    private static final double EPS = 1e-9;

    private static ResearchMath.Settings settings() {
        final Map<String, Double> b = new LinkedHashMap<>();
        b.put("biblioteka", 4.0);
        b.put("szkoła", 3.0);   // diacritics in the config are normalised
        b.put("uniwersytet", 10.0);
        b.put("kosciol", 1.0);
        b.put("katedra", 3.0);
        return new ResearchMath.Settings(3, 0.15, 100, 2, 5, b, 3, 10, 5, 15, 15, 45, 10, 0.25, 1.5);
    }

    private static ResearchMath.Input input(final int pop, final int players, final Map<String, Integer> buildings,
                                            final int allies, final int wars, final boolean vassal, final boolean npc) {
        return new ResearchMath.Input(pop, players, buildings, allies, wars, vassal, npc, 1.0, 0.0);
    }

    @Test
    void baseOnly() {
        final ResearchMath.Breakdown b = ResearchMath.daily(settings(), input(0, 0, Map.of(), 0, 1, false, false));
        assertEquals(3.0, b.raw(), EPS);
        // one war: -15 %, no peace bonus
        assertEquals(3.0 * 0.85, b.total(), EPS);
    }

    @Test
    void populationPlayersAndCaps() {
        final ResearchMath.Breakdown b = ResearchMath.daily(settings(), input(40, 3, Map.of(), 0, 1, false, false));
        assertEquals(6.0, b.population(), EPS);
        assertEquals(6.0, b.players(), EPS);
        final ResearchMath.Breakdown capped = ResearchMath.daily(settings(), input(1000, 50, Map.of(), 0, 1, false, false));
        assertEquals(15.0, capped.population(), EPS); // 100 × 0.15
        assertEquals(10.0, capped.players(), EPS);    // 5 × 2
    }

    @Test
    void buildingsMatchLongestKeyAndCapPerType() {
        final Map<String, Integer> buildings = new LinkedHashMap<>();
        buildings.put("biblioteka", 5);                 // capped at 3 → 12
        buildings.put("uniwersytet_renesansowy", 1);    // future type → uniwersytet 10
        buildings.put("Szkoła", 1);                     // diacritics/case → 3
        buildings.put("katedra_gotycka", 1);            // 3
        buildings.put("chata", 4);                      // no points
        final ResearchMath.Breakdown b = ResearchMath.daily(settings(), input(0, 0, buildings, 0, 1, false, false));
        assertEquals(12 + 10 + 3 + 3, b.buildings(), EPS);
        assertEquals(12.0, b.buildingDetail().get("biblioteka"), EPS);
        assertNull(b.buildingDetail().get("chata"));
    }

    @Test
    void matchPrefersLongestKey() {
        final Map<String, Double> keys = Map.of("kosciol", 1.0, "kosciol_gotycki", 5.0);
        assertEquals("kosciol_gotycki", ResearchMath.matchBuilding("kościół_gotycki_duży", keys));
        assertEquals("kosciol", ResearchMath.matchBuilding("kosciol", keys));
        assertNull(ResearchMath.matchBuilding("dom", keys));
    }

    @Test
    void peaceAlliancesWarsAndVassal() {
        final ResearchMath.Settings s = settings();
        // peace +10, 2 allies +10 → +20 %
        assertEquals(3.0 * 1.2, ResearchMath.daily(s, input(0, 0, Map.of(), 2, 0, false, false)).total(), EPS);
        // allies capped at +15, peace +10 → +25 %
        assertEquals(3.0 * 1.25, ResearchMath.daily(s, input(0, 0, Map.of(), 9, 0, false, false)).total(), EPS);
        // 5 wars capped at -45 %
        assertEquals(3.0 * 0.55, ResearchMath.daily(s, input(0, 0, Map.of(), 0, 5, false, false)).total(), EPS);
        // vassal in peace: +10 - 10 = 0
        assertEquals(3.0, ResearchMath.daily(s, input(0, 0, Map.of(), 0, 0, true, false)).total(), EPS);
    }

    @Test
    void multipliersEraDiscoveriesNpcAndFloor() {
        final ResearchMath.Settings s = settings();
        final ResearchMath.Input in = new ResearchMath.Input(0, 0, Map.of(), 0, 0, false, true, 1.5, 20.0);
        // (1 + (10 peace + 20 research)/100) × 1.5 era × 1.5 npc
        assertEquals(3.0 * 1.3 * 1.5 * 1.5, ResearchMath.daily(s, in).total(), EPS);
        final ResearchMath.Input awful = new ResearchMath.Input(0, 0, Map.of(), 0, 3, true, false, 1.0, -200.0);
        assertEquals(3.0 * 0.25, ResearchMath.daily(s, awful).total(), EPS);
    }
}
