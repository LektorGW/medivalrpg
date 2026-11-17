// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.planner.TerrainView;

class TerrainCacheInvalidationTest {
    @SuppressWarnings("unchecked")
    @Test
    void levellingInvalidatesOldHillUntilFreshSnapshotAndRequestsOnlyOneRescan() throws Exception {
        final TerrainCache cache = new TerrainCache(null);
        final var field = TerrainCache.class.getDeclaredField("surfaces");
        field.setAccessible(true);
        final Map<ChunkRef, TerrainCache.Surface> surfaces = (Map<ChunkRef, TerrainCache.Surface>) field.get(cache);
        final ChunkRef chunk = new ChunkRef("world", 0, 0);
        final int[] oldHill = new int[256];
        Arrays.fill(oldHill, 78);
        surfaces.put(chunk, new TerrainCache.Surface(oldHill, new byte[256], System.currentTimeMillis()));
        assertEquals(78, cache.view("world", UUID.randomUUID()).height(4, 4));
        cache.refresh("world", new BlockBox("world", 1, 0, 1, 8, 0, 8));
        assertFalse(cache.known(chunk));
        assertEquals(TerrainView.UNKNOWN, cache.view("world", UUID.randomUUID()).height(4, 4));
        assertEquals(1, cache.pending());
        cache.request(chunk);
        assertEquals(1, cache.pending());
        final int[] finishedGrade = new int[256];
        Arrays.fill(finishedGrade, 64);
        surfaces.put(chunk, new TerrainCache.Surface(finishedGrade, new byte[256], System.currentTimeMillis()));
        assertTrue(cache.known(chunk));
        assertEquals(64, cache.view("world", UUID.randomUUID()).height(4, 4));
    }
}
