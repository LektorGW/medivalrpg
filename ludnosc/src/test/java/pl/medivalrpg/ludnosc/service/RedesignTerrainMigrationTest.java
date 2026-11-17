// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.CountryState;

class RedesignTerrainMigrationTest {
    @Test
    void oldPlanRevisitsOccupiedYardsOnceWithoutResettingItsOtherProgress() {
        final CountryState state = new CountryState();
        state.planActive = true;
        state.planForced = true;
        state.planFree = true;
        state.planQuarters = new ArrayList<>(List.of(new int[] {0, 0, 18, 18}));
        state.planFill = new ArrayList<>(List.of(new int[] {0, 0, 18, 18}, new int[] {20, 0, 38, 18}));
        state.planRoads = new ArrayList<>(List.of(12L, 15L));
        state.planDemolish = new ArrayList<>(List.of("remaining-building"));
        state.planLevelsReady = true;
        state.planLevels = new ArrayList<>(List.of(new int[] {0, 0, 18, 18, 64}));
        assertTrue(RedesignService.migrateTerrainPlan(state));
        assertEquals(2, state.planQuarters.size(), "occupied quarter restored without duplicate free quarter");
        assertEquals(2, state.planFill.size());
        assertNull(state.planLevels);
        assertFalse(state.planLevelsReady);
        assertTrue(state.planActive && state.planForced && state.planFree);
        assertEquals(List.of(12L, 15L), state.planRoads);
        assertEquals(List.of("remaining-building"), state.planDemolish);
        assertFalse(RedesignService.migrateTerrainPlan(state), "no repeat reset on subsequent planner turns");
    }
}
