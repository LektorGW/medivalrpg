// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/** Building sites of city walls: the ground of a column, stretches of a site, notes for player plans. */
final class WallSiteTest {

    /** A column: y → what the scan sees (missing = air). */
    private static int ground(final Map<Integer, WorldOps.GroundScan> column, final int top) {
        return WorldOps.wallGround(top, -64, ConstructionService.WALL_STACK,
            y -> column.getOrDefault(y, WorldOps.GroundScan.PASS));
    }

    @Test
    void theGroundOfAColumnIsTheSameBeforeAndAfterTheWallStands() {
        final Map<Integer, WorldOps.GroundScan> column = new HashMap<>();
        for (int y = -64; y <= 64; y++) {
            column.put(y, WorldOps.GroundScan.GROUND);
        }
        assertEquals(64, ground(column, 64), "bare ground");
        // the wall of the site stands: 5 wall blocks, merlon and lantern (a tower: 8 + 2)
        for (int y = 65; y <= 74; y++) {
            column.put(y, WorldOps.GroundScan.WALL);
        }
        assertEquals(64, ground(column, 74), "planning the site again after a restart: the same ground, no second wall on top");
    }

    @Test
    void groundDoesNotDependOnTheTownCentreHeight() {
        // the old scan looked only 24 blocks around the centre's height: a hillside column 30 blocks higher was skipped
        final Map<Integer, WorldOps.GroundScan> column = new HashMap<>();
        for (int y = -64; y <= 100; y++) {
            column.put(y, WorldOps.GroundScan.GROUND);
        }
        assertEquals(100, ground(column, 100));
    }

    @Test
    void aTallStackOfStoneIsSomeonesBuildingNotOurWall() {
        final Map<Integer, WorldOps.GroundScan> column = new HashMap<>();
        for (int y = -64; y <= 64; y++) {
            column.put(y, WorldOps.GroundScan.GROUND);
        }
        for (int y = 65; y <= 90; y++) {
            column.put(y, WorldOps.GroundScan.WALL);
        }
        assertEquals(TerrainView.UNKNOWN, ground(column, 90), "skip the column instead of building on or into it");
    }

    @Test
    void scatteredSiteCellsAreSplitIntoStretches() {
        final List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            cells.add(new BlockPos("world", x, 64, 0));
        }
        for (int x = 40; x < 45; x++) {
            cells.add(new BlockPos("world", x, 64, 3));
        }
        cells.add(new BlockPos("world", 46, 64, 4));
        final List<List<BlockPos>> parts = ConstructionService.stretches(cells);
        assertEquals(3, parts.size());
        assertEquals(10, parts.get(0).size());
        assertEquals(5, parts.get(1).size());
        assertEquals(1, parts.get(2).size());
    }

    @Test
    void aPlayerPlanSaysWhatHappensToItsWalls() {
        assertTrue(DesignService.playerPlanNotes(false, true, false).getFirst().contains("szkic"),
            "walls of a player plan are not silently ignored");
        assertTrue(DesignService.playerPlanNotes(true, true, false).getFirst().contains("skarbca"));
        assertEquals(1, DesignService.playerPlanNotes(true, false, true).size());
        assertTrue(DesignService.playerPlanNotes(true, false, false).isEmpty());
    }
}
