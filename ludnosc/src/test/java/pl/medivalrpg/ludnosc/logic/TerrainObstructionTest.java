// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell;

class TerrainObstructionTest {

    @Test
    void playerOrderNeverBlockedByNaturalTerrain() {
        // the reported case: DIRT 9 blocks above the floor of a player's building
        final int limit = TerrainObstruction.cutLimit(66, 2, false);
        assertFalse(TerrainObstruction.blocks(Cell.NATURAL, 75, limit));
        assertFalse(TerrainObstruction.blocks(Cell.NATURAL, 66 + 300, limit));
    }

    @Test
    void builtBlocksStayProtectedForEveryone() {
        assertTrue(TerrainObstruction.blocks(Cell.BUILT, 67, TerrainObstruction.cutLimit(66, 2, false)));
        assertTrue(TerrainObstruction.blocks(Cell.BUILT, 67, TerrainObstruction.cutLimit(66, 2, true)));
        assertTrue(TerrainObstruction.blocks(Cell.BUILT, 10, TerrainObstruction.cutLimit(66, 64, true)));
    }

    @Test
    void automaticPlannerKeepsTheOldCutLimit() {
        // old behaviour: groundY + max(max-terrain-cut, 6)
        final int limit = TerrainObstruction.cutLimit(50, 2, true);
        assertEquals(56, limit);
        assertFalse(TerrainObstruction.blocks(Cell.NATURAL, 56, limit));
        assertTrue(TerrainObstruction.blocks(Cell.NATURAL, 57, limit));
        assertEquals(60, TerrainObstruction.cutLimit(50, 10, true));
    }

    @Test
    void freeCellsNeverBlock() {
        assertFalse(TerrainObstruction.blocks(Cell.FREE, 999, TerrainObstruction.cutLimit(0, 0, true)));
    }

    @Test
    void reasonNamesTheBlockAndTheCount() {
        final String built = TerrainObstruction.reason("OAK_LOG", 1, 2, 3, Cell.BUILT, 7);
        assertTrue(built.startsWith("Blok OAK_LOG w 1 2 3 blokuje budowę (w obrysie łącznie 7 takich bloków)"), built);
        assertTrue(built.contains("usuń ją ręcznie"), built);
        final String single = TerrainObstruction.reason("OAK_PLANKS", 1, 2, 3, Cell.BUILT, 1);
        assertFalse(single.contains("łącznie"), single);
        final String natural = TerrainObstruction.reason("DIRT", -3755, 58, -1339, Cell.NATURAL, 40);
        assertTrue(natural.contains("DIRT w -3755 58 -1339"), natural);
        assertFalse(natural.contains("usuń ją ręcznie"), natural);
    }
}
