// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Limits, chunk planning, buildings, personality parsing and the decision log. */
class PlanningTest {

    private static long k(final int x, final int z) {
        return ChunkPlanner.key(x, z);
    }

    @Test
    void chunkKeyMatchesKrajeFormat() {
        assertEquals(((long) -3 << 32) | (7 & 0xFFFFFFFFL), k(-3, 7));
        assertEquals(-3, ChunkPlanner.cx(k(-3, 7)));
        assertEquals(-7, ChunkPlanner.cz(k(3, -7)));
    }

    @Test
    void expansionPrefersCompactAndNearCapital() {
        // own: (0,0) (1,0) (0,1). (1,1) touches two own chunks.
        final Set<Long> own = Set.of(k(0, 0), k(1, 0), k(0, 1));
        final List<Long> c = ChunkPlanner.expansionCandidates(own, key -> key != k(-1, 0), 0, 0, 10);
        assertEquals(k(1, 1), c.get(0));
        assertFalse(c.contains(k(-1, 0))); // not free
        assertFalse(c.contains(k(0, 0)));
        assertEquals(2, ChunkPlanner.expansionCandidates(own, key -> true, 0, 0, 2).size());
    }

    @Test
    void borderPrioritisesEnemies() {
        final Set<Long> own = Set.of(k(0, 0), k(1, 0), k(2, 0));
        final List<Long> border = ChunkPlanner.borderChunks(own, key -> key == k(3, 0), key -> key == k(-1, 0) || key == k(3, 0));
        assertEquals(k(2, 0), border.get(0)); // faces the enemy
        assertEquals(k(0, 0), border.get(1)); // faces another country
        assertEquals(3, border.size());
        assertEquals(List.of(k(2, 0), k(0, 0), k(1, 0), k(2, 0)), ChunkPlanner.guardPosts(border, 4));
        assertTrue(ChunkPlanner.guardPosts(List.of(), 3).isEmpty());
        assertEquals(List.of(k(3, 0)), ChunkPlanner.enemyFront(own, key -> key == k(3, 0)));
    }

    @Test
    void siegeMustNotSplitDefender() {
        // line (0,0)-(1,0)-(2,0): the middle chunk would split it, the ends would not
        final Set<Long> line = Set.of(k(0, 0), k(1, 0), k(2, 0));
        assertTrue(ChunkPlanner.wouldDisconnect(line, k(1, 0)));
        assertFalse(ChunkPlanner.wouldDisconnect(line, k(2, 0)));
        // 2x2 block: any corner is safe
        final Set<Long> block = Set.of(k(0, 0), k(1, 0), k(0, 1), k(1, 1));
        assertFalse(ChunkPlanner.wouldDisconnect(block, k(0, 0)));
        assertFalse(ChunkPlanner.wouldDisconnect(Set.of(k(0, 0), k(1, 0)), k(0, 0)));
        assertFalse(ChunkPlanner.wouldDisconnect(line, k(9, 9)));
    }

    @Test
    void limits() {
        assertEquals(10, ArmyLimits.countryCap(5, 0.5, 10, 60));
        assertEquals(60, ArmyLimits.countryCap(5, 0.5, 1000, 60));
        assertEquals(3, ArmyLimits.allowed(5, 7, 10, 0, 150));
        assertEquals(1, ArmyLimits.allowed(5, 0, 10, 149, 150));
        assertEquals(0, ArmyLimits.allowed(5, 10, 10, 0, 150));
    }

    @Test
    void buildingsHousingFirstThenUtilities() {
        final List<String> housing = List.of("dom", "chata");
        final List<String> utilities = List.of("spichlerz", "koszary");
        final Set<String> available = Set.of("dom", "chata", "spichlerz", "koszary");
        assertEquals(Optional.of("dom"), BuildingPlanner.next(4, 4, Map.of(), available, housing, utilities, 5));
        assertEquals(Optional.of("spichlerz"), BuildingPlanner.next(2, 8, Map.of(), available, housing, utilities, 5));
        assertEquals(Optional.of("koszary"), BuildingPlanner.next(2, 8, Map.of("spichlerz", 1), available, housing, utilities, 5));
        assertEquals(Optional.empty(), BuildingPlanner.next(2, 8, Map.of("spichlerz", 1, "koszary", 1), available, housing, utilities, 5));
        // housing cap reached → utilities
        assertEquals(Optional.of("spichlerz"), BuildingPlanner.next(4, 4, Map.of("dom", 5), available, housing, utilities, 5));
        // unknown types are skipped
        assertEquals(Optional.of("chata"), BuildingPlanner.next(4, 4, Map.of(), Set.of("chata"), housing, utilities, 5));
    }

    @Test
    void personalityParsing() {
        final Personality p = new Personality(50, 50, 50, 50, 40, 20, true);
        assertEquals(80, p.with("agresja", "80").aggression());
        assertEquals(12, p.with("max-chunki", "12").maxChunks());
        assertFalse(p.with("aktywna", "nie").active());
        assertEquals("tak", p.get("aktywna"));
        assertThrows(IllegalArgumentException.class, () -> p.with("agresja", "101"));
        assertThrows(IllegalArgumentException.class, () -> p.with("agresja", "dużo"));
        assertThrows(IllegalArgumentException.class, () -> p.with("odwaga", "5"));
        assertEquals(100, new Personality(500, -5, 0, 0, 0, -1, true).aggression());
        assertEquals(1, new Personality(500, -5, 0, 0, 0, -1, true).maxChunks());
    }

    @Test
    void decisionLogKeepsLast() {
        final DecisionLog log = new DecisionLog(3);
        for (int i = 0; i < 5; i++) {
            log.add(i, "d" + i);
        }
        assertEquals(3, log.size());
        assertEquals(List.of("d3", "d4"), log.last(2).stream().map(DecisionLog.Entry::text).toList());
        assertEquals(3, log.last(10).size());
    }
}
