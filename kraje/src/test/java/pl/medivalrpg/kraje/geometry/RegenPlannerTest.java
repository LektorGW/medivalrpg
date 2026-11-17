// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Batch;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Plan;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Skip;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Chunk selection, batching, estimate and progress of the terrain restore. */
class RegenPlannerTest {

    private static long k(final int x, final int z) {
        return ChunkKeys.key(x, z);
    }

    /** Spawn = chunks with |x| ≤ 1 and |z| ≤ 1 of world "world". */
    private static boolean spawn(final String world, final long key) {
        return world.equals("world") && Math.abs(ChunkKeys.x(key)) <= 1 && Math.abs(ChunkKeys.z(key)) <= 1;
    }

    @Test
    void wholeCountrySkipsProtectedChunksInEveryWorld() {
        final Map<String, Set<Long>> claims = new LinkedHashMap<>();
        claims.put("world", Set.copyOf(AreaPlanner.square(0, 0, 2)));   // 25 chunks, 9 of them spawn
        claims.put("world_nether", Set.of(k(0, 0), k(5, 5)));           // spawn rule only in "world"
        final Plan plan = RegenPlanner.country(claims, w -> key -> spawn(w, key));
        assertEquals(16 + 2, plan.count());
        assertEquals(9, plan.skipped(Skip.PROTECTED));
        assertEquals(9, plan.skippedCount());
        assertTrue(plan.chunks().get("world").stream().noneMatch(key -> spawn("world", key)));
        assertEquals(List.of("world", "world_nether"), List.copyOf(plan.chunks().keySet()));
    }

    @Test
    void worldWithOnlyProtectedChunksIsDropped() {
        final Plan plan = RegenPlanner.country(Map.of("world", Set.of(k(0, 0), k(1, 1))), w -> key -> spawn(w, key));
        assertEquals(0, plan.count());
        assertTrue(plan.chunks().isEmpty());
        assertEquals(2, plan.skipped(Skip.PROTECTED));
    }

    @Test
    void countryPartTakesOnlyOwnChunksOfTheArea() {
        final Set<Long> territory = Set.of(k(10, 10), k(11, 10), k(12, 10), k(30, 30));
        final List<Long> area = AreaPlanner.square(11, 10, 1); // 9 chunks around (11,10)
        final Plan plan = RegenPlanner.countryPart("world", area, territory, key -> key == k(12, 10));
        assertEquals(List.of(k(11, 10), k(10, 10)), plan.chunks().get("world"));
        assertEquals(1, plan.skipped(Skip.PROTECTED));
        assertEquals(6, plan.skipped(Skip.NOT_OWN));
    }

    @Test
    void wildernessSkipsClaimedAndProtected() {
        final Set<Long> claimed = Set.of(k(1, 0), k(2, 0));
        final Plan plan = RegenPlanner.wilderness("world", AreaPlanner.square(3, 0, 2), new RegenPlanner.Land() {
            @Override
            public boolean claimed(final long key) {
                return claimed.contains(key);
            }

            @Override
            public boolean isProtected(final long key) {
                return spawn("world", key);
            }
        });
        // square x 1..5, z -2..2 = 25; claimed (1,0),(2,0); protected x=1, z -1..1 → (1,-1),(1,1) (+ (1,0) already claimed)
        assertEquals(2, plan.skipped(Skip.CLAIMED));
        assertEquals(2, plan.skipped(Skip.PROTECTED));
        assertEquals(21, plan.count());
    }

    @Test
    void batchesCoverEveryChunkOnceAndRespectTheSize() {
        final List<Long> chunks = new ArrayList<>(AreaPlanner.square(0, 0, 6)); // 169 chunks
        chunks.add(k(100, -100));
        for (final int size : new int[] {1, 2, 4, 5, 9, 16, 99}) {
            final List<Batch> batches = RegenPlanner.batches(Map.of("world", chunks), size);
            final Set<Long> seen = new HashSet<>();
            int total = 0;
            for (final Batch b : batches) {
                assertEquals("world", b.world());
                assertTrue(!b.chunks().isEmpty() && b.chunks().size() <= Math.min(size, RegenPlanner.MAX_BATCH), "size " + size);
                total += b.chunks().size();
                seen.addAll(b.chunks());
            }
            assertEquals(chunks.size(), total, "size " + size);
            assertEquals(new HashSet<>(chunks), seen, "size " + size);
        }
    }

