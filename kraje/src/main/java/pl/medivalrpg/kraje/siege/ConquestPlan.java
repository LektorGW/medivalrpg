// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * What happens to a sieged chunk (pure, used both when a siege starts and when it is won), keeping both
 * territories consistent:
 * <ul>
 *   <li>the chunk must belong to the defender;</li>
 *   <li>taking it may not split the defender's territory (same rule as {@code /kraj porzuc});</li>
 *   <li>the attacker only gets the chunk if it borders the attacker's land — otherwise its own territory
 *       would become disconnected, so the conquered chunk is razed to the wilderness instead;</li>
 *   <li>the capital can only be taken when it is the defender's last chunk, unless {@code capitalAnytime};
 *       a conquered capital that is not the last chunk moves to the defender's nearest remaining chunk;</li>
 *   <li>taking the last chunk ends the defender country.</li>
 * </ul>
 */
public final class ConquestPlan {

    public enum Problem { NONE, NOT_OWNED, SPLITS_DEFENDER, CAPITAL_PROTECTED, NOT_ADJACENT }

    /**
     * @param problem       why the conquest is impossible ({@link Problem#NONE} = possible)
     * @param toAttacker    the chunk joins the attacker (false = it becomes wilderness)
     * @param defenderFalls the defender loses its last chunk and is disbanded
     * @param newCapital    defender's new capital when its capital is taken but it survives, otherwise null
     */
    public record Outcome(Problem problem, boolean toAttacker, boolean defenderFalls, ChunkPos newCapital) {
        public boolean possible() {
            return this.problem == Problem.NONE;
        }
    }

    private ConquestPlan() {
    }

    /**
     * @param attackerClaims   attacker territory, world → keys
     * @param defenderClaims   defender territory, world → keys
     * @param defenderCapital  defender capital
     * @param target           sieged chunk
     * @param requireAdjacent  the chunk must border the attacker's land (checked at siege start)
     * @param capitalAnytime   the capital may be sieged even if it is not the last chunk
     */
    public static Outcome plan(final Map<String, Set<Long>> attackerClaims, final Map<String, Set<Long>> defenderClaims,
                               final ChunkPos defenderCapital, final ChunkPos target,
                               final boolean requireAdjacent, final boolean capitalAnytime) {
        return plan(attackerClaims,defenderClaims,defenderCapital,target,requireAdjacent,capitalAnytime,false);
    }

    /** A verified naval landing can create a new disconnected, owned bridgehead. */
    public static Outcome plan(final Map<String, Set<Long>> attackerClaims, final Map<String, Set<Long>> defenderClaims,
                               final ChunkPos defenderCapital, final ChunkPos target,
                               final boolean requireAdjacent, final boolean capitalAnytime, final boolean landing) {
        final Set<Long> defWorld = defenderClaims.getOrDefault(target.world(), Set.of());
        if (!defWorld.contains(target.key())) {
            return new Outcome(Problem.NOT_OWNED, false, false, null);
        }
        final boolean adjacent = ChunkGraph.isAdjacent(attackerClaims.getOrDefault(target.world(), Set.of()), target.key());
        if (requireAdjacent && !adjacent && !landing) {
            return new Outcome(Problem.NOT_ADJACENT, false, false, null);
        }
        int total = 0;
        for (final Set<Long> keys : defenderClaims.values()) {
            total += keys.size();
        }
        if (total <= 1) {
            return new Outcome(Problem.NONE, adjacent || landing, true, null);
        }
        if (target.equals(defenderCapital)) {
            if (!capitalAnytime) {
                return new Outcome(Problem.CAPITAL_PROTECTED, false, false, null);
            }
            if (!ChunkGraph.removalKeepsConnectivity(defWorld, target.key())) {
                return new Outcome(Problem.SPLITS_DEFENDER, false, false, null);
            }
            return new Outcome(Problem.NONE, adjacent || landing, false, nearestRemaining(defenderClaims, target));
        }
        final Long capitalKey = defenderCapital.world().equals(target.world()) ? defenderCapital.key() : null;
        if (!ChunkGraph.staysConnected(defWorld, target.key(), capitalKey)) {
            return new Outcome(Problem.SPLITS_DEFENDER, false, false, null);
        }
        return new Outcome(Problem.NONE, adjacent || landing, false, null);
    }

    /** Remaining defender chunk closest to {@code removed} (same world first), ties by coordinates. */
    private static ChunkPos nearestRemaining(final Map<String, Set<Long>> claims, final ChunkPos removed) {
        ChunkPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (final Map.Entry<String, Set<Long>> entry : claims.entrySet()) {
            final Set<Long> keys = new HashSet<>(entry.getValue());
            if (entry.getKey().equals(removed.world())) {
                keys.remove(removed.key());
            }
            for (final long key : keys) {
                final ChunkPos pos = new ChunkPos(entry.getKey(), ChunkKeys.x(key), ChunkKeys.z(key));
                final long dx = pos.x() - removed.x();
                final long dz = pos.z() - removed.z();
                final long d = entry.getKey().equals(removed.world()) ? dx * dx + dz * dz : Long.MAX_VALUE - 1;
                if (d < bestDistance || (d == bestDistance && best != null
                    && (pos.x() < best.x() || (pos.x() == best.x() && pos.z() < best.z())))) {
                    best = pos;
                    bestDistance = d;
                }
            }
        }
        return best;
    }
}
