// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.util.ChunkKeys;

class AbstractConquestTest {

    private static final String W = "world";

    private static Set<Long> rect(final int x0, final int z0, final int x1, final int z1) {
        final Set<Long> out = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                out.add(ChunkKeys.key(x, z));
            }
        }
        return out;
    }

    /** Attacker 0..2 × 0..2, defender 3..5 × 0..2 (capital 5,1) unless overridden. */
    private static AbstractConquest.Situation sit(final Set<Long> att, final Set<Long> def, final ChunkPos target,
                                                  final boolean attNpc, final boolean defNpc, final boolean allowPlayers,
                                                  final boolean expedition, final boolean bridgeheadFree) {
        return sit(att, def, new ChunkPos(W, 5, 1), target, attNpc, defNpc, allowPlayers, expedition, bridgeheadFree);
    }

    private static AbstractConquest.Situation sit(final Set<Long> att, final Set<Long> def, final ChunkPos capital, final ChunkPos target,
                                                  final boolean attNpc, final boolean defNpc, final boolean allowPlayers,
                                                  final boolean expedition, final boolean bridgeheadFree) {
        return new AbstractConquest.Situation(true, true, allowPlayers, attNpc, defNpc, true, false, false, false, false, expedition,
            Map.of(W, att), Map.of(W, def), capital, target, false, bridgeheadFree);
    }

    @Test
    void adjacentBorderChunkOfNpcCanBeTaken() {
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.problem(
            sit(rect(0, 0, 2, 2), rect(3, 0, 5, 2), new ChunkPos(W, 3, 1), true, true, false, false, true)));
    }

    @Test
    void playerCountriesAreNeverTakenInTheBackgroundByDefault() {
        // defender is a player country
        assertEquals(AbstractConquest.Problem.PLAYER_COUNTRY, AbstractConquest.problem(
            sit(rect(0, 0, 2, 2), rect(3, 0, 5, 2), new ChunkPos(W, 3, 1), true, false, false, false, true)));
        // attacker is a player country
        assertEquals(AbstractConquest.Problem.PLAYER_COUNTRY, AbstractConquest.problem(
            sit(rect(0, 0, 2, 2), rect(3, 0, 5, 2), new ChunkPos(W, 3, 1), false, true, false, false, true)));
        // only with the explicit switch
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.problem(
            sit(rect(0, 0, 2, 2), rect(3, 0, 5, 2), new ChunkPos(W, 3, 1), true, false, true, false, true)));
    }

    @Test
    void capitalOnlyAsLastChunk() {
        final Set<Long> def = rect(3, 1, 5, 1); // 3,1 4,1 5,1 (capital 5,1)
        final Set<Long> att = rect(3, 0, 5, 0); // borders all three from the north
        assertEquals(AbstractConquest.Problem.CAPITAL_PROTECTED, AbstractConquest.problem(
            sit(att, def, new ChunkPos(W, 5, 1), true, true, false, false, true)));
        final Set<Long> last = Set.of(ChunkKeys.key(5, 1));
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.problem(
            sit(rect(5, 0, 5, 0), last, new ChunkPos(W, 5, 1), true, true, false, false, true)));
    }

    @Test
    void territoryOfTheDefenderStaysConnected() {
        // defender is a line 3,1..5,1 (capital 5,1); taking 4,1 would cut 3,1 from the capital
        final Set<Long> def = new HashSet<>(rect(3, 1, 5, 1));
        assertEquals(AbstractConquest.Problem.SPLITS_DEFENDER, AbstractConquest.problem(
            sit(rect(4, 2, 4, 2), def, new ChunkPos(W, 4, 1), true, true, false, false, true)));
    }

    @Test
    void farTargetNeedsAnExpeditionOnTheEdgeAndAFreeBridgehead() {
        final Set<Long> att = rect(0, 0, 1, 1);
        final Set<Long> def = rect(20, 0, 24, 4);
        final ChunkPos edge = new ChunkPos(W, 20, 2);
        final ChunkPos inner = new ChunkPos(W, 22, 2);
        final ChunkPos cap = new ChunkPos(W, 24, 2);
        assertEquals(AbstractConquest.Problem.NOT_ADJACENT, AbstractConquest.problem(sit(att, def, cap, edge, true, true, false, false, true)));
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.problem(sit(att, def, cap, edge, true, true, false, true, true)));
        assertEquals(AbstractConquest.Problem.NOT_BORDER, AbstractConquest.problem(sit(att, def, cap, inner, true, true, false, true, true)));
        assertEquals(AbstractConquest.Problem.BRIDGEHEAD_LIMIT, AbstractConquest.problem(sit(att, def, cap, edge, true, true, false, true, false)));
    }

    @Test
    void noWarNoPlayersNearNoRunningSiege() {
        final Set<Long> att = rect(0, 0, 2, 2);
        final Set<Long> def = rect(3, 0, 5, 2);
        final ChunkPos t = new ChunkPos(W, 3, 1);
        final ChunkPos cap = new ChunkPos(W, 5, 1);
        assertEquals(AbstractConquest.Problem.NOT_AT_WAR, AbstractConquest.problem(new AbstractConquest.Situation(true, true, false, true, true,
            false, false, false, false, false, false, Map.of(W, att), Map.of(W, def), cap, t, false, true)));
        assertEquals(AbstractConquest.Problem.PLAYERS_NEAR, AbstractConquest.problem(new AbstractConquest.Situation(true, true, false, true, true,
            true, false, false, true, false, false, Map.of(W, att), Map.of(W, def), cap, t, false, true)));
        assertEquals(AbstractConquest.Problem.SIEGE_RUNNING, AbstractConquest.problem(new AbstractConquest.Situation(true, true, false, true, true,
            true, true, false, false, false, false, Map.of(W, att), Map.of(W, def), cap, t, false, true)));
        assertEquals(AbstractConquest.Problem.DISABLED, AbstractConquest.problem(new AbstractConquest.Situation(false, true, false, true, true,
            true, false, false, false, false, false, Map.of(W, att), Map.of(W, def), cap, t, false, true)));
        assertEquals(AbstractConquest.Problem.PROTECTED, AbstractConquest.problem(new AbstractConquest.Situation(true, true, false, true, true,
            true, false, false, false, true, false, Map.of(W, att), Map.of(W, def), cap, t, false, true)));
    }

    @Test
    void backgroundRevoltNeverTouchesPlayerCountriesByDefault() {
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.revoltProblem(true, false, true, true));
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.revoltProblem(true, false, true, null)); // NPC land → wilderness
        assertEquals(AbstractConquest.Problem.PLAYER_COUNTRY, AbstractConquest.revoltProblem(true, false, false, true)); // player holder
        assertEquals(AbstractConquest.Problem.PLAYER_COUNTRY, AbstractConquest.revoltProblem(true, false, false, null));
        assertEquals(AbstractConquest.Problem.PLAYER_COUNTRY, AbstractConquest.revoltProblem(true, false, true, false)); // back to a player
        assertEquals(AbstractConquest.Problem.NONE, AbstractConquest.revoltProblem(true, true, false, false)); // explicitly allowed
        assertEquals(AbstractConquest.Problem.DISABLED, AbstractConquest.revoltProblem(false, true, true, true));
    }
}
