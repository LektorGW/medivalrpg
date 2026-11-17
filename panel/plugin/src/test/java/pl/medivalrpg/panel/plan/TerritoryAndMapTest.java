// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.map.KrajeFiles;
import pl.medivalrpg.panel.map.MapImage;
import pl.medivalrpg.panel.map.MapRenderService;

/** Territory decisions (foreign, protected, capital, connectivity, cost), map image composition and Kraje files. */
class TerritoryAndMapTest {

    static long k(final int x, final int z) {
        return ChunkGeometry.key(x, z);
    }

    /** 3×1 strip (0,0)-(2,0), capital (0,0). */
    static Set<Long> strip() {
        return new HashSet<>(List.of(k(0, 0), k(1, 0), k(2, 0)));
    }

    @Test
    void chunkKeysRoundTripIncludingNegatives() {
        for (final int[] p : new int[][] {{0, 0}, {-1, -1}, {123456, -98765}, {-1875000, 1875000}}) {
            final long key = ChunkGeometry.key(p[0], p[1]);
            assertEquals(p[0], ChunkGeometry.cx(key));
            assertEquals(p[1], ChunkGeometry.cz(key));
        }
    }

    @Test
    void additionsMustBeFreeUnprotectedAndConnected() {
        final TerritoryPlanner.Decision d = TerritoryPlanner.decide(strip(), k(0, 0),
            List.of(k(3, 0), k(4, 0), k(0, 1), k(9, 9), k(1, 0), k(0, -1)), List.of(),
            key -> key == k(0, 1) ? "Królestwo Polan" : null,
            key -> key == k(0, -1) ? "Spawn" : null, 50, 5);
        assertEquals(List.of(k(3, 0), k(4, 0)), d.claim());
        final Map<String, String> codes = new HashMap<>();
        for (final TerritoryPlanner.Skip s : d.skipped()) {
            codes.put(s.cx() + "," + s.cz(), s.code());
        }
        assertEquals("FOREIGN", codes.get("0,1"));
        assertEquals("NOT_ADJACENT", codes.get("9,9"));
        assertEquals("ALREADY_OWN", codes.get("1,0"));
        assertEquals("PROTECTED", codes.get("0,-1"));
        assertTrue(d.skipped().stream().anyMatch(s -> s.reason().contains("Królestwo Polan")));
        // cost like Kraje: 50 + 5 × 3, then 50 + 5 × 4
        assertEquals(65 + 70, d.cost());
    }

    @Test
    void releasesKeepCapitalAndConnectivity() {
        final TerritoryPlanner.Decision d = TerritoryPlanner.decide(strip(), k(0, 0), List.of(), List.of(k(1, 0), k(0, 0), k(2, 0), k(7, 7)),
            key -> null, key -> null, 50, 5);
        // removing (1,0) first would cut (2,0) off → skipped; capital never; (2,0) is an end → fine; (7,7) is not ours
        assertEquals(List.of(k(2, 0)), d.release());
        final Map<String, String> codes = new HashMap<>();
        for (final TerritoryPlanner.Skip s : d.skipped()) {
            codes.put(s.cx() + "," + s.cz(), s.code());
        }
        assertEquals("DISCONNECTS", codes.get("1,0"));
        assertEquals("CAPITAL", codes.get("0,0"));
        assertEquals("NOT_OWN", codes.get("7,7"));
    }

    @Test
    void releaseAllowedWhenAdditionsKeepTheLandConnected() {
        // ring around: adding (1,1),(2,1) lets (1,0) go
        final TerritoryPlanner.Decision d = TerritoryPlanner.decide(strip(), k(0, 0), List.of(k(0, 1), k(1, 1), k(2, 1)), List.of(k(1, 0)),
            key -> null, key -> null, 0, 0);
        assertEquals(3, d.claim().size());
        assertEquals(List.of(k(1, 0)), d.release());
        assertEquals(0, d.cost());
        final TerritoryPlanner.Decision c = TerritoryPlanner.decide(strip(), k(0, 0), List.of(k(3, 0)), List.of(k(3, 0)), key -> null, key -> null, 0, 0);
        assertEquals("CONFLICT", c.skipped().get(0).code());
    }

