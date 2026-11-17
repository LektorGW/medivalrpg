// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;

class PlacementTest {

    @Test
    void footprintIsCentredAndFloorAtOrigin() throws IOException {
        final BuildingTemplate chata = BuiltinTemplates.load("chata"); // 9 × 8 (x × z)
        final Placement p = Placement.centered("world", chata, 100, 64, -50);
        final BlockBox box = p.box();
        assertEquals(96, box.minX());
        assertEquals(104, box.maxX());
        assertEquals(-54, box.minZ());
        assertEquals(64, box.minY(), "ground layer 0 lies at the origin's y");
        assertEquals(64, p.groundY());
        assertEquals(100, box.centerX());
        for (int q = 0; q < 4; q++) {
            final Placement r = Placement.centered("world", chata.rotated(q), 100, 64, -50);
            assertTrue(r.box().contains("world", 100, 64, -50), "rotating keeps the origin inside, rot " + q);
        }
    }

    @Test
    void undergroundLayersGoBelowTheOrigin() throws IOException {
        final BuildingTemplate well = BuiltinTemplates.load("studnia");
        final Placement p = Placement.centered("world", well, 0, 70, 0);
        assertEquals(70 - well.meta().groundLayer(), p.box().minY());
        assertEquals(70, p.groundY());
    }

    @Test
    void spawnCellMapsIntoTheWorld() throws IOException {
        final BuildingTemplate dom = BuiltinTemplates.load("dom").rotated(1);
        final Placement p = Placement.centered("world", dom, 10, 64, 10);
        final BlockPos s = p.toWorld(dom.spawnCell());
        assertTrue(p.box().contains(s.world(), s.x(), s.y(), s.z()));
        assertTrue(s.y() > 64, "spawn above the floor");
    }
}
