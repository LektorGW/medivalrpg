// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.ProtectedArea;
import pl.medivalrpg.kraje.storage.ProtectedAreaStorage;

class ProtectedAreasTest {

    /** Spawn from block (0,0) to (31,31) = chunks 0..1, buffer 3 → chunks -3..4. */
    private static ProtectedArea spawn() {
        return ProtectedArea.of("spawn", "world", 0, 0, 31, 31, 3);
    }

    @Test
    void edgesOfAreaAndBuffer() {
        final ProtectedArea a = spawn();
        assertTrue(a.containsChunk("world", 0, 0));
        assertTrue(a.containsChunk("world", 1, 1));
        assertTrue(a.containsChunk("world", -3, -3), "buffer corner");
        assertTrue(a.containsChunk("world", 4, 4), "buffer corner");
        assertTrue(a.containsChunk("world", 4, -3));
        assertFalse(a.containsChunk("world", 5, 0), "just outside the buffer");
        assertFalse(a.containsChunk("world", -4, 0));
        assertFalse(a.containsChunk("world", 0, 5));
        assertFalse(a.containsChunk("world", 0, -4));
    }

    @Test
    void zeroBufferCoversOnlyTouchedChunks() {
        final ProtectedArea a = ProtectedArea.of("plac", "world", 15, 15, 16, 16, 0);
        assertTrue(a.containsChunk("world", 0, 0), "block 15 lies in chunk 0");
        assertTrue(a.containsChunk("world", 1, 1), "block 16 lies in chunk 1");
        assertFalse(a.containsChunk("world", 2, 1));
        assertFalse(a.containsChunk("world", -1, 0));
    }

    @Test
    void otherWorldIsNotProtected() {
        final ProtectedAreas areas = new ProtectedAreas();
        areas.put(spawn());
        assertTrue(areas.isProtected("world", 0, 0));
        assertFalse(areas.isProtected("world_nether", 0, 0));
        assertFalse(areas.isProtected(null, 0, 0));
    }

    @Test
    void negativeCoordinatesUseFloorChunks() {
        // Blocks -1..-16 are chunk -1; block -17 is chunk -2.
        final ProtectedArea a = ProtectedArea.of("las", "world", -17, -1, -1, -16, 1);
        assertEquals(-2 - 1, a.minChunkX());
        assertEquals(-1 + 1, a.maxChunkX());
        assertEquals(-1 - 1, a.minChunkZ());
        assertEquals(-1 + 1, a.maxChunkZ());
        assertTrue(a.containsChunk("world", -3, -2));
        assertTrue(a.containsChunk("world", 0, 0));
        assertFalse(a.containsChunk("world", 1, 0));
        assertFalse(a.containsChunk("world", -4, 0));
        assertFalse(a.containsChunk("world", 0, -3));
    }

    @Test
    void minMaxAreSwapped() {
        final ProtectedArea a = ProtectedArea.of("spawn", "world", 31, 31, 0, 0, 3);
        assertEquals(spawn(), a);
    }

    @Test
    void validation() {
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("Spawn", "world", 0, 0, 1, 1, 0));
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("", "world", 0, 0, 1, 1, 0));
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("a".repeat(33), "world", 0, 0, 1, 1, 0));
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("spawn", " ", 0, 0, 1, 1, 0));
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("spawn", null, 0, 0, 1, 1, 0));
        assertEquals(ProtectedArea.Problem.BUFFER,
            assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("spawn", "world", 0, 0, 1, 1, 17)).problem());
        assertThrows(ProtectedArea.InvalidAreaException.class, () -> ProtectedArea.of("spawn", "world", 0, 0, 1, 1, -1));
        ProtectedArea.of("a".repeat(32), "world", 0, 0, 1, 1, 16);
        ProtectedArea.of("rynek_2-b", "world", 0, 0, 1, 1, 0);
    }

    @Test
    void sameIdReplacesArea() {
        final ProtectedAreas areas = new ProtectedAreas();
        assertTrue(areas.put(spawn()).isEmpty());
        final ProtectedArea moved = ProtectedArea.of("spawn", "world", 1000, 1000, 1015, 1015, 0);
        assertEquals(spawn(), areas.put(moved).orElseThrow());
        assertEquals(1, areas.all().size());
        assertFalse(areas.isProtected("world", 0, 0), "old rectangle no longer protected");
        assertTrue(areas.isProtected("world", 62, 62));
        assertEquals("spawn", areas.at("world", 63, 63).orElseThrow().id());
    }

    @Test
    void removeArea() {
        final ProtectedAreas areas = new ProtectedAreas();
        areas.put(spawn());
        areas.put(ProtectedArea.of("port", "world", 500, 500, 510, 510, 0));
        assertTrue(areas.remove("spawn").isPresent());
        assertTrue(areas.remove("spawn").isEmpty(), "second removal finds nothing");
        assertTrue(areas.remove(null).isEmpty());
        assertFalse(areas.isProtected("world", 0, 0));
        assertTrue(areas.isProtected("world", 31, 31));
        assertEquals(List.of("port"), areas.all().stream().map(ProtectedArea::id).toList());
    }

    @Test
    void labels() {
        assertEquals("Spawn", spawn().label());
        assertEquals("port", ProtectedArea.of("port", "world", 0, 0, 0, 0, 0).label());
    }

    @Test
    void codecRoundTripSkipsInvalidEntries() {
        final List<ProtectedArea> list = List.of(spawn(), ProtectedArea.of("las", "world_nether", -40, -40, -1, -1, 2));
        final var json = ProtectedAreaStorage.encode(list);
        assertEquals(list, ProtectedAreaStorage.decode(json, null));

        final var broken = ProtectedAreaStorage.encode(list);
        final var bad = new com.google.gson.JsonObject();
        bad.addProperty("id", "BAD ID");
        bad.addProperty("world", "world");
        bad.addProperty("minX", 0);
        bad.addProperty("minZ", 0);
        bad.addProperty("maxX", 0);
        bad.addProperty("maxZ", 0);
        broken.getAsJsonArray("areas").add(bad);
        assertEquals(list, ProtectedAreaStorage.decode(broken, null));
        assertTrue(ProtectedAreaStorage.decode(new com.google.gson.JsonObject(), null).isEmpty());
    }
}