    @Test
    void batchesAreCompactTiles() {
        // 4 per batch → 2×2 tiles: every batch fits in a 2×2 square
        final List<Batch> batches = RegenPlanner.batches(Map.of("world", AreaPlanner.square(0, 0, 3)), 4);
        for (final Batch b : batches) {
            final int minX = b.chunks().stream().mapToInt(ChunkKeys::x).min().orElseThrow();
            final int maxX = b.chunks().stream().mapToInt(ChunkKeys::x).max().orElseThrow();
            final int minZ = b.chunks().stream().mapToInt(ChunkKeys::z).min().orElseThrow();
            final int maxZ = b.chunks().stream().mapToInt(ChunkKeys::z).max().orElseThrow();
            assertTrue(maxX - minX <= 1 && maxZ - minZ <= 1, b.toString());
        }
        // 7×7 chunks in 2×2 tiles aligned to even coordinates: x and z each span 4 tiles → 16 batches
        assertEquals(16, batches.size());
    }

    @Test
    void batchesKeepWorldsApart() {
        final Map<String, List<Long>> chunks = new LinkedHashMap<>();
        chunks.put("a", List.of(k(0, 0), k(0, 1)));
        chunks.put("b", List.of(k(0, 0)));
        final List<Batch> batches = RegenPlanner.batches(chunks, 16);
        assertEquals(2, batches.size());
        assertEquals("a", batches.get(0).world());
        assertEquals(2, batches.get(0).chunks().size());
        assertEquals("b", batches.get(1).world());
    }

    @Test
    void serpentineOrderKeepsConsecutiveBatchesAdjacent() {
        final List<Batch> batches = RegenPlanner.batches(Map.of("world", AreaPlanner.square(0, 0, 4)), 1);
        for (int i = 1; i < batches.size(); i++) {
            final long a = batches.get(i - 1).chunks().getFirst();
            final long b = batches.get(i).chunks().getFirst();
            final int dist = Math.abs(ChunkKeys.x(a) - ChunkKeys.x(b)) + Math.abs(ChunkKeys.z(a) - ChunkKeys.z(b));
            assertEquals(1, dist, "between " + (i - 1) + " and " + i);
        }
    }

    @Test
    void estimate() {
        assertEquals(0, RegenPlanner.estimateMillis(0, 4, 100, 4));
        // 10 chunks × 100 ms + 2 pauses (3 batches) × 4 ticks × 50 ms
        assertEquals(1000 + 400, RegenPlanner.estimateMillis(10, 4, 100, 4));
        assertEquals(1000 + 9 * 200, RegenPlanner.estimateMillis(10, 1, 100, 4));
    }

    @Test
    void milestones() {
        assertEquals(-1, RegenPlanner.milestone(5, 100, 10, 0));
        assertEquals(10, RegenPlanner.milestone(10, 100, 10, 0));
        assertEquals(-1, RegenPlanner.milestone(15, 100, 10, 10));
        assertEquals(30, RegenPlanner.milestone(35, 100, 10, 10)); // a big batch jumps over 20 %
        assertEquals(-1, RegenPlanner.milestone(100, 100, 10, 90)); // the end has its own message
        assertEquals(50, RegenPlanner.milestone(1, 2, 10, 0));
        assertEquals(-1, RegenPlanner.milestone(1, 0, 10, 0));
    }

    @Test
    void smoothing() {
        assertEquals(200.0, RegenPlanner.smooth(0, 200), 1e-9);
        assertEquals(130.0, RegenPlanner.smooth(100, 200), 1e-9);
    }
}
