// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.UUID;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/** How a citizen reacts to a player who comes close (pure). */
public final class ReactionRules {

    /** Who the player is for the citizen. {@link #key()} = condition {@code relacje} in the text pools. */
    public enum Standing {
        RULER("wladca", Action.GREET),
        COMPATRIOT("rodak", Action.GREET),
        OVERLORD("zwierzchnik", Action.GREET),
        ALLY("sojusznik", Action.GREET),
        STRANGER("obcy", Action.SUSPICIOUS),
        ENEMY("wrog", Action.FLEE);

        private final String key;
        private final Action action;

        Standing(final String key, final Action action) {
            this.key = key;
            this.action = action;
        }

        public String key() {
            return this.key;
        }

        public Action action() {
            return this.action;
        }
    }

    /** What the citizen does. */
    public enum Action {
        GREET,
        SUSPICIOUS,
        /** Runs home, shouts for the guard, nearby citizens hide too. */
        FLEE
    }

    private ReactionRules() {
    }

    /**
     * @param citizenCountry country of the villager
     * @param playerCountry  country of the player (null = none)
     * @param playerRank     rank of the player in {@code playerCountry} (null = none)
     * @param relation       relation between the two countries (ignored when equal or the player has none)
     * @param overlordOfCitizen overlord of the villager's country (null = none)
     */
    public static Standing classify(final UUID citizenCountry, final UUID playerCountry, final Rank playerRank,
                                    final Relation relation, final UUID overlordOfCitizen) {
        if (playerCountry == null) {
            return Standing.STRANGER;
        }
        if (playerCountry.equals(citizenCountry)) {
            return playerRank == Rank.RULER ? Standing.RULER : Standing.COMPATRIOT;
        }
        if (relation == Relation.WAR) {
            return Standing.ENEMY;
        }
        if (playerCountry.equals(overlordOfCitizen)) {
            return Standing.OVERLORD;
        }
        if (relation == Relation.ALLIANCE) {
            return Standing.ALLY;
        }
        return Standing.STRANGER;
    }
}
