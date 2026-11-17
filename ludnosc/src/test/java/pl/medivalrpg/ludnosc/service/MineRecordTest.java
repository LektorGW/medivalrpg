// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class MineRecordTest {

    @Test
    void oldRecordsGetLevelsAndMinerFields() {
        // written before mine levels: x, y, z, dir, length, remaining, bottom, world hash
        final int[] old = MineService.normalise(new int[] {1, 2, 3, 2, 40, 0, -10, 7});
        assertEquals(MineService.SIZE, old.length);
        assertEquals(1, old[MineService.LEVEL]);
        assertEquals(1, old[MineService.FLOORS]);
        assertEquals(2, old[MineService.MAIN_DIR], "main direction = current direction");
        assertEquals(0, old[MineService.PROGRESS]);
        assertEquals(0, old[MineService.BLOCKED]);
    }

    @Test
    void levelRecordsKeepTheirState() {
        // written with mine levels (15 fields): following a vein east (dir 1) while the main direction is south (2)
        final int[] rec = {1, 2, 3, 1, 120, 0, -20, 7, 3, 2, 55, 4, 2, 0, 60};
        final int[] n = MineService.normalise(rec);
        assertEquals(MineService.SIZE, n.length);
        assertEquals(3, n[MineService.LEVEL]);
        assertEquals(2, n[MineService.MAIN_DIR], "the vein detour is not lost");
        assertEquals(4, n[MineService.BRANCH]);
        final int[] full = new int[MineService.SIZE];
        assertSame(full, MineService.normalise(full));
        assertEquals(0, MineService.blockedUntil(new int[8]));
    }
}
