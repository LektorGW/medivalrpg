// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.depot;

import org.junit.jupiter.api.Test;
import java.util.*;
import pl.medivalrpg.kolej.build.BlockOp;
import static org.junit.jupiter.api.Assertions.*;

class DepotPlanTest {
    private static String key(BlockOp op) { return op.x() + ":" + op.y() + ":" + op.z(); }
    @Test void uniqueBoundedAndFoundationFirst() {
        List<BlockOp> ops = DepotPlan.plan(0, 64, 0);
        assertTrue(ops.size() > 15000); assertTrue(ops.size() < 100000);
        Set<String> keys = new HashSet<>(); int lastY = Integer.MIN_VALUE;
        for (BlockOp op : ops) {
            assertTrue(keys.add(key(op)), key(op)); assertTrue(op.y() >= lastY); lastY = op.y();
            assertTrue(Math.abs(op.x()) <= 38); assertTrue(Math.abs(op.z()) <= 30);
        }
    }
    @Test void sixDistinctRadialBaysWithSupportedTracks() {
        List<DepotPlan.Bay> bays = DepotPlan.bays(); assertEquals(6, bays.size());
        assertEquals(6, bays.stream().map(b -> b.endX() + ":" + b.endZ()).distinct().count());
        Map<String, BlockOp> ops = new HashMap<>(); DepotPlan.plan(0, 0, 0).forEach(op -> ops.put(key(op), op));
        for (var bay : bays) {
            assertEquals("minecraft:rail", ops.get(bay.endX() + ":1:" + bay.endZ()).data().split("\\[")[0]);
        }
        for (BlockOp op : ops.values()) if (op.data().startsWith("minecraft:rail[")) {
            BlockOp below = ops.get(op.x() + ":0:" + op.z()); assertNotNull(below);
            assertFalse(below.data().equals("minecraft:air"));
        }
    }
    @Test void translatedPlanRetainsBlockDataAndDimensions() {
        var a = DepotPlan.plan(0, 0, 0); var b = DepotPlan.plan(-800, 70, 1200);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).x() - 800, b.get(i).x()); assertEquals(a.get(i).y() + 70, b.get(i).y());
            assertEquals(a.get(i).z() + 1200, b.get(i).z()); assertEquals(a.get(i).data(), b.get(i).data());
        }
    }
    @Test void depotHasActualFacilitiesAndCompleteBeds() {
        var ops = DepotPlan.plan(0, 0, 0);
        for (String facility : List.of("anvil", "coal_block", "water", "bookshelf", "crafting_table", "red_bed", "rail", "lantern"))
            assertTrue(ops.stream().anyMatch(op -> op.data().startsWith("minecraft:" + facility)), facility);
        assertEquals(4, ops.stream().filter(op -> op.data().contains("red_bed") && op.data().contains("part=foot")).count());
        assertEquals(4, ops.stream().filter(op -> op.data().contains("red_bed") && op.data().contains("part=head")).count());
    }
}
