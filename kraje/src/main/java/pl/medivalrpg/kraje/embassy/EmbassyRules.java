// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.embassy;

import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.kraje.api.RelationQuality;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Rules of player embassies (pure, no Bukkit): where and when one may be opened, NPC answers, closing on war, the
 * relation quality and the "good relations + embassy" check for joint projects. Used by {@link EmbassyService}, the API
 * and the tests.
 */
public final class EmbassyRules {

    /**
     * Config switches ({@code embassy.*}).
     *
     * @param enabled                  embassies on/off
     * @param allowedRelations         relations in which an embassy may be opened (never WAR)
     * @param maxPerCountry            embassies of one country abroad (0 = no limit)
     * @param maxHosted                embassies one country hosts (0 = no limit)
     * @param allowCapital             whether the host's capital chunk may become an embassy
     * @param expelNoticeMillis        notice period after {@code /kraj ambasada wydal}
     * @param npcAcceptRelations       an NPC host accepts when the relation is one of these
     * @param tradeBonus               trade bonus hook value when an embassy exists between the two
     * @param unionTradeBonus          trade bonus hook value for union partners
     * @param jointMinQuality          minimum relation quality for joint projects (with an embassy)
     * @param jointNeedsOwnEmbassy     true = the builder must own an embassy in the target; false = either direction
     */
    public record Settings(boolean enabled, Set<Relation> allowedRelations, int maxPerCountry, int maxHosted,
                           boolean allowCapital, long expelNoticeMillis, Set<Relation> npcAcceptRelations,
                           double tradeBonus, double unionTradeBonus, RelationQuality jointMinQuality,
                           boolean jointNeedsOwnEmbassy) {

        public Settings {
            allowedRelations = Set.copyOf(allowedRelations);
            npcAcceptRelations = Set.copyOf(npcAcceptRelations);
        }
    }

    /** Read access to the current state. */
    public interface State {
        /** Owner of the chunk (null = wilderness). */
        UUID ownerAt(ChunkPos pos);

        Relation relation(UUID a, UUID b);

        boolean hasEmbassy(UUID guest, UUID host);

        int embassiesOf(UUID guest);

        int embassiesIn(UUID host);

        /** Whether an embassy (of anyone) stands on the chunk. */
        boolean embassyAt(ChunkPos pos);

        boolean isCapital(UUID country, ChunkPos pos);

        boolean sieged(ChunkPos pos);

        boolean protectedArea(ChunkPos pos);
    }

    public enum Verdict {
        ALLOWED,
        DISABLED,
        /** The chunk is not land of the named host. */
        NOT_HOST_LAND,
        SAME_COUNTRY,
        BAD_RELATION,
        ALREADY_OPEN,
        LIMIT_GUEST,
        LIMIT_HOST,
        CAPITAL,
        CHUNK_TAKEN,
        SIEGED,
        PROTECTED;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    private EmbassyRules() {
    }

    /** May {@code guest} open an embassy of its own on chunk {@code pos}, which must belong to {@code host}? */
    public static Verdict open(final Settings s, final State st, final UUID guest, final UUID host, final ChunkPos pos) {
        if (!s.enabled()) {
            return Verdict.DISABLED;
        }
        final UUID owner = st.ownerAt(pos);
        if (owner == null || host == null || !owner.equals(host)) {
            return owner != null && owner.equals(guest) ? Verdict.SAME_COUNTRY : Verdict.NOT_HOST_LAND;
        }
        if (host.equals(guest)) {
            return Verdict.SAME_COUNTRY;
        }
        final Relation rel = st.relation(guest, host);
        if (rel == Relation.WAR || !s.allowedRelations().contains(rel)) {
            return Verdict.BAD_RELATION;
        }
        if (st.hasEmbassy(guest, host)) {
            return Verdict.ALREADY_OPEN;
        }
        if (s.maxPerCountry() > 0 && st.embassiesOf(guest) >= s.maxPerCountry()) {
            return Verdict.LIMIT_GUEST;
        }
        if (s.maxHosted() > 0 && st.embassiesIn(host) >= s.maxHosted()) {
            return Verdict.LIMIT_HOST;
        }
        if (!s.allowCapital() && st.isCapital(host, pos)) {
            return Verdict.CAPITAL;
        }
        if (st.embassyAt(pos)) {
            return Verdict.CHUNK_TAKEN;
        }
        if (st.sieged(pos)) {
            return Verdict.SIEGED;
        }
        if (st.protectedArea(pos)) {
            return Verdict.PROTECTED;
        }
        return Verdict.ALLOWED;
    }

    /** An existing embassy closes when its two countries get this relation. */
    public static boolean closesOn(final Relation relation) {
        return relation == Relation.WAR;
    }

    /** Closing time of an embassy expelled at {@code now} (immediately with no notice period). */
    public static long expelDeadline(final Settings s, final long now) {
        return now + Math.max(0, s.expelNoticeMillis());
    }

    /** Whether an NPC host accepts a request in the current relation. */
    public static boolean npcAccepts(final Settings s, final Relation relation) {
        return relation != Relation.WAR && s.npcAcceptRelations().contains(relation);
    }

    /**
     * Relation quality: same country, union, war, alliance, vassalage, neutral — the best that applies (war always wins
     * over vassalage; alliance over vassalage).
     */
    public static RelationQuality quality(final boolean same, final boolean union, final Relation relation, final boolean vassalage) {
        if (same) {
            return RelationQuality.SAME;
        }
        if (union) {
            return RelationQuality.UNION;
        }
        if (relation == Relation.WAR) {
            return RelationQuality.WAR;
        }
        if (relation == Relation.ALLIANCE) {
            return RelationQuality.ALLIANCE;
        }
        return vassalage ? RelationQuality.VASSALAGE : RelationQuality.NEUTRAL;
    }

    /**
     * Joint project of {@code from} reaching into {@code into} (e.g. a road): same country / union always; otherwise the
     * relation quality must reach {@link Settings#jointMinQuality} and an embassy must exist ({@code ownEmbassy} = the
     * builder has one in the target, {@code otherEmbassy} = the target has one at the builder).
     */
    public static boolean jointProject(final Settings s, final RelationQuality quality, final boolean ownEmbassy,
                                       final boolean otherEmbassy) {
        if (quality.atLeast(RelationQuality.UNION)) {
            return true;
        }
        if (!quality.peaceful() || !quality.atLeast(s.jointMinQuality())) {
            return false;
        }
        return ownEmbassy || (!s.jointNeedsOwnEmbassy() && otherEmbassy);
    }

    /** Trade bonus hook: union partners get the union bonus, an embassy (either direction) the embassy bonus; never at war. */
    public static double tradeBonus(final Settings s, final RelationQuality quality, final boolean anyEmbassy) {
        if (!quality.peaceful() || quality == RelationQuality.SAME) {
            return 0.0;
        }
        double bonus = anyEmbassy ? s.tradeBonus() : 0.0;
        if (quality == RelationQuality.UNION) {
            bonus = Math.max(bonus, s.unionTradeBonus());
        }
        return Math.max(0.0, bonus);
    }
}
