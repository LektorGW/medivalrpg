// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.util.ChunkKeys;

class BankruptcyPlannerTest {

    private static Set<Long> keys(final int[]... coords) {
        final Set<Long> set = new HashSet<>();
        for (final int[] c : coords) {
            set.add(ChunkKeys.key(c[0], c[1]));
        }
        return set;
    }

    private static ChunkPos pos(final int x, final int z) {
        return new ChunkPos("world", x, z);
    }

    @Test
    void removesFarthestFromCapitalFirst() {
        // line 0..5, capital at 0
        final Map<String, Set<Long>> claims = Map.of("world",
            keys(new int[] {0, 0}, new int[] {1, 0}, new int[] {2, 0}, new int[] {3, 0}, new int[] {4, 0}, new int[] {5, 0}));
        assertEquals(List.of(pos(5, 0), pos(4, 0)), BankruptcyPlanner.chunksToLose(claims, pos(0, 0), 2));
    }

    @Test
    void distanceIsMeasuredThroughTheTerritory() {
        // U shape: (2,2) is close in a straight line to the capital (0,2) but far along the territory
        //  z=2: C . X
        //  z=1: # . #
        //  z=0: # # #
        final Map<String, Set<Long>> claims = Map.of("world", keys(
            new int[] {0, 2}, new int[] {0, 1}, new int[] {0, 0}, new int[] {1, 0}, new int[] {2, 0}, new int[] {2, 1}, new int[] {2, 2}));
        assertEquals(List.of(pos(2, 2)), BankruptcyPlanner.chunksToLose(claims, pos(0, 2), 1));
    }

    @Test
    void neverTheCapitalAndAlwaysConnected() {
        final Set<Long> territory = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                territory.add(ChunkKeys.key(x, z));
            }
        }
        territory.remove(ChunkKeys.key(1, 0)); // a hole next to the capital
        final Map<String, Set<Long>> claims = new HashMap<>(Map.of("world", territory));
        final ChunkPos capital = pos(0, 0);
        final List<ChunkPos> lost = BankruptcyPlanner.chunksToLose(claims, capital, 100);
        assertEquals(territory.size() - 1, lost.size(), "everything but the capital");
        assertFalse(lost.contains(capital));
        // after every single removal the rest stays connected
        final Set<Long> remaining = new HashSet<>(territory);
        for (final ChunkPos p : lost) {
            remaining.remove(p.key());
            assertTrue(ChunkGraph.isConnected(remaining), "split after removing " + p);
        }
        assertEquals(Set.of(capital.key()), remaining);
        assertEquals(territory, claims.get("world"), "input not modified");
    }

    @Test
    void cutOffFragmentsAndOtherWorldsGoFirst() {
        final Map<String, Set<Long>> claims = new HashMap<>();
        claims.put("world", keys(new int[] {0, 0}, new int[] {1, 0}, new int[] {9, 9}));
        claims.put("world_nether", keys(new int[] {0, 0}));
        final List<ChunkPos> lost = BankruptcyPlanner.chunksToLose(claims, pos(0, 0), 2);
        assertTrue(lost.contains(pos(9, 9)), lost.toString());
        assertTrue(lost.contains(new ChunkPos("world_nether", 0, 0)), lost.toString());
    }

    @Test
    void onlyCapitalLeft() {
        assertTrue(BankruptcyPlanner.chunksToLose(Map.of("world", keys(new int[] {0, 0})), pos(0, 0), 3).isEmpty());
    }
}
