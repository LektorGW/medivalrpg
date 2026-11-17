// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.vassal;

import java.util.UUID;
import pl.medivalrpg.kraje.model.Relation;

/**
 * What vassals and overlords may do in diplomacy (pure, no Bukkit). Used by {@code /kraj wojna}, {@code /kraj sojusz},
 * the API and the tests.
 */
public final class VassalRules {

    /** Config switches ({@code vassal.*}). */
    public record Settings(boolean forbidWarOnOverlord, boolean forbidWarOnOverlordAllies,
                           boolean forbidAllianceWithOverlordEnemies, boolean overlordWarOnlyForDebt, int debtWarDays) {
    }

    /** Read access to the current state. */
    public interface State {
        /** Overlord of a country, null when independent. */
        UUID overlordOf(UUID country);

        Relation relation(UUID a, UUID b);

        /** Days of unpaid tribute of a vassal (0 when none). */
        int debtDays(UUID vassal);
    }

    public enum Verdict {
        ALLOWED,
        /** Overlord punishes a vassal with overdue tribute: no cost, no cooldown. */
        ALLOWED_FREE,
        /** A vassal may not attack its overlord. */
        DENIED_OVERLORD,
        /** A vassal may not attack an ally of its overlord. */
        DENIED_OVERLORD_ALLY,
        /** A vassal may not ally with an enemy of its overlord. */
        DENIED_OVERLORD_ENEMY,
        /** An overlord may attack its vassal only for overdue tribute. */
        DENIED_OWN_VASSAL;

        public boolean allowed() {
            return this == ALLOWED || this == ALLOWED_FREE;
        }
    }

    private VassalRules() {
    }

    /** May {@code from} declare war on {@code to}? */
    public static Verdict war(final Settings s, final State state, final UUID from, final UUID to) {
        final UUID fromOverlord = state.overlordOf(from);
        if (fromOverlord != null) {
            if (fromOverlord.equals(to)) {
                if (s.forbidWarOnOverlord()) {
                    return Verdict.DENIED_OVERLORD;
                }
            } else if (s.forbidWarOnOverlordAllies() && state.relation(fromOverlord, to) == Relation.ALLIANCE) {
                return Verdict.DENIED_OVERLORD_ALLY;
            }
        }
        if (from.equals(state.overlordOf(to))) {
            if (state.debtDays(to) >= Math.max(1, s.debtWarDays())) {
                return Verdict.ALLOWED_FREE;
            }
            if (s.overlordWarOnlyForDebt()) {
                return Verdict.DENIED_OWN_VASSAL;
            }
        }
        return Verdict.ALLOWED;
    }

    /** May {@code a} and {@code b} become allies (checked for both sides)? */
    public static Verdict alliance(final Settings s, final State state, final UUID a, final UUID b) {
        if (!s.forbidAllianceWithOverlordEnemies()) {
            return Verdict.ALLOWED;
        }
        final UUID overlordA = state.overlordOf(a);
        if (overlordA != null && !overlordA.equals(b) && state.relation(overlordA, b) == Relation.WAR) {
            return Verdict.DENIED_OVERLORD_ENEMY;
        }
        final UUID overlordB = state.overlordOf(b);
        if (overlordB != null && !overlordB.equals(a) && state.relation(overlordB, a) == Relation.WAR) {
            return Verdict.DENIED_OVERLORD_ENEMY;
        }
        return Verdict.ALLOWED;
    }
}
