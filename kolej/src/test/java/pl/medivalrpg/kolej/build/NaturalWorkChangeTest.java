// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalWorkChangeTest {
    @Test void upperPlantDisappearingAfterSupportReplacementDoesNotBlockTheRemainingJob() {
        assertTrue(NaturalWorkChange.accepts("minecraft:tall_grass[half=upper]", "minecraft:air"));
        assertTrue(NaturalWorkChange.accepts("minecraft:large_fern[half=upper]", "minecraft:air"));
        assertTrue(NaturalWorkChange.accepts("minecraft:grass_block[snowy=false]", "minecraft:dirt"));
    }
    @Test void changedConstructionAndContainersStillStopWork() {
        for (String before : new String[]{"minecraft:stone_bricks", "minecraft:chest[facing=north,type=single,waterlogged=false]",
            "minecraft:torch", "minecraft:oak_log[axis=y]", "minecraft:stone", "minecraft:air"})
            assertFalse(NaturalWorkChange.accepts(before, "minecraft:air"), before);
        assertFalse(NaturalWorkChange.accepts("minecraft:tall_grass[half=upper]", "minecraft:chest"));
        assertFalse(NaturalWorkChange.accepts("minecraft:grass_block[snowy=false]", "minecraft:oak_planks"));
    }
    @Test void shortGrassAndDecorativePlantsMayDisappearWhenTheirSupportIsExcavated() {
        for (String plant : new String[]{"short_grass", "wildflowers[facing=east,flower_amount=1]", "vine[north=true]", "poppy"})
            assertTrue(NaturalWorkChange.matches("minecraft:"+plant,"minecraft:coal_block","minecraft:air"),plant);
        assertFalse(NaturalWorkChange.matches("minecraft:short_grass","minecraft:coal_block","minecraft:oak_planks"));
    }
    @Test void ExposedDirtMayRegrowGrassWhileTheAcceptedExcavationIsQueued() {
        assertTrue(NaturalWorkChange.matches("minecraft:dirt","minecraft:air","minecraft:grass_block[snowy=false]"));
        assertTrue(NaturalWorkChange.matches("minecraft:grass_block[snowy=false]","minecraft:gravel","minecraft:dirt"));
        assertFalse(NaturalWorkChange.matches("minecraft:dirt","minecraft:air","minecraft:farmland[moisture=0]"));
    }
    @Test void aManuallyFinishedAcceptedCellCanResumeWithoutReplacingItAgain() {
        assertTrue(NaturalWorkChange.matches("minecraft:dirt","minecraft:air","minecraft:air"));
        assertTrue(NaturalWorkChange.matches("minecraft:air","minecraft:stone_bricks","minecraft:stone_bricks"));
        assertFalse(NaturalWorkChange.matches("minecraft:air","minecraft:stone_bricks","minecraft:chest"));
    }
}
