// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FoodReserveTest {
    @Test void emptyOrPartialReserveCannotFeedWholeVillage() {
        var r = new FoodReserve(4, 100, 10);
        assertFalse(r.sufficient()); assertEquals(4, r.available()); assertEquals(10, r.required());
    }
    @Test void realStoredFoodCanCoverTheNextMeal() {
        var r = new FoodReserve(10, 100, 10);
        assertTrue(r.sufficient());
    }
    @Test void physicalFoodComplementsExistingReserve() {
        var r = new FoodReserve(4, 100, 10); r.include(2, 5);
        assertTrue(r.sufficient()); assertEquals(10, r.available());
    }
    @Test void capacityAndWholeItemsMatchFeedingRules() {
        var r = new FoodReserve(8, 10, 10); r.include(64, 5);
        assertFalse(r.sufficient()); assertEquals(8, r.available());
        r.include(2, 1); assertTrue(r.sufficient());
    }
    @Test void capacityBelowVillageDemandDoesNotInventRoom() {
        var r = new FoodReserve(0, 5, 10); r.include(64, 1);
        assertEquals(5, r.available()); assertFalse(r.sufficient());
    }
    @Test void unknownFoodZeroDemandAndInvalidStacksAreHarmless() {
        var r = new FoodReserve(0, 100, 0); r.include(64, 5);
        assertTrue(r.sufficient()); assertEquals(0, r.available());
        var empty = new FoodReserve(0, 100, 10);
        empty.include(64, 0); empty.include(0, 5); empty.include(-1, 5);
        assertEquals(0, empty.available());
    }
    @Test void zeroCapacityStillAcceptsAlreadyAccountedLegacyReserve() {
        var r = new FoodReserve(10, 0, 10); assertTrue(r.sufficient());
    }
    @Test void largeFoodCountsUseLongWithoutOverflow() {
        var r = new FoodReserve(0, Long.MAX_VALUE, Long.MAX_VALUE);
        r.include(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals((long)Integer.MAX_VALUE * Integer.MAX_VALUE, r.available());
    }
}
