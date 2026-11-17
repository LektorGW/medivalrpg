// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.permit;

/**
 * Pure rules deciding whether a company may build track/road on land of a foreign country ("budowa drogi do innego
 * królestwa przy dobrych stosunkach").
 *
 * <p><b>Embassy hook</b>: another module is adding embassies to Kraje. When {@code requireEmbassy} is enabled in the
 * config, {@link Input#embassy()} must be true; the plugin fills it from {@code EmbassyCheck} (see README: once
 * {@code KrajeApi} gets e.g. {@code default boolean hasEmbassy(UUID host, UUID guest)}, wire it in
 * {@code pl.medivalrpg.kolej.permit.EmbassyCheck}). Until then the check reports "no embassy information" and the
 * requirement is skipped only when {@code requireEmbassy} is false.</p>
 */
public final class PermitRules {

    /** The private carrier's consent survives changes in its manager's citizenship and diplomacy. */
    public static boolean privateCarrierAllowed(final java.util.UUID country, final java.util.Set<java.util.UUID> permits) {
        return country == null || permits.contains(country);
    }

    /** Stored decision of a country about a company. */
    public enum Status { NONE, PENDING, GRANTED, DENIED }

    /** Relation between the company's home country and the target country. */
    public enum Relation { SAME, ALLIANCE, NEUTRAL, WAR, NO_HOME }

    /** Outcome. */
    public enum Decision {
        /** Own land or permission granted: build. */
        ALLOWED,
        /** Ask the country (player ruler gets a clickable prompt; NPC AI answers immediately). */
        ASK,
        /** Not allowed (war, refused, no embassy...). */
        FORBIDDEN
    }

    /**
     * @param relation       relation between the company home country and the target
     * @param status         stored permit status of the target country for this company
     * @param targetNpc      target is an NPC country
     * @param npcOpinion     opinion of the NPC country about the company home country (-100..100; 0 if unknown)
     * @param embassy        an embassy exists (when known)
     * @param requireEmbassy config: embassy needed before asking
     * @param requireAlliance config: only allies may build (neutral is not enough)
     * @param npcMinOpinion  config: NPC accepts when opinion ≥ this
     */
    public record Input(Relation relation, Status status, boolean targetNpc, int npcOpinion, boolean embassy,
                        boolean requireEmbassy, boolean requireAlliance, int npcMinOpinion) {
    }

    private PermitRules() {
    }

    public static Decision decide(final Input in) {
        if (in.relation() == Relation.SAME) {
            return Decision.ALLOWED;
        }
        if (in.relation() == Relation.WAR) {
            return Decision.FORBIDDEN;
        }
        if (in.status() == Status.DENIED) {
            return Decision.FORBIDDEN;
        }
        if (in.requireAlliance() && in.relation() != Relation.ALLIANCE) {
            return Decision.FORBIDDEN;
        }
        if (in.status() == Status.GRANTED) {
            return in.requireEmbassy() && !in.embassy() ? Decision.FORBIDDEN : Decision.ALLOWED;
        }
        if (in.requireEmbassy() && !in.embassy()) {
            return Decision.FORBIDDEN;
        }
        return Decision.ASK;
    }

    /** Answer of an NPC country to a request (its AI accepts if the opinion is good enough and it is not at war). */
    public static boolean npcAccepts(final Input in) {
        if (in.relation() == Relation.WAR) {
            return false;
        }
        final int bonus = in.relation() == Relation.ALLIANCE ? 30 : 0;
        return in.npcOpinion() + bonus >= in.npcMinOpinion();
    }
}
