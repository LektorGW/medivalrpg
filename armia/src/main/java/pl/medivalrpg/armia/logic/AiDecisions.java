// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decisions of the NPC-country AI as pure functions of the personality, a snapshot of the situation and a random
 * roll in [0, 1) passed by the caller (so tests are deterministic). No Bukkit, no side effects.
 */
public final class AiDecisions {

    private AiDecisions() {
    }

    // ------------------------------------------------------------------ economy

    /** Virtual income for {@code dayFraction} of a day (e.g. 5 min = 5/1440). bogactwo 0 → ×0.5, 50 → ×1, 100 → ×1.5. */
    public static long income(final AiTuning t, final Personality p, final int chunks, final double dayFraction) {
        final double perDay = (t.baseIncomePerDay() + t.incomePerChunkPerDay() * (double) Math.max(0, chunks)) * (0.5 + p.wealth() / 100.0);
        return Math.max(0, Math.round(perDay * Math.max(0, dayFraction)));
    }

    /** Money kept untouched: some days of wages (more for "rich" personalities) and never less than {@code minReserve}. */
    public static long reserve(final AiTuning t, final Personality p, final long dailyWages) {
        final double days = t.reserveDaysOfWages() * (0.5 + p.wealth() / 100.0);
        return Math.max(t.minReserve(), Math.round(Math.max(0, dailyWages) * days));
    }

    /**
     * Claim one more wilderness chunk this tick? Never above max-chunki, never into the reserve; chance falls off as the
     * country approaches its cap (half of the base chance at the cap).
     */
    public static boolean shouldExpand(final AiTuning t, final Personality p, final int chunks, final long balance,
                                       final long claimCost, final long dailyWages, final double roll) {
        if (p.expansion() <= 0 || chunks >= p.maxChunks()) {
            return false;
        }
        if (balance - Math.max(0, claimCost) < reserve(t, p, dailyWages)) {
            return false;
        }
        final double fill = Math.min(1.0, chunks / (double) p.maxChunks());
        final double chance = t.expansionChance() * (p.expansion() / 100.0) * (1.0 - 0.5 * fill);
        return roll < chance;
    }

    // ------------------------------------------------------------------ army

    /** Army size the AI aims for: grows with land and agresja, capped by max-zolnierze and the country limit. */
    public static int desiredSoldiers(final AiTuning t, final Personality p, final int chunks, final int countryLimit) {
        final double perChunk = t.soldiersPerChunk() * (0.5 + p.aggression() / 100.0);
        final int wanted = (int) Math.ceil(Math.max(1, chunks) * perChunk);
        return Math.max(0, Math.min(wanted, Math.min(p.maxSoldiers(), countryLimit)));
    }

    /** How many soldiers to recruit now (0..maxRecruitPerTick), never spending into the reserve. */
    public static int recruitBatch(final AiTuning t, final Personality p, final int desired, final int current,
                                   final long balance, final long unitCost, final long dailyWages) {
        final int missing = desired - current;
        if (missing <= 0) {
            return 0;
        }
        final long spendable = balance - reserve(t, p, dailyWages);
        if (spendable <= 0) {
            return 0;
        }
        final long affordable = unitCost <= 0 ? Integer.MAX_VALUE : spendable / unitCost;
        return (int) Math.max(0, Math.min(Math.min(missing, t.maxRecruitPerTick()), affordable));
    }

    // ------------------------------------------------------------------ war

    /** Strength ratio needed to start a war: 1.6 at agresja 50, 1.2 at agresja 100 (defaults). Never below 1.0. */
    public static double requiredAdvantage(final AiTuning t, final Personality p) {
        return Math.max(1.0, t.warAdvantage() - (p.aggression() - 50) / 100.0 * t.warAdvantageAggression());
    }

    /**
     * Neighbour to attack this tick, if any. Only neutral (not allied, not already fighting) neighbours that we beat
     * by the required ratio and do not like too much; the weakest relative target wins. The war happens with a
     * probability growing with agresja.
     */
    public static Optional<NeighborView> chooseWarTarget(final AiTuning t, final Personality p, final double ourStrength,
                                                         final List<NeighborView> neighbors, final int activeWars,
                                                         final boolean cooldownOver, final double roll) {
        if (p.aggression() < t.minAggressionForWar() || !cooldownOver || activeWars >= t.maxConcurrentWars() || ourStrength <= 0) {
            return Optional.empty();
        }
        final double required = requiredAdvantage(t, p);
        final Optional<NeighborView> best = neighbors.stream()
            .filter(n -> !n.ally() && !n.atWar())
            .filter(n -> n.opinion() < t.warOpinionBlock())
            .filter(n -> ourStrength / Math.max(0.5, n.strength()) >= required)
            .max(Comparator.comparingDouble((NeighborView n) -> ourStrength / Math.max(0.5, n.strength()) * (1.0 - n.opinion() / 200.0)));
        if (best.isEmpty()) {
            return Optional.empty();
        }
        final double span = Math.max(1, 100 - t.minAggressionForWar());
        final double chance = t.warChance() * Math.min(1.0, (p.aggression() - t.minAggressionForWar() + 1) / span);
        return roll < chance ? best : Optional.empty();
    }

