// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.*;

class TerrainFootprintTest {
    @Test void ticketsCoverRampAndItsExternalNeighbourProbe() {
        final BlockBox house = new BlockBox("world", 5, 60, 5, 10, 70, 10);
        assertEquals(1, house.chunks().size());
        final BlockBox domain = TerrainFootprint.building(house);
        assertEquals(9, domain.chunks().size());
        final var plan = Terraform.plan((x,z) -> x > 12 ? 68 : 62, 5,5,10,10,62,
            new Terraform.Settings(TerrainFootprint.MARGIN, TerrainFootprint.RAMP,3));
        for (final var c : plan.columns()) {
            assertTrue(domain.contains("world", c.x(), 62, c.z()));
            assertTrue(domain.contains("world", c.x()+1, 62, c.z()));
        }
    }

    @Test void savedPendingTerrainIsReplannedOnce() {
        final ConstructionSite site = new ConstructionSite();
        site.terrain = List.of(new int[] {1,2,60,60}); site.cursor = 7;
        assertTrue(TerrainFootprint.refreshPending(site));
        assertNull(site.terrain); assertEquals(0, site.cursor);
        assertFalse(TerrainFootprint.refreshPending(site));
    }

    /** 2026-11-03: plots planned with retaining walls (version 1) are planned once more with natural embankments. */
    @Test void versionOnePlansAreReplannedForNaturalEmbankments() {
        final ConstructionSite site = new ConstructionSite();
        site.terrain = List.of(new int[] {1,2,60,60}); site.terrainVersion = 1; site.cursor = 3;
        assertTrue(TerrainFootprint.refreshPending(site));
        assertEquals(TerrainFootprint.VERSION, site.terrainVersion);
        assertFalse(TerrainFootprint.refreshPending(site));
    }

    @Test void migrationDoesNotRewindWallsOrTownGrading() {
        final ConstructionSite wall = new ConstructionSite();
        wall.phase = 2; wall.cursor = 100;
        assertFalse(TerrainFootprint.refreshPending(wall)); assertEquals(100, wall.cursor);
        final ConstructionSite grading = new ConstructionSite(); grading.kind = ConstructionSite.LEVEL;
        grading.terrain = List.of(new int[] {1,2,60,61}); grading.cursor=1;
        assertFalse(TerrainFootprint.refreshPending(grading)); assertEquals(1, grading.cursor);
    }
}
