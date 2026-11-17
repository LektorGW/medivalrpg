// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import pl.medivalrpg.armia.api.NpcStance;

/**
 * Capitulation and vassalage decisions of NPC countries (Kraje: {@code offerCapitulation}, {@code respondCapitulation},
 * {@code buyout}, {@code releaseVassal}). Pure.
 */
public final class Capitulation {

    private Capitulation() {
    }

    /**
     * An offer received by the NPC country (the winner).
     *
     * @param ourStrength       our strength
     * @param theirStrength     strength of the country offering to capitulate
     * @param opinion           our opinion of it
     * @param estimatedTribute  daily tribute expected from its taxes
     * @param tributeMin        minimum daily tribute of Kraje
     * @param warHours          war duration
     * @param losses            soldiers we lost in this war
     * @param stance            our stance towards it
     */
    public record OfferView(double ourStrength, double theirStrength, int opinion, long estimatedTribute, long tributeMin,
                            double warHours, int losses, NpcStance stance) {

        public double ratio() {
            return this.ourStrength / Math.max(0.5, this.theirStrength);
        }
    }

    /** A greedy ruler: low dyplomacja and high agresja. */
    public static boolean greedy(final Personality p) {
        return p.diplomacy() < 40 && p.aggression() > 60;
    }

    /** Tribute a greedy ruler wants at least: per strength point of the loser and day. */
    public static long fairTribute(final AiRules r, final OfferView v) {
        return Math.round(Math.max(0, v.theirStrength()) * r.greedyTributePerStrength());
    }

    /** A greedy ruler refuses an offer whose tribute is low compared to the loser's strength. */
    public static boolean greedyRefuses(final AiRules r, final Personality p, final OfferView v) {
        return greedy(p) && Math.max(v.estimatedTribute(), v.tributeMin()) < fairTribute(r, v);
    }

    /**
     * Score of accepting: diplomacy and war fatigue for, agresja against; a big advantage of an aggressive ruler
     * (wants to conquer instead) against; being weaker for; opinion and stance shift it.
     */
    public static double acceptScore(final AiTuning t, final Personality p, final OfferView v) {
        final double fatigue = AiDecisions.fatigue(t, p, new WarView(v.ourStrength(), v.theirStrength(), v.warHours(), v.losses(), v.opinion()));
        double s = 0.45 + p.diplomacy() / 100.0 * 0.3 - p.aggression() / 100.0 * 0.25 + v.opinion() / 200.0 + fatigue * 0.25;
        if (v.ratio() >= 2.0 && p.aggression() >= 70) {
            s -= 0.25;
        }
        if (v.ratio() < 1.0) {
            s += 0.25;
        }
        s += switch (v.stance() == null ? NpcStance.AUTO : v.stance()) {
            case NEVER_WAR -> 0.3;
            case FRIENDLY -> 0.15;
            case HOSTILE -> -0.1;
            case PREFERRED_TARGET -> -0.3;
            default -> 0.0;
        };
        return s;
    }

    public static boolean accept(final AiTuning t, final AiRules r, final Personality p, final OfferView v) {
        return !greedyRefuses(r, p, v) && acceptScore(t, p, v) >= r.capitulationThreshold();
    }

    /**
     * The NPC country losing a war.
     *
     * @param ourStrength        our strength
     * @param theirStrength      the enemy's strength
     * @param warHours           war duration
     * @param chunksLostInWar    chunks the enemy conquered from us in this war
     * @param chunksNow          our chunks now
     * @param treasuryAtWarStart treasury when the war started (0 = unknown)
     * @param treasuryNow        treasury now
     * @param alreadyVassal      we are a vassal already (Kraje forbids a second overlord)
     * @param cooldownOver       no offer to this enemy recently
     */
    public record LosingView(double ourStrength, double theirStrength, double warHours, int chunksLostInWar, int chunksNow,
                             long treasuryAtWarStart, long treasuryNow, boolean alreadyVassal, boolean cooldownOver) {

        public double ratio() {
            return this.ourStrength / Math.max(0.5, this.theirStrength);
        }

        /** 0..1 how much of the treasury was lost since the war started. */
        public double treasuryDrop() {
            if (this.treasuryAtWarStart <= 0) {
                return 0;
            }
            return Math.max(0, Math.min(1, (this.treasuryAtWarStart - this.treasuryNow) / (double) this.treasuryAtWarStart));
        }
    }

    /**
     * Offer capitulation instead of falling? Only after the minimum war time, when clearly weaker and losing land or
     * money (or close to the fall). A proud ruler (agresja ≥ 85) gives up only when about to fall.
     */
    public static boolean shouldCapitulate(final AiTuning t, final AiRules r, final Personality p, final LosingView v) {
        if (v.alreadyVassal() || !v.cooldownOver() || v.warHours() < t.minWarHours()) {
            return false;
        }
        if (v.ratio() >= r.capitulateRatio()) {
            return false;
        }
        final boolean nearFall = v.chunksNow() <= 2;
        if (p.aggression() >= 85) {
            return nearFall;
        }
        return nearFall || v.chunksLostInWar() >= r.capitulateLostChunks() || v.treasuryDrop() >= r.capitulateTreasuryDrop();
    }

    /** A vassal buys its freedom when the treasury keeps {@code buyoutReserveFactor} × reserve afterwards. */
    public static boolean shouldBuyout(final AiRules r, final long cost, final long balance, final long reserve) {
        return cost > 0 && balance - cost >= Math.round(Math.max(0, reserve) * r.buyoutReserveFactor());
    }

    /** Opinion from which an overlord releases its vassal: {@code releaseOpinion}, ±10 by agresja (100 → +10, 0 → −10). */
    public static int releaseThreshold(final AiRules r, final Personality p) {
        return Math.min(100, r.releaseOpinion() + (p.aggression() - 50) / 5);
    }

    public static boolean shouldRelease(final AiRules r, final Personality p, final int opinionOfVassal) {
        return opinionOfVassal >= releaseThreshold(r, p);
    }
}