    /** 0..1: how tired of the war we are (half from its length, half from losses). */
    public static double fatigue(final AiTuning t, final Personality p, final WarView w) {
        final double hoursFull = t.fatigueHours() * (1.0 + p.aggression() / 100.0);
        final double tolerance = t.lossTolerance() + p.aggression() / 10.0;
        return 0.5 * Math.min(1.0, w.hours() / Math.max(0.01, hoursFull)) + 0.5 * Math.min(1.0, w.losses() / Math.max(1.0, tolerance));
    }

    /** Should we offer peace to this enemy now? Only after the minimum war time; when losing, exhausted or peaceful. */
    public static boolean shouldOfferPeace(final AiTuning t, final Personality p, final WarView w) {
        if (w.hours() < t.minWarHours()) {
            return false;
        }
        final double fatigue = fatigue(t, p, w);
        return w.ratio() < t.losingRatio()
            || fatigue >= 0.75
            || (p.diplomacy() >= 70 && fatigue >= 0.4);
    }

    /**
     * Accept a peace proposal? Score = diplomacy + fatigue + "we are weaker" − "we are winning and aggressive"
     * + opinion (tribute). Never before the minimum war time.
     */
    public static boolean acceptPeace(final AiTuning t, final Personality p, final WarView w) {
        if (w.hours() < t.minWarHours()) {
            return false;
        }
        return peaceScore(t, p, w) >= t.peaceThreshold();
    }

    public static double peaceScore(final AiTuning t, final Personality p, final WarView w) {
        final double ratio = w.ratio();
        double score = p.diplomacy() / 100.0 * 0.5 + fatigue(t, p, w) * 0.6 + w.opinion() / 400.0;
        if (ratio < 1.0) {
            score += 0.3;
        }
        if (ratio >= requiredAdvantage(t, p)) {
            score -= 0.3 * p.aggression() / 100.0;
        }
        return score;
    }

    // ------------------------------------------------------------------ alliances

    /** Accept an alliance proposal? Never with an enemy or below the minimum dyplomacja. */
    public static boolean acceptAlliance(final AiTuning t, final Personality p, final AllianceView a) {
        if (a.atWar() || p.diplomacy() < t.minDiplomacyForAlliance()) {
            return false;
        }
        return allianceScore(p, a) >= t.allianceThreshold();
    }

    public static double allianceScore(final Personality p, final AllianceView a) {
        double score = p.diplomacy() / 100.0 * 0.6 + a.opinion() / 100.0 * 0.4 - p.aggression() / 100.0 * 0.2 - a.ourAllies() * 0.1;
        if (a.sharedEnemy()) {
            score += 0.25;
        }
        if (a.theirStrength() >= a.ourStrength() * 0.5) {
            score += 0.1;
        }
        return score;
    }

    /** Neighbour to propose an alliance to this tick (diplomatic personalities only). */
    public static Optional<NeighborView> chooseAllianceTarget(final AiTuning t, final Personality p,
                                                              final List<NeighborView> neighbors, final double roll) {
        if (p.diplomacy() < t.allianceProposeDiplomacy()) {
            return Optional.empty();
        }
        if (roll >= t.allianceProposeChance() * p.diplomacy() / 100.0) {
            return Optional.empty();
        }
        return neighbors.stream()
            .filter(n -> !n.ally() && !n.atWar())
            .filter(n -> n.opinion() >= 10 || n.sharedEnemy())
            .max(Comparator.comparingInt((NeighborView n) -> n.opinion() + (n.sharedEnemy() ? 50 : 0)));
    }

    // ------------------------------------------------------------------ strength

    /** Military strength: soldiers (already weighted by their unit power) + online/offline members. */
    public static double strength(final AiTuning t, final double soldierPower, final int onlineMembers, final int offlineMembers) {
        return Math.max(0, soldierPower) + t.playerWeight() * Math.max(0, onlineMembers) + t.offlinePlayerWeight() * Math.max(0, offlineMembers);
    }

    /** Opinion after a tribute: +1 point per {@code copperPerPoint}, clamped to −100..100. */
    public static int opinionAfterTribute(final int opinion, final long amount, final long copperPerPoint) {
        final long gain = copperPerPoint <= 0 ? 0 : amount / copperPerPoint;
        return (int) Math.max(-100, Math.min(100, opinion + Math.min(200, gain)));
    }
}
