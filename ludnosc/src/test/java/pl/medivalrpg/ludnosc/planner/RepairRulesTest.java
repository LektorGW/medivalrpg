// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.QueueRules;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;

class RepairRulesTest {

    @Test
    void cosmeticDifferencesAreIgnored() {
        final String door = "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false]";
        assertTrue(RepairRules.equivalent(door, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=true,powered=false]"));
        assertTrue(RepairRules.equivalent("minecraft:red_bed[facing=north,part=head]",
            "minecraft:red_bed[facing=north,occupied=true,part=head]"));
        assertTrue(RepairRules.equivalent("minecraft:glass_pane[east=true,north=false,south=false,west=true]",
            "minecraft:glass_pane[east=false,north=false,south=false,west=false,waterlogged=false]"), "connections");
        assertTrue(RepairRules.equivalent("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
            "minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]"));
        assertTrue(RepairRules.equivalent("minecraft:water_cauldron[level=3]", "minecraft:cauldron"), "emptied cauldron");
        assertTrue(RepairRules.equivalent("minecraft:chest[facing=south]", "minecraft:chest[facing=south,type=left]"));
        assertTrue(RepairRules.equivalent("minecraft:grass_block[snowy=false]", "minecraft:dirt"));
        assertTrue(RepairRules.equivalent("minecraft:spruce_trapdoor[facing=south,half=top,open=false]",
            "minecraft:spruce_trapdoor[facing=south,half=top,open=true]"));
        // plants, crops and air are never damage
        assertTrue(RepairRules.equivalent("minecraft:poppy", "minecraft:air"));
        assertTrue(RepairRules.equivalent("minecraft:wheat[age=7]", "minecraft:wheat[age=0]"));
        assertTrue(RepairRules.equivalent(BlockStates.AIR, "minecraft:stone"));
    }

    @Test
    void realDamageIsDetected() {
        assertFalse(RepairRules.equivalent("minecraft:stone_bricks", "minecraft:air"));
        assertFalse(RepairRules.equivalent("minecraft:stone_bricks", "minecraft:cobblestone"), "replaced block");
        assertFalse(RepairRules.equivalent("minecraft:oak_stairs[facing=north,half=bottom]",
            "minecraft:oak_stairs[facing=east,half=bottom]"), "turned stairs");
        assertFalse(RepairRules.equivalent("minecraft:oak_slab[type=bottom]", "minecraft:oak_slab[type=top]"));
        assertFalse(RepairRules.equivalent("minecraft:red_bed[facing=north,part=head]", "minecraft:air"));
        assertFalse(RepairRules.equivalent("minecraft:glass_pane", null));
    }

    @Test
    void keyCellsAreBedsDoorsAndTheRoofAboveBeds() throws IOException {
        final BuildingTemplate chata = BuiltinTemplates.load("chata");
        final Set<Integer> keys = RepairRules.keyCells(chata);
        assertTrue(keys.size() >= 2 * 2 + 2 + 2, "2 beds (4 cells) + door (2) + ceiling above 2 bed heads");
        for (final int[] head : chata.bedHeads()) {
            boolean roof = false;
            for (int y = head[1] + 1; y < chata.height(); y++) {
                if (keys.contains(chata.index(head[0], y, head[2]))) {
                    roof = true;
                }
            }
            assertTrue(roof, "roof over the bed");
        }
    }

    @Test
    void verdictAndCost() {
        assertEquals(RepairRules.Verdict.OK, RepairRules.verdict(0, 500, false, 0.02, 0.7));
        assertEquals(RepairRules.Verdict.OK, RepairRules.verdict(5, 500, false, 0.02, 0.7), "1% below threshold");
        assertEquals(RepairRules.Verdict.REPAIR, RepairRules.verdict(5, 500, true, 0.02, 0.7), "key block");
        assertEquals(RepairRules.Verdict.REPAIR, RepairRules.verdict(20, 500, false, 0.02, 0.7));
        assertEquals(RepairRules.Verdict.RUIN, RepairRules.verdict(400, 500, false, 0.02, 0.7));
        assertEquals(400, RepairRules.cost(4000, 100, 500, 0.5), "4000 × 20% × 0.5");
        assertEquals(1, RepairRules.cost(4000, 1, 100_000, 0.5), "at least 1");
        assertEquals(0, RepairRules.cost(4000, 0, 500, 0.5));
        assertEquals(20, RepairRules.percent(100, 500));
    }

    @Test
    void repairsGoFirstButWaitDuringASiege() {
        // queue: [new house, new smithy]; a repair is put in front
        final List<String> queue = new ArrayList<>(List.of("dom", "kuznia"));
        queue.add(0, "naprawa");
        // the repaired building is under siege → it waits, the next order starts, the repair stays first
        final String next = QueueRules.pollFirstStartable(queue, s -> s.equals("naprawa"), s -> true, s -> { });
        assertEquals("dom", next);
        assertEquals(List.of("naprawa", "kuznia"), queue);
        // siege over → the repair starts first
        assertEquals("naprawa", QueueRules.pollFirstStartable(queue, s -> false, s -> true, s -> { }));
    }
}
