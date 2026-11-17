// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Production stop 2026-10-29 04:39:56: kelp[age=23] at 3334:51:773 (mr-preclear-39) grew after the 28.10 survey. */
class KelpGrowthTest {
    @Test void kelpFingerprintToleratesGrowthOnly() {
        assertTrue(NaturalClearance.fingerprintMatches("minecraft:kelp[age=23]", "minecraft:kelp[age=24]"));
        assertTrue(NaturalClearance.fingerprintMatches("minecraft:kelp[age=23]", "minecraft:kelp_plant"), "top became stem under a new top");
        assertTrue(NaturalClearance.fingerprintMatches("minecraft:kelp_plant", "minecraft:kelp[age=25]"), "stem became top after the top was eaten/broken");
        for (String other : List.of("minecraft:water[level=0]", "minecraft:air", "minecraft:seagrass", "minecraft:stone_bricks", "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]"))
            assertFalse(NaturalClearance.fingerprintMatches("minecraft:kelp[age=23]", other), other);
        // Leaves keep the previous rule: only distance, same species, never persistent.
        assertTrue(NaturalClearance.fingerprintMatches("minecraft:oak_leaves[distance=5,persistent=false,waterlogged=false]", "minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]"));
        assertFalse(NaturalClearance.fingerprintMatches("minecraft:oak_leaves[distance=5,persistent=false,waterlogged=false]", "minecraft:oak_leaves[distance=5,persistent=true,waterlogged=false]"));
        assertFalse(NaturalClearance.fingerprintMatches("minecraft:oak_leaves[distance=5,persistent=false,waterlogged=false]", "minecraft:kelp_plant"));
        assertFalse(NaturalClearance.fingerprintMatches("minecraft:sandstone", "minecraft:kelp_plant"), "other special classes stay exact");
    }

    @Test void auditOfThePinnedKelpPositionAcceptsGrowth() {
        var op = BlockOp.clear(3334, 51, 773);
        var policy = NaturalClearance.forPlan(List.of(op), Map.of("3334:51:773", "minecraft:kelp[age=23]"), Map.of());
        var position = new NaturalClearance.Position(3334, 51, 773);
        assertNull(policy.auditProblem(position, new NaturalClearance.Sample("minecraft:kelp_plant", false, false, true), s -> s));
        assertNull(policy.naturalProblem(op, new NaturalClearance.Sample("minecraft:kelp[age=24]", false, false, true), s -> s));
        assertNotNull(policy.auditProblem(position, new NaturalClearance.Sample("minecraft:water[level=0]", false, false, true), s -> s));
    }

    @Test void workerAcceptsKelpGrowthIntoPlannedWater() {
        assertTrue(NaturalWorkChange.matches("minecraft:water[level=0]", "minecraft:stone_bricks", "minecraft:kelp[age=4]"));
        assertTrue(NaturalWorkChange.matches("minecraft:kelp[age=23]", "minecraft:air", "minecraft:kelp_plant"));
        assertFalse(NaturalWorkChange.matches("minecraft:water[level=0]", "minecraft:stone_bricks", "minecraft:cobblestone"));
    }
}
