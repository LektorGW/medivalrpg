// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.planner.TerrainFootprint;

/** Every block a site reads outside its plot lies inside the chunks it waits for (no synchronous chunk load). */
class ConstructionReachTest {

    @Test
    void frontSpotStaysInsideTheNearMargin() {
        final int[][] fronts = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int sx = 1; sx <= 24; sx++) {
            for (int sz = 1; sz <= 24; sz++) {
                for (final int ox : new int[] {-17, 0, 5}) {
                    final BlockBox box = new BlockBox("world", ox, 60, -3, ox + sx - 1, 70, -3 + sz - 1);
                    final BlockBox near = TerrainFootprint.expand(box, ConstructionService.NEAR_MARGIN);
                    for (final int[] v : fronts) {
                        final int[] xz = ConstructionService.frontXZ(box, v);
                        assertTrue(near.contains("world", xz[0], 65, xz[1]),
                            "front " + xz[0] + "," + xz[1] + " outside " + near + " for " + box + " v=" + v[0] + "," + v[1]);
                    }
                }
            }
        }
    }

    @Test
    void earthworksEntranceAndCleanUpWaitForTheWholeBand() {
        assertTrue(ConstructionService.wideLoadingBox(site(ConstructionSite.BUILD, ConstructionService.Phase.TERRAIN)));
        assertTrue(ConstructionService.wideLoadingBox(site(ConstructionSite.BUILD, ConstructionService.Phase.ENTRANCE)));
        // the clean-up after a cancelled build restores terrain up to 9 blocks outside the plot
        assertTrue(ConstructionService.wideLoadingBox(site(ConstructionSite.RESTORE, ConstructionService.Phase.RESTORE_CELLS)));
        assertTrue(ConstructionService.wideLoadingBox(site(ConstructionSite.RESTORE, ConstructionService.Phase.RESTORE_TERRAIN)));
        assertFalse(ConstructionService.wideLoadingBox(site(ConstructionSite.BUILD, ConstructionService.Phase.CELLS)));
        assertFalse(ConstructionService.wideLoadingBox(site(ConstructionSite.REPAIR, ConstructionService.Phase.CELLS)));
        assertTrue(TerrainFootprint.building(new BlockBox("w", 0, 0, 0, 9, 9, 9)).minX() <= -ConstructionService.NEAR_MARGIN);
    }

    private static ConstructionSite site(final String kind, final ConstructionService.Phase phase) {
        final ConstructionSite s = new ConstructionSite();
        s.kind = kind;
        s.phase = phase.ordinal();
        return s;
    }
}
