// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.siege.ConquestPlan.Problem;
import pl.medivalrpg.kraje.util.ChunkKeys;

class SiegeTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------------ progress

    @Test
    void progressRisesFallsAndStands() {
        assertEquals(1.0 / 300, SiegeMath.step(0, 1, 0, 1, 300, 3), EPS, "one attacker alone");
        assertEquals(0.5, SiegeMath.step(0.5, 0, 0, 1, 300, 3), EPS, "nobody: no fight, progress stays");
        assertEquals(0.5, SiegeMath.step(0.5, 2, 2, 1, 300, 3), EPS, "balanced: stays");
        assertEquals(0.5 - 1.0 / 300, SiegeMath.step(0.5, 1, 2, 1, 300, 3), EPS, "defenders stronger: falls");
        assertEquals(0.0, SiegeMath.step(0.001, 0, 5, 1, 300, 3), EPS, "never below 0");
        assertEquals(1.0, SiegeMath.step(0.999, 5, 0, 1, 300, 3), EPS, "never above 1");
    }

    @Test
    void speedProportionalToAdvantageAndCapped() {
        assertEquals(2.0 / 300, SiegeMath.step(0, 3, 1, 1, 300, 3), EPS);
        assertEquals(3.0 / 300, SiegeMath.step(0, 10, 0, 1, 300, 3), EPS, "capped at max multiplier");
        assertEquals(0.5 / 300, SiegeMath.step(0, 1.5, 1, 1, 300, 3), EPS, "NPC soldiers can add fractions");
    }

    @Test
    void unopposedCaptureTakesCaptureSeconds() {
        double p = 0;
        int seconds = 0;
        while (p < 1.0 && seconds < 10_000) {
            p = SiegeMath.step(p, 1, 0, 1, 300, 3);
            seconds++;
        }
        assertTrue(seconds >= 299 && seconds <= 301, "seconds=" + seconds);
        assertEquals(1, SiegeMath.trend(2, 1));
        assertEquals(0, SiegeMath.trend(0, 0));
        assertEquals(-1, SiegeMath.trend(0, 1));
    }

    // ------------------------------------------------------------------ conquest consistency

    private static Set<Long> rect(final int x0, final int z0, final int x1, final int z1) {
        final Set<Long> set = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set.add(ChunkKeys.key(x, z));
            }
        }
        return set;
    }

    private static Map<String, Set<Long>> world(final Set<Long> keys) {
        final Map<String, Set<Long>> map = new HashMap<>();
        map.put("world", keys);
        return map;
    }

    private static ChunkPos pos(final int x, final int z) {
        return new ChunkPos("world", x, z);
    }

    /** Applies an outcome like the siege manager does and returns (attacker, defender) after it. */
    private static Set<Long>[] apply(final Set<Long> attacker, final Set<Long> defender, final ChunkPos target,
                                     final ConquestPlan.Outcome outcome) {
        final Set<Long> a = new HashSet<>(attacker);
        final Set<Long> d = new HashSet<>(defender);
        d.remove(target.key());
        if (outcome.toAttacker()) {
            a.add(target.key());
        }
        @SuppressWarnings("unchecked")
        final Set<Long>[] result = new Set[] {a, d};
        return result;
    }

    @Test
    void borderChunkGoesToAttackerAndBothStayConnected() {
        final Set<Long> attacker = rect(-3, 0, -1, 2);     // west
        final Set<Long> defender = rect(0, 0, 3, 2);       // east, capital (3,1)
        final ChunkPos target = pos(0, 1);
        final ConquestPlan.Outcome o = ConquestPlan.plan(world(attacker), world(defender), pos(3, 1), target, true, false);
        assertTrue(o.possible());
        assertTrue(o.toAttacker());
        assertFalse(o.defenderFalls());
        assertNull(o.newCapital());
        final Set<Long>[] after = apply(attacker, defender, target, o);
        assertTrue(ChunkGraph.isConnected(after[0]), "attacker connected");
        assertTrue(ChunkGraph.isConnected(after[1]), "defender connected");
    }

    @Test
    void chunkThatWouldSplitTheDefenderCannotBeTaken() {
        // defender is a line 0..4 at z=0 with the capital at 4; attacker touches (2,0) from the north
        final Set<Long> attacker = rect(2, 1, 2, 3);
        final Set<Long> defender = rect(0, 0, 4, 0);
        final ConquestPlan.Outcome o = ConquestPlan.plan(world(attacker), world(defender), pos(4, 0), pos(2, 0), true, false);
        assertEquals(Problem.SPLITS_DEFENDER, o.problem());
        // the end of the line is fine
        final Set<Long> attacker2 = rect(-1, 0, -1, 0);
        assertTrue(ConquestPlan.plan(world(attacker2), world(defender), pos(4, 0), pos(0, 0), true, false).possible());
    }

    @Test
    void nonAdjacentChunkIsRefusedAtStartAndRazedIfConquered() {
        final Set<Long> attacker = rect(-10, -10, -9, -9);
        final Set<Long> defender = rect(0, 0, 2, 0);
        assertEquals(Problem.NOT_ADJACENT,
            ConquestPlan.plan(world(attacker), world(defender), pos(0, 0), pos(2, 0), true, false).problem());
        // config require-adjacent: false → may be sieged; the attacker would be split, so the chunk is razed
        final ConquestPlan.Outcome o = ConquestPlan.plan(world(attacker), world(defender), pos(0, 0), pos(2, 0), false, false);
        assertTrue(o.possible());
        assertFalse(o.toAttacker());
        final Set<Long>[] after = apply(attacker, defender, pos(2, 0), o);
        assertTrue(ChunkGraph.isConnected(after[0]));
        assertTrue(ChunkGraph.isConnected(after[1]));
    }

    @Test
    void capitalOnlyWhenLastChunkAndLastChunkMeansFall() {
        final Set<Long> attacker = rect(-1, 0, -1, 0);
        final Set<Long> defender = rect(0, 0, 1, 0);
        assertEquals(Problem.CAPITAL_PROTECTED,
            ConquestPlan.plan(world(attacker), world(defender), pos(0, 0), pos(0, 0), true, false).problem());
        final ConquestPlan.Outcome last = ConquestPlan.plan(world(attacker), world(rect(0, 0, 0, 0)), pos(0, 0), pos(0, 0), true, false);
        assertTrue(last.possible());
        assertTrue(last.defenderFalls());
        assertTrue(last.toAttacker());
    }

    @Test
    void capitalAnytimeMovesTheCapital() {
        final Set<Long> attacker = rect(-1, 0, -1, 0);
        final Set<Long> defender = rect(0, 0, 2, 0);
        final ConquestPlan.Outcome o = ConquestPlan.plan(world(attacker), world(defender), pos(0, 0), pos(0, 0), true, true);
        assertTrue(o.possible());
        assertFalse(o.defenderFalls());
        assertEquals(pos(1, 0), o.newCapital(), "nearest remaining chunk");
        // a capital in the middle of the line would split the defender
        final Set<Long> attacker2 = rect(1, 1, 1, 1);
        assertEquals(Problem.SPLITS_DEFENDER,
            ConquestPlan.plan(world(attacker2), world(defender), pos(1, 0), pos(1, 0), true, true).problem());
    }

    @Test
    void chunkNoLongerOwned() {
        assertEquals(Problem.NOT_OWNED,
            ConquestPlan.plan(world(rect(0, 0, 0, 0)), world(rect(5, 5, 5, 5)), pos(5, 5), pos(1, 0), false, false).problem());
    }

    @Test
    void strengthFormatting() {
        assertEquals("3", SiegeManager.formatStrength(3.0));
        assertEquals("2.5", SiegeManager.formatStrength(2.5));
    }
}
