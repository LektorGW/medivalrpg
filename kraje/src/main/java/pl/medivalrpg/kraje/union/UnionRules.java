// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.union;

import java.util.Comparator;
import java.util.UUID;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Rules of personal unions (pure, no Bukkit): who may form one, when it may be broken, which union partner joins a
 * war and who takes the partner's throne when the union ends. Used by {@link UnionService}, the API and the tests.
 *
 * <p>Formation rules: both countries exist and differ; the proposing (home) country is a player country; an NPC
 * partner only with {@code allowNpc}; neither is already in a union; they are not at war; neither is a vassal (a
 * vassal first has to become independent — this also excludes an overlord uniting with its own vassal); after a
 * union between the same two countries ended, {@code reformCooldown} must pass.</p>
 */
public final class UnionRules {

    /**
     * Config switches ({@code union.*}).
     *
     * @param enabled             unions on/off
     * @param allowNpc            a player country may unite with an NPC country (which stays AI-run)
     * @param minDurationMillis   a union can be broken by command only after this time
     * @param reformCooldownMillis pause before the same two countries may unite again
     * @param defendPartner       a war declared on one union country drags the partner in as a defender
     * @param joinOffensiveWars   a war declared by one union country drags the partner in as an attacker
     */
    public record Settings(boolean enabled, boolean allowNpc, long minDurationMillis, long reformCooldownMillis,
                           boolean defendPartner, boolean joinOffensiveWars) {
    }

    /** Read access to the current state. */
    public interface State {
        boolean exists(UUID country);

        boolean npc(UUID country);

        /** Overlord of a country, null when independent. */
        UUID overlordOf(UUID country);

        /** The other country of the union of {@code country}, null when none. */
        UUID unionPartnerOf(UUID country);

        Relation relation(UUID a, UUID b);

        /** Epoch millis when the last union of exactly these two countries ended (0 = never / unknown). */
        long unionEndedAt(UUID a, UUID b);
    }

    public enum Verdict {
        ALLOWED,
        DISABLED,
        NOT_FOUND,
        SAME_COUNTRY,
        /** The proposing side must be a player country (its ruler becomes the shared ruler). */
        NPC_HOME,
        /** Unions with NPC countries are off. */
        NPC_PARTNER,
        HOME_IN_UNION,
        PARTNER_IN_UNION,
        AT_WAR,
        HOME_VASSAL,
        PARTNER_VASSAL,
        COOLDOWN;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    private UnionRules() {
    }

    /** May {@code home} (its ruler becoming the shared ruler) form a union with {@code partner} at {@code now}? */
    public static Verdict form(final Settings s, final State st, final UUID home, final UUID partner, final long now) {
        if (!s.enabled()) {
            return Verdict.DISABLED;
        }
        if (home == null || partner == null || !st.exists(home) || !st.exists(partner)) {
            return Verdict.NOT_FOUND;
        }
        if (home.equals(partner)) {
            return Verdict.SAME_COUNTRY;
        }
        if (st.npc(home)) {
            return Verdict.NPC_HOME;
        }
        if (st.npc(partner) && !s.allowNpc()) {
            return Verdict.NPC_PARTNER;
        }
        if (st.unionPartnerOf(home) != null) {
            return Verdict.HOME_IN_UNION;
        }
        if (st.unionPartnerOf(partner) != null) {
            return Verdict.PARTNER_IN_UNION;
        }
        if (st.relation(home, partner) == Relation.WAR) {
            return Verdict.AT_WAR;
        }
        if (st.overlordOf(home) != null) {
            return Verdict.HOME_VASSAL;
        }
        if (st.overlordOf(partner) != null) {
            return Verdict.PARTNER_VASSAL;
        }
        final long ended = st.unionEndedAt(home, partner);
        if (ended > 0 && now < ended + s.reformCooldownMillis()) {
            return Verdict.COOLDOWN;
        }
        return Verdict.ALLOWED;
    }

    /** Millis until the same pair may unite again (0 = now). */
    public static long reformAvailableIn(final Settings s, final long endedAt, final long now) {
        return endedAt <= 0 ? 0 : Math.max(0, endedAt + s.reformCooldownMillis() - now);
    }

    /** Epoch millis from which the union formed at {@code since} may be broken by command. */
    public static long breakableAt(final Settings s, final long since) {
        return since + Math.max(0, s.minDurationMillis());
    }

    public static boolean canBreak(final Settings s, final long since, final long now) {
        return now >= breakableAt(s, since);
    }

    /** Union partners may never declare war on each other (they must break the union first). */
    public static boolean warForbidden(final State st, final UUID from, final UUID to) {
        return from != null && to != null && to.equals(st.unionPartnerOf(from));
    }

    /**
     * {@code attacker} declared war on {@code defender}: the union partner of the defender that joins as a defender,
     * or null. It does not join when it is the attacker itself, already at war with or allied to the attacker, or in
     * a vassal relation with the attacker (the vassal rules forbid that war).
     */
    public static UUID joiningDefender(final Settings s, final State st, final UUID attacker, final UUID defender) {
        return s.defendPartner() ? joining(st, st.unionPartnerOf(defender), attacker) : null;
    }

    /** The union partner of the attacker that joins the attack (only with {@code joinOffensiveWars}), or null. */
    public static UUID joiningAttacker(final Settings s, final State st, final UUID attacker, final UUID defender) {
        return s.joinOffensiveWars() ? joining(st, st.unionPartnerOf(attacker), defender) : null;
    }

    private static UUID joining(final State st, final UUID partner, final UUID enemy) {
        if (partner == null || partner.equals(enemy) || !st.exists(partner) || !st.exists(enemy)) {
            return null;
        }
        if (st.relation(partner, enemy) != Relation.NEUTRAL) {
            return null;
        }
        if (enemy.equals(st.overlordOf(partner)) || partner.equals(st.overlordOf(enemy))) {
            return null;
        }
        return partner;
    }

    /**
     * Who takes the partner's throne when the union ends: the regent if he is still a member, otherwise the member with
     * the highest rank (earliest joined first), never the monarch. Null when nobody is left.
     */
    public static Member successor(final Country partner, final UUID monarch, final UUID regent) {
        if (regent != null && !regent.equals(monarch)) {
            final Member r = partner.member(regent);
            if (r != null) {
                return r;
            }
        }
        return partner.members().stream()
            .filter(m -> !m.uuid().equals(monarch))
            .min(Comparator.comparingInt((Member m) -> -rankWeight(m.rank())).thenComparingLong(Member::joinedAt))
            .orElse(null);
    }

    private static int rankWeight(final Rank rank) {
        return rank == null ? 0 : rank.weight();
    }
}
