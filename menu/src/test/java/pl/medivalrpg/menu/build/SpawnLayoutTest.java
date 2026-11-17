// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpawnLayoutTest {

    private static final SpawnLayout.Pos P = new SpawnLayout.Pos(100, 70, -200);

    private static SpawnLayout bundled() throws IOException {
        try (InputStream in = SpawnLayoutTest.class.getResourceAsStream("/spawn/markers.json")) {
            assertNotNull(in, "markers.json is packed into the jar");
            return SpawnLayout.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void schematicsArePacked() {
        assertNotNull(SpawnLayoutTest.class.getResource("/spawn/spawn.schem"));
        assertNotNull(SpawnLayoutTest.class.getResource("/spawn/spawn_noair.schem"));
    }

    @Test
    void bundledMarkers() throws IOException {
        final SpawnLayout l = bundled();
        assertEquals(69, l.sizeX());
        assertEquals(32, l.sizeY());
        assertEquals(69, l.sizeZ());
        assertEquals(-34, l.offsetX());
        assertEquals(-5, l.offsetY());
        assertEquals(-34, l.offsetZ());
        assertEquals(Optional.of(P), l.marker("spawn", P), "spawn marker = paste point (above the lodestone)");
        assertEquals(Optional.of(new SpawnLayout.Pos(86, 71, -222)), l.marker("bankier", P), "(-14, +1, -22)");
        assertEquals(Optional.of(new SpawnLayout.Pos(122, 70, -205)), l.marker("kupiec", P), "(+22, 0, -5)");
        assertTrue(l.marker("smok", P).isEmpty());
        assertEquals(69, l.groundY(P), "edge grass / paving is one block below the feet");
    }

    @Test
    void boundingBoxFromOffset() throws IOException {
        // README: X − 34 … X + 34, Y − 5 … Y + 26, Z − 34 … Z + 34
        final Box box = bundled().box(P);
        assertEquals(new Box(66, 65, -234, 134, 96, -166), box);
        assertEquals(69, box.sizeX());
        assertEquals(32, box.sizeY());
        assertEquals(69, box.sizeZ());
    }

    @Test
    void regionIsBoxPlusMarginOverWholeHeight() throws IOException {
        final Box region = bundled().region(P, 8, -64, 319);
        assertEquals(new Box(58, -64, -242, 142, 319, -158), region);
        assertEquals(85, region.sizeX());
        assertEquals(bundled().box(P).expandHorizontally(0, -64, 319), bundled().region(P, -3, -64, 319),
            "negative margin → 0");
    }

    @Test
    void chunkRangeAndDistances() throws IOException {
        final Box box = bundled().box(P);
        assertEquals(66 >> 4, box.minChunkX());
        assertEquals(134 >> 4, box.maxChunkX());
        assertEquals(-234 >> 4, box.minChunkZ());
        assertEquals(0, box.columnDistance(100, -200));
        assertEquals(1, box.columnDistance(65, -200));
        assertEquals(3, box.columnDistance(137, -163), "corner: Chebyshev distance");
    }

    @Test
    void brokenJson() {
        assertThrows(RuntimeException.class, () -> SpawnLayout.parse("{\"size\": [1, 2], \"offset\": [0, 0, 0]}"));
        assertThrows(RuntimeException.class, () -> SpawnLayout.parse("{\"size\": [0, 2, 2], \"offset\": [0, 0, 0]}"));
        assertThrows(IllegalArgumentException.class, () -> new Box(5, 0, 0, 4, 0, 0));
    }
}
