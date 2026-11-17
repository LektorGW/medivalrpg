// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mines grow: levels, floors, ore veins, the look of each epoch. */
class MinePlannerTest {

    @Test
    void levelsGrowWithLengthFloorsAndEpoch() {
        assertEquals(1, MinePlanner.level(0, 1, 1));
        assertEquals(2, MinePlanner.level(50, 1, 1));
        assertEquals(3, MinePlanner.level(50, 2, 1), "a second floor");
        assertEquals(4, MinePlanner.level(50, 2, 3), "the industrial epoch");
        assertEquals(5, MinePlanner.level(1000, 9, 4), "at most 5");
        assertTrue(MinePlanner.floorLength(2) > MinePlanner.floorLength(1));
        assertTrue(MinePlanner.incomeFactor(5, 3) > MinePlanner.incomeFactor(5, 1) && MinePlanner.incomeFactor(5, 1) > MinePlanner.incomeFactor(1, 1));
        assertEquals(10, MinePlanner.dailyOre(5));
    }

    @Test
    void minersTurnTowardsOreVeins() {
        final Map<Long, String> ore = new HashMap<>();
        ore.put(MineDigger.key(3, 50, 10), "minecraft:iron_ore"); // 3 blocks east of a gallery heading south
        final MineDigger.Blocks w = (x, y, z) -> ore.getOrDefault(MineDigger.key(x, y, z), "minecraft:stone");
        assertEquals(1, MinePlanner.oreDirection(w, 0, 50, 10, 2, 4), "east");
        assertEquals(-1, MinePlanner.oreDirection(w, 0, 50, 10, 1, 4), "ahead is not sideways");
        assertEquals(-1, MinePlanner.oreDirection(w, 0, 50, 30, 2, 4), "out of reach");
    }

    @Test
    void galleriesLookLikeTheirEpoch() {
        assertEquals("minecraft:wall_torch[facing=east]", MinePlanner.light("minecraft:wall_torch[facing=east]", 1));
        assertEquals("minecraft:lantern[hanging=false]", MinePlanner.light("minecraft:wall_torch[facing=east]", 2));
        assertEquals("minecraft:sea_lantern", MinePlanner.light("minecraft:wall_torch[facing=east]", 4));
        // a lantern stands in a niche of the wall the torch hung on; a modern lamp sits in the ceiling
        assertArrayEquals(new int[] {9, 51, 10}, MinePlanner.lightPos("minecraft:wall_torch[facing=east]", 10, 51, 10, 3));
        assertArrayEquals(new int[] {10, 52, 10}, MinePlanner.lightPos("minecraft:wall_torch[facing=east]", 10, 51, 10, 4));
        assertEquals("minecraft:polished_deepslate", MinePlanner.style("minecraft:oak_log[axis=y]", 3), "steel/stone frames");
        assertEquals("minecraft:gray_concrete", MinePlanner.style("minecraft:cobblestone", 4), "concrete galleries");
        assertEquals("minecraft:andesite_stairs[facing=north,half=bottom]",
            MinePlanner.style("minecraft:cobblestone_stairs[facing=north,half=bottom]", 4));
        assertEquals("minecraft:rail[shape=north_south]", MinePlanner.style("minecraft:rail[shape=north_south]", 4));
    }
}
