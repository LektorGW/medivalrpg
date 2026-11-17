// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.Locale;
import java.util.UUID;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Who may hurt whom (pure).
 *
 * <ul>
 *   <li>{@link Mode#VANILLA} (default): Kraje does not block player combat at all, except — when
 *       {@code friendlyFire} is false — between members of the same country and between allies.</li>
 *   <li>{@link Mode#WAR_ONLY}: players of different countries may fight only when their countries are at war.
 *       Same country / allies: {@code friendlyFire}. A player without a country (attacker or victim):
 *       {@code withoutCountry}. Fights in the wilderness: {@code wildernessFree} = always allowed there.</li>
 * </ul>
 */
public final class PvpRules {

    public enum Mode {
        VANILLA, WAR_ONLY;

        public static Mode parse(final String text) {
            final String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return s.equals("war-only") || s.equals("waronly") ? WAR_ONLY : VANILLA;
        }
    }

    /** Config switches. */
    public record Settings(Mode mode, boolean friendlyFire, boolean withoutCountry, boolean wildernessFree) {
    }

    private PvpRules() {
    }

    /**
     * @param attackerCountry attacker's country or null
     * @param victimCountry   victim's country or null
     * @param relation        relation between the two countries (NEUTRAL if unknown / same)
     * @param inWilderness    the victim stands in the wilderness
     */
    public static boolean allowed(final Settings s, final UUID attackerCountry, final UUID victimCountry,
                                  final Relation relation, final boolean inWilderness) {
        final boolean bothInCountries = attackerCountry != null && victimCountry != null;
        final boolean friendly = bothInCountries
            && (attackerCountry.equals(victimCountry) || relation == Relation.ALLIANCE);
        if (friendly && !s.friendlyFire()) {
            return false;
        }
        if (s.mode() == Mode.VANILLA || friendly) {
            return true;
        }
        if (s.wildernessFree() && inWilderness) {
            return true;
        }
        if (!bothInCountries) {
            return s.withoutCountry();
        }
        return relation == Relation.WAR;
    }
}