    @Test
    void dilationAndMargin() {
        final Set<Long> one = Set.of(k(0, 0));
        assertEquals(9, ChunkGeometry.dilate(one, 1).size());
        assertEquals(25, ChunkGeometry.dilate(one, 2).size());
        assertTrue(ChunkGeometry.nearChunks(one, 15 + 32, 0, 32));
        assertFalse(ChunkGeometry.nearChunks(one, 15 + 33, 0, 32));
        assertTrue(ChunkGeometry.nearChunks(one, -32, -32, 32));
        assertFalse(ChunkGeometry.nearChunks(one, -33, 5, 32));
        final int[] used = new int[1];
        final Set<Long> set = MapRenderService.renderSet(strip(), k(0, 0), 32, 4096, used);
        assertEquals(32, used[0]);
        assertEquals(7 * 5, set.size());
        final Set<Long> small = MapRenderService.renderSet(strip(), k(0, 0), 128, 20, used);
        assertTrue(small.size() <= 20 && used[0] < 128, "margin shrinks to the limit");
        final Set<Long> tiny = MapRenderService.renderSet(strip(), k(0, 0), 0, 2, used);
        assertEquals(Set.of(k(0, 0), k(1, 0)), tiny, "nearest to the capital");
    }

    /** Column source: flat grass at y=64 except a 4-deep pond at x<4 and a hill block at (8, 8). */
    static final class Fake implements MapImage.ColumnSource {
        static final int GRASS = 0x7FB238;
        static final int WATER = 0x4040FF;
        static final int SAND = 0xF7E9A3;

        @Override
        public int top(final int x, final int z) {
            return x == 8 && z == 8 ? 65 : 64;
        }

        @Override
        public int color(final int x, final int y, final int z) {
            if (x == 8 && z == 8 && y == 65) {
                return GRASS;
            }
            if (y > 64) {
                return -1;
            }
            if (x < 4) {
                return y > 60 ? WATER : y == 60 ? SAND : 0x707070;
            }
            return y == 64 ? GRASS : 0x976D4D;
        }

        @Override
        public boolean water(final int x, final int y, final int z) {
            return x < 4 && y > 60 && y <= 64;
        }
    }

    @Test
    void mapColumnsShadingAndWater() {
        final MapImage.Column c = MapImage.reduce(new Fake(), -64, 320);
        final int water = c.argb(0, 5) & 0xFFFFFF;
        // blended: bluer than sand, but not pure water colour (bottom shows through)
        assertTrue((water & 255) > (Fake.SAND & 255) - 40 && water != Fake.WATER, Integer.toHexString(water));
        assertEquals(0xFF000000 | Fake.GRASS, c.argb(5, 5));
        final Map<Long, MapImage.Column> cols = Map.of(k(0, 0), c);
        final int[] img = MapImage.compose(cols, 0, 0, 16, 16);
        final int flat = img[5 * 16 + 5] & 0xFFFFFF;
        final int hill = img[8 * 16 + 8] & 0xFFFFFF;  // higher than the block north of it → brightest
        final int below = img[9 * 16 + 8] & 0xFFFFFF; // lower than the hill to the north → darkest
        assertTrue(((hill >> 8) & 255) > ((flat >> 8) & 255), "hill brighter");
        assertTrue(((below >> 8) & 255) < ((flat >> 8) & 255), "slope behind the hill darker");
        assertEquals(0, img.length - 256);
        final int[] missing = MapImage.compose(cols, 16, 0, 16, 16);
        assertEquals(0, missing[0] >>> 24, "chunk without data stays transparent");
        final int[] half = MapImage.downscale(img, 16, 16, 2);
        assertEquals(64, half.length);
        final byte[] png = MapImage.png(half, 8, 8);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
    }

    @Test
    void protectedAreasFileOfKraje() {
        final List<KrajeFiles.Area> a = KrajeFiles.parseAreas("""
            {"version":1,"areas":[{"id":"spawn","world":"world","minX":-100,"minZ":-50,"maxX":100,"maxZ":50,"bufferChunks":2},
                                   {"id":"zly"},{"id":"rynek","world":"world","minX":40,"minZ":40,"maxX":0,"maxZ":0}]}""");
        assertEquals(2, a.size());
        final KrajeFiles.Area spawn = a.get(0);
        assertEquals("Spawn", spawn.label());
        assertEquals((-100 >> 4) - 2, spawn.minCX());
        assertEquals((100 >> 4) + 2, spawn.maxCX());
        assertTrue(spawn.contains("world", 0, 0));
        assertFalse(spawn.contains("world_nether", 0, 0));
        assertEquals(0, a.get(1).minCX(), "min/max swapped");
        assertTrue(KrajeFiles.parseAreas("[]").isEmpty());
    }
}
