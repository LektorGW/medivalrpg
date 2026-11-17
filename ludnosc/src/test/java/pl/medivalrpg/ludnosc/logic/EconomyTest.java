// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EconomyTest {

    /** 4 food/citizen, farmer 10, NPC 10 + 5/citizen, tax 3 md, farmer ×1, librarian ×1.5, none ×0.6. */
    private static EconomyRules rules() {
        return new EconomyRules(4, 10, 10, 5, false, 3,
            Map.of("farmer", 1.0, "librarian", 1.5, "none", 0.6), 0.0, 0.3, 0.5,
            2, 0.2, 3, 0.25, 5);
    }

    private static Economy.DayInput in(final Map<String, Integer> adults, final int babies, final int housing, final long food,
                                       final long capacity, final boolean npc, final int hungry, final int homeless) {
        return new Economy.DayInput(adults, babies, housing, food, capacity, npc, hungry, homeless);
    }

    @Test
    void professionKeyNormalises() {
        assertEquals("farmer", Economy.professionKey("minecraft:farmer"));
        assertEquals("farmer", Economy.professionKey("FARMER"));
        assertEquals("none", Economy.professionKey(null));
        assertEquals("none", Economy.professionKey(" "));
        assertEquals(1.5, rules().multiplier("minecraft:librarian"));
        assertEquals(1.0, rules().multiplier("minecraft:mason"), "unknown profession = 1.0");
    }

    @Test
    void fedAndHousedCountryPaysFullTax() {
        // 2 farmers + 2 librarians + 1 unemployed: 3*(2*1 + 2*1.5 + 0.6) = 16.8 → 17
        final var r = Economy.day(in(Map.of("farmer", 2, "librarian", 2, "none", 1), 0, 5, 100, 1000, false, 0, 0), rules());
        assertEquals(17, r.tax());
        assertEquals(20, r.produced());
        assertEquals(20, r.eaten());
        assertEquals(100, r.food());
        assertTrue(r.fed());
        assertEquals(0, r.departures());
        assertEquals(0, r.hungryDays());
        assertEquals(0, r.homelessDays());
    }

    @Test
    void bakeriesAndHarboursAddFood() {
        final var plain = new Economy.DayInput(Map.of("none", 2), 0, 2, 0, 1000, false, 0, 0, 0, 0);
        final var bonus = new Economy.DayInput(Map.of("none", 2), 0, 2, 0, 1000, false, 0, 0, 0, 22);
        assertEquals(Economy.production(plain, rules()) + 22, Economy.production(bonus, rules()));
        assertTrue(Economy.day(bonus, rules()).fed() || !Economy.day(plain, rules()).fed(), "the bonus never makes things worse");
        assertEquals(0, Economy.production(new Economy.DayInput(Map.of(), 0, 0, 0, 0, false, 0, 0, 0, -5), rules()),
            "a negative bonus (bad mood) never makes the production negative");
    }

    @Test
    void babiesUseBabyMultiplier() {
        final var r = Economy.day(in(Map.of("farmer", 1), 3, 10, 100, 1000, false, 0, 0), rules());
        assertEquals(3, r.tax(), "babies pay nothing with multiplier 0");
        assertEquals(16, r.eaten(), "but they eat");
    }

    @Test
    void granaryCapacityCapsProductionButNeverDestroysFood() {
        final var r = Economy.day(in(Map.of("farmer", 10), 0, 10, 90, 100, false, 0, 0), rules());
        assertEquals(10, r.produced());
        assertEquals(60, r.food());
        final var over = Economy.day(in(Map.of("farmer", 1), 0, 10, 500, 100, false, 0, 0), rules());
        assertEquals(0, over.produced());
        assertEquals(496, over.food(), "stock above capacity (capacity lowered) is kept");
    }

    @Test
    void hungerReducesTaxLinearly() {
        // 10 unemployed need 40 food, have 20 → fed 0.5 → factor 0.3 + 0.7*0.5 = 0.65; gross 18 → 11.7 → 12
        final var r = Economy.day(in(Map.of("none", 10), 0, 10, 20, 1000, false, 0, 0), rules());
        assertEquals(0.5, r.fedRatio(), 1e-9);
        assertEquals(12, r.tax());
        assertEquals(0, r.food());
        assertEquals(1, r.hungryDays());
        assertFalse(r.fed());
        assertEquals(0, r.departures(), "within grace days");
    }

    @Test
    void longHungerMakesCitizensLeave() {
        // third hungry day (> 2 grace), nobody fed: ceil(10 * 0.2 * 1.0) = 2
        final var r = Economy.day(in(Map.of("none", 10), 0, 10, 0, 1000, false, 2, 0), rules());
        assertEquals(3, r.hungryDays());
        assertEquals(2, r.departures());
        // starving: tax at 30 %
        assertEquals(Math.round(18 * 0.3), r.tax());
    }

    @Test
    void homelessPayLessAndLeaveAfterGrace() {
        // 8 farmers, 4 beds: housing factor 0.5 + 0.5*0.5 = 0.75; gross 24 → 18
        final var r = Economy.day(in(Map.of("farmer", 8), 0, 4, 1000, 2000, false, 0, 0), rules());
        assertEquals(18, r.tax());
        assertEquals(1, r.homelessDays());
        assertEquals(0, r.departures());
        // 4th day without beds → ceil(4 homeless * 0.25) = 1
        final var later = Economy.day(in(Map.of("farmer", 8), 0, 4, 1000, 2000, false, 0, 3), rules());
        assertEquals(1, later.departures());
        // beds again → counter resets
        assertEquals(0, Economy.day(in(Map.of("farmer", 4), 0, 4, 1000, 2000, false, 0, 3), rules()).homelessDays());
    }

    @Test
    void departuresAreCapped() {
        final var r = Economy.day(in(Map.of("none", 100), 0, 0, 0, 0, false, 10, 10), rules());
        assertEquals(5, r.departures());
    }

    @Test
    void npcCountriesGetVirtualFood() {
        // 4 citizens: 10 + 4*5 = 30 produced, 16 eaten
        final var r = Economy.day(in(Map.of("none", 4), 0, 4, 0, 1000, true, 0, 0), rules());
        assertEquals(30, r.produced());
        assertEquals(14, r.food());
        assertTrue(r.fed());
        // capacity still applies unless ignore-capacity
        assertEquals(5, Economy.day(in(Map.of("none", 4), 0, 4, 0, 5, true, 0, 0), rules()).produced());
        final EconomyRules uncapped = new EconomyRules(4, 10, 10, 5, true, 3, Map.of(), 0, 0.3, 0.5, 2, 0.2, 3, 0.25, 5);
        assertEquals(30, Economy.day(in(Map.of("none", 4), 0, 4, 0, 5, true, 0, 0), uncapped).produced());
    }

    @Test
    void emptyCountryIsNeutral() {
        final var r = Economy.day(in(Map.of(), 0, 0, 50, 100, false, 0, 0), rules());
        assertEquals(0, r.tax());
        assertEquals(50, r.food());
        assertEquals(0, r.departures());
        assertEquals(0L, Economy.expectedTax(in(Map.of(), 0, 0, 0, 0, false, 0, 0), rules()));
    }

    @Test
    void growthNeedsBedsAndFood() {
        assertEquals(0, Growth.newSettlers(4, 0, 4, 1000, 20, 1), "no free bed");
        assertEquals(1, Growth.newSettlers(3, 0, 4, 1000, 20, 1));
        assertEquals(0, Growth.newSettlers(3, 1, 4, 1000, 20, 1), "pending settler takes the bed");
        assertEquals(0, Growth.newSettlers(0, 0, 10, 19, 20, 3), "not enough food");
        assertEquals(2, Growth.newSettlers(0, 0, 10, 45, 20, 3), "food limits");
        assertEquals(3, Growth.newSettlers(0, 0, 10, 1000, 20, 3), "per-tick cap");
        assertEquals(2, Growth.newSettlers(8, 0, 10, 1000, 0, 5), "free beds limit, free settlers");
        assertEquals(0, Growth.newSettlers(0, 0, 10, 1000, 20, 0));
    }

    @Test
    void namePoolDealsWithoutRepetition() {
        final NamePool pool = new NamePool(java.util.List.of("A", "B", "C", " "), new java.util.Random(1));
        assertEquals(3, pool.size());
        final java.util.Set<String> first = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            first.add(pool.next());
        }
        assertEquals(java.util.Set.of("A", "B", "C"), first);
        assertEquals("Osadnik", new NamePool(java.util.List.of(), new java.util.Random()).next());
    }
}
