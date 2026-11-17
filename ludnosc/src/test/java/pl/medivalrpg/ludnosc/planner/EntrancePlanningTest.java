// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntrancePlanningTest {
    private static class Land implements TerrainView {
        @Override public int height(final int x, final int z) { return 64; }
        @Override public boolean liquid(final int x, final int z) { return false; }
        @Override public boolean obstacle(final int x, final int z) { return false; }
        @Override public boolean own(final int x, final int z) { return true; }
    }

    @Test
    void isolatedWaterAndObstaclesCannotHideBetweenHeightSamples() {
        final Land obstacle = new Land() {
            @Override public boolean obstacle(final int x, final int z) { return x == 1 && z == 0; }
        };
        assertNull(SiteFinder.evaluate(obstacle, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3));
        final Land water = new Land() {
            @Override public boolean liquid(final int x, final int z) { return x == 1 && z == 0; }
        };
        assertNull(SiteFinder.evaluate(water, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3));
        assertNull(SiteFinder.evaluate(new Land(), 5, 5, 0, 0, 0, 0, "inner", List.of(),
            Set.of(RoadPlanner.key(1, 0)), 0, 3));
    }

    @Test
    void isolatedCliffCannotHideBetweenHeightSamples() {
        final Land cliff = new Land() {
            @Override public int height(final int x, final int z) { return x == 1 && z == 0 ? 30 : 64; }
        };
        assertNull(SiteFinder.evaluate(cliff, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3));
    }

    @Test
    void squareBuildingCanTurnItsDoorTowardsTheOnlyClearApproach() {
        final Land land = new Land() {
            @Override public boolean obstacle(final int x, final int z) {
                return x == -3 && z == 0 || x == 0 && Math.abs(z) == 3;
            }
        };
        final SiteFinder.Spot spot = SiteFinder.evaluate(land, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3,
            0, new SiteFinder.Relax(0, 0, 0, 0, true), new SiteFinder.Stats());
        assertNotNull(spot);
        assertEquals(3, spot.rotation());
        assertEquals(3, spot.doorX());
        assertEquals(0, spot.doorZ());
    }

    @Test
    void entranceMustReachOwnLandAndAvoidAbruptSteps() {
        final Land foreign = new Land() {
            @Override public boolean own(final int x, final int z) { return z <= 3; }
        };
        assertNull(SiteFinder.evaluate(foreign, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3,
            0, new SiteFinder.Relax(0, 0, 0, 0, false), new SiteFinder.Stats()));
        final Land cliff = new Land() {
            @Override public int height(final int x, final int z) { return z >= 4 ? 60 : 64; }
        };
        assertNull(SiteFinder.evaluate(cliff, 5, 5, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3,
            0, new SiteFinder.Relax(0, 0, 0, 0, false), new SiteFinder.Stats()));
    }

    @Test
    void evenSizedFootprintStartsItsApproachAtTheAdjacentBlock() {
        final SiteFinder.Spot spot = SiteFinder.evaluate(new Land(), 6, 6, 0, 0, 0, 0, "inner", List.of(), Set.of(), 0, 3);
        assertNotNull(spot);
        assertEquals(3, spot.doorZ()); // footprint z = -3..2
    }
}
