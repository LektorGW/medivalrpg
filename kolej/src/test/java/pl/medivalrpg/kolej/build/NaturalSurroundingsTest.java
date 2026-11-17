// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class NaturalSurroundingsTest {
    @Test void sharedCasesAgreeWithThePlanGenerator() throws Exception {
        assertTrue(NaturalSurroundings.loaded(), "resource natural-surroundings.json must be on the classpath");
        try (var in = getClass().getResourceAsStream("/natural-surroundings-cases.json")) {
            var cases = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("cases");
            assertTrue(cases.size() >= 40);
            for (var element : cases) {
                var c = element.getAsJsonObject();
                assertEquals(c.get("expected").getAsBoolean(), NaturalSurroundings.permitted(c.get("state").getAsString(), c.get("tileEntity").getAsBoolean()), c.toString());
            }
        }
    }

    @Test void auditAcceptsVanillaSurroundingsButNotConstructionOrPersistentLeaves() {
        BlockOp op = BlockOp.clear(3647, 78, 778);
        var position = NaturalClearance.Position.of(op);
        var policy = NaturalClearance.forPlan(List.of(op), Map.of(position.key(), "minecraft:dark_oak_leaves[distance=3,persistent=false,waterlogged=false]"), Map.of());
        UnaryOperator<String> exact = s -> s;
        var near = new NaturalClearance.Position(3650, 76, 782);
        for (String state : List.of("minecraft:azalea_leaves[distance=7,persistent=false,waterlogged=false]", "minecraft:jungle_log[axis=y]",
                                    "minecraft:bubble_column[drag=true]", "minecraft:magma_block"))
            assertNull(policy.auditProblem(near, new NaturalClearance.Sample(state, false, false, true), exact), state);
        for (String state : List.of("minecraft:azalea_leaves[distance=7,persistent=true,waterlogged=false]", "minecraft:oak_planks",
                                    "minecraft:cobblestone", "minecraft:stripped_oak_log[axis=y]"))
            assertNotNull(policy.auditProblem(near, new NaturalClearance.Sample(state, false, false, true), exact), state);
        assertNotNull(policy.auditProblem(near, new NaturalClearance.Sample("minecraft:magma_block", true, false, true), exact), "tile entity");
        assertNotNull(policy.auditProblem(near, new NaturalClearance.Sample("minecraft:magma_block", false, false, false), exact), "protected land");
        // The operation itself still needs its exact approved fingerprint: surroundings never authorize work.
        assertNotNull(policy.auditProblem(position, new NaturalClearance.Sample("minecraft:azalea_leaves[distance=3,persistent=false,waterlogged=false]", false, false, true), exact));
        assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(op), Map.of(position.key(), "minecraft:azalea_leaves[distance=3,persistent=false,waterlogged=false]"), Map.of()));
    }
}
