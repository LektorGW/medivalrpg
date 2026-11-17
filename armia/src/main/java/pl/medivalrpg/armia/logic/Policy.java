// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcStance;

/**
 * AI v2 diplomacy on top of {@link AiDecisions}: the admin's stance towards each country, the posture of the NPC
 * country and land conquered from it change who is attacked, who gets an alliance and when peace is made. Pure.
 *
 * <p>Rules: {@code NEVER_WAR} and vassal protection block war; {@code DEFENSIVE} never starts a war;
 * {@code PREFERRED_TARGET} multiplies the war weight, lowers the required advantage and the minimum agresja and
 * ignores the "we like them" block; {@code FRIENDLY}/{@code HOSTILE} shift the opinion; land lost to a neighbour
 * (after the reclaim cooldown) raises its weight and lowers the required advantage.</p>
 */
public final class Policy {

    private Policy() {
    }

    /** Opinion with the stance applied, −100..100. */
    public static int effectiveOpinion(final AiRules r, final int opinion, final NpcStance stance) {
        final int shifted = switch (stance == null ? NpcStance.AUTO : stance) {
            case FRIENDLY -> opinion + r.friendlyOpinion();
            case HOSTILE, PREFERRED_TARGET -> opinion - r.hostileOpinion();
            default -> opinion;
        };
        return Opinions.clamp(shifted);
    }

    /** May the AI start a war with this neighbour at all? */
    public static boolean warAllowed(final NeighborView n) {
        return !n.ally() && !n.atWar() && !n.protectedBy() && n.stance() != NpcStance.NEVER_WAR;
    }

    /** Lowest agresja that may attack this neighbour. */
    public static int minAggression(final AiTuning t, final NeighborView n, final boolean reclaimReady) {
        int min = t.minAggressionForWar();
        if (n.stance() == NpcStance.PREFERRED_TARGET) {
            min -= 20;
        } else if (n.stance() == NpcStance.HOSTILE) {
            min -= 10;
        }
        if (reclaimReady && n.lostChunks() > 0) {
            min -= 10;
        }
        return Math.max(0, min);
    }

    /** Strength ratio needed to attack this neighbour. Never below 1.0. */
    public static double requiredAdvantage(final AiTuning t, final AiRules r, final Personality p, final NpcPosture posture,
                                           final NeighborView n, final boolean reclaimReady) {
        double req = AiDecisions.requiredAdvantage(t, p);
        if (posture == NpcPosture.OFFENSIVE) {
            req *= r.offensiveAdvantage();
        }
        if (n.stance() == NpcStance.PREFERRED_TARGET) {
            req *= r.preferredAdvantage();
        } else if (n.stance() == NpcStance.HOSTILE) {
            req *= 0.9;
        }
        if (reclaimReady && n.lostChunks() > 0) {
            req *= 0.9;
        }
        return Math.max(1.0, req);
    }

    /** Attractiveness of a war target (higher = better). */
    public static double warWeight(final AiRules r, final double ourStrength, final NeighborView n, final boolean reclaimReady) {
        final double ratio = ourStrength / Math.max(0.5, n.strength());
        double w = ratio * (1.0 - effectiveOpinion(r, n.opinion(), n.stance()) / 200.0);
        if (n.stance() == NpcStance.PREFERRED_TARGET) {
            w *= r.preferredWeight();
        }
        if (reclaimReady) {
            w *= 1.0 + r.reclaimWeight() * Math.min(3, n.lostChunks());
        }
        return w;
    }

    /** Neighbours that qualify as a war target now (before the random roll). */
    public static List<NeighborView> warCandidates(final AiTuning t, final AiRules r, final Personality p, final NpcPosture posture,
                                                   final double ourStrength, final List<NeighborView> neighbors, final boolean reclaimReady) {
        if (posture == NpcPosture.DEFENSIVE || ourStrength <= 0) {
            return List.of();
        }
        return neighbors.stream()
            .filter(Policy::warAllowed)
            .filter(n -> p.aggression() >= minAggression(t, n, reclaimReady))
            .filter(n -> n.stance() == NpcStance.PREFERRED_TARGET || effectiveOpinion(r, n.opinion(), n.stance()) < t.warOpinionBlock())
            .filter(n -> ourStrength / Math.max(0.5, n.strength()) >= requiredAdvantage(t, r, p, posture, n, reclaimReady))
            .toList();
    }

    /** Best war target ignoring the random roll (shown as "autoTarget" while at peace). */
    public static Optional<NeighborView> bestWarTarget(final AiTuning t, final AiRules r, final Personality p, final NpcPosture posture,
                                                       final double ourStrength, final List<NeighborView> neighbors, final boolean reclaimReady) {
        return warCandidates(t, r, p, posture, ourStrength, neighbors, reclaimReady).stream()
            .max(Comparator.comparingDouble((NeighborView n) -> warWeight(r, ourStrength, n, reclaimReady)));
    }

    /**
     * Neighbour to attack this tick, if any. Posture, stance and lost land as described in the class comment; the
     * war happens with a chance growing with agresja (higher when OFFENSIVE or against a PREFERRED_TARGET).
     *
     * @param reclaimReady the reclaim cooldown after the last peace is over (lost land counts as a reason)
     */
    public static Optional<NeighborView> chooseWarTarget(final AiTuning t, final AiRules r, final Personality p, final NpcPosture posture,
                                                         final double ourStrength, final List<NeighborView> neighbors,
                                                         final int activeWars, final boolean cooldownOver, final boolean reclaimReady,
                                                         final double roll) {
        if (!cooldownOver || activeWars >= t.maxConcurrentWars()) {
            return Optional.empty();
        }
        final Optional<NeighborView> best = bestWarTarget(t, r, p, posture, ourStrength, neighbors, reclaimReady);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        final int min = minAggression(t, best.get(), reclaimReady);
        final double span = Math.max(1, 100 - min);
        double chance = t.warChance() * Math.min(1.0, (p.aggression() - min + 1) / span);
        if (posture == NpcPosture.OFFENSIVE) {
            chance *= r.offensiveWarChance();
        }
        if (best.get().stance() == NpcStance.PREFERRED_TARGET) {
            chance *= 1.5;
        }
        return roll < Math.min(1.0, chance) ? best : Optional.empty();
    }

    // ------------------------------------------------------------------ alliances

    /** Alliance score with the stance: FRIENDLY shifts the opinion and adds a bonus. */
    public static double allianceScore(final AiRules r, final Personality p, final AllianceView a, final NpcStance stance) {
        final AllianceView shifted = new AllianceView(a.ourStrength(), a.theirStrength(), effectiveOpinion(r, a.opinion(), stance),
            a.atWar(), a.sharedEnemy(), a.ourAllies());
        return AiDecisions.allianceScore(p, shifted) + (stance == NpcStance.FRIENDLY ? 0.2 : 0.0);
    }

    /** Accept an alliance? Never with a HOSTILE / PREFERRED_TARGET country; FRIENDLY lowers the minimum dyplomacja. */
    public static boolean acceptAlliance(final AiTuning t, final AiRules r, final Personality p, final AllianceView a, final NpcStance stance) {
        if (a.atWar() || stance == NpcStance.HOSTILE || stance == NpcStance.PREFERRED_TARGET) {
            return false;
        }
        final int minDiplomacy = t.minDiplomacyForAlliance() - (stance == NpcStance.FRIENDLY ? 15 : 0);
        if (p.diplomacy() < minDiplomacy) {
            return false;
        }
        return allianceScore(r, p, a, stance) >= t.allianceThreshold();
    }

    /** Neighbour to propose an alliance to this tick: FRIENDLY ones first (and also with a low dyplomacja). */
    public static Optional<NeighborView> chooseAllianceTarget(final AiTuning t, final AiRules r, final Personality p,
                                                              final List<NeighborView> neighbors, final double roll) {
        final boolean anyFriendly = neighbors.stream().anyMatch(n -> n.stance() == NpcStance.FRIENDLY && !n.ally() && !n.atWar());
        final int needed = t.allianceProposeDiplomacy() - (anyFriendly ? 15 : 0);
        if (p.diplomacy() < needed) {
            return Optional.empty();
        }
        final double chance = t.allianceProposeChance() * Math.max(p.diplomacy(), anyFriendly ? 60 : 0) / 100.0;
        if (roll >= chance) {
            return Optional.empty();
        }
        return neighbors.stream()
            .filter(n -> !n.ally() && !n.atWar())
            .filter(n -> n.stance() != NpcStance.HOSTILE && n.stance() != NpcStance.PREFERRED_TARGET)
            .filter(n -> n.stance() == NpcStance.FRIENDLY || effectiveOpinion(r, n.opinion(), n.stance()) >= 10 || n.sharedEnemy())
            .max(Comparator.comparingInt((NeighborView n) -> effectiveOpinion(r, n.opinion(), n.stance())
                + (n.sharedEnemy() ? 50 : 0) + (n.stance() == NpcStance.FRIENDLY ? 100 : 0)));
    }

    // ------------------------------------------------------------------ peace

    public static double peaceScore(final AiTuning t, final Personality p, final WarView w, final NpcStance stance) {
        final double bonus = switch (stance == null ? NpcStance.AUTO : stance) {
            case NEVER_WAR -> 0.5;
            case FRIENDLY -> 0.2;
            case HOSTILE -> -0.15;
            case PREFERRED_TARGET -> -0.3;
            default -> 0.0;
        };
        return AiDecisions.peaceScore(t, p, w) + bonus;
    }

    /** Accept peace? Never before the minimum war time; with NEVER_WAR always after it. */
    public static boolean acceptPeace(final AiTuning t, final Personality p, final WarView w, final NpcStance stance) {
        if (w.hours() < t.minWarHours()) {
            return false;
        }
        return stance == NpcStance.NEVER_WAR || peaceScore(t, p, w, stance) >= t.peaceThreshold();
    }

    /**
     * Offer peace now? NEVER_WAR always (after the minimum war time), FRIENDLY earlier, PREFERRED_TARGET only when
     * losing; otherwise like {@link AiDecisions#shouldOfferPeace}.
     */
    public static boolean shouldOfferPeace(final AiTuning t, final Personality p, final WarView w, final NpcStance stance) {
        if (w.hours() < t.minWarHours()) {
            return false;
        }
        return switch (stance == null ? NpcStance.AUTO : stance) {
            case NEVER_WAR -> true;
            case FRIENDLY -> AiDecisions.shouldOfferPeace(t, p, w) || AiDecisions.fatigue(t, p, w) >= 0.3;
            case PREFERRED_TARGET -> w.ratio() < t.losingRatio();
            default -> AiDecisions.shouldOfferPeace(t, p, w);
        };
    }

    // ------------------------------------------------------------------ allies in need

    /**
     * Score of an NPC ally for joining a war it was asked to join (≥ {@link AiRules#allyJoinThreshold()} = join).
     *
     * @param opinionOfAsker  ally's opinion of the country asking for help
     * @param opinionOfEnemy  ally's opinion of the enemy
     * @param stanceToEnemy   ally's stance towards the enemy
     * @param ourStrength     ally's strength
     * @param enemyStrength   enemy's strength
     */
    public static double joinWarScore(final Personality p, final NpcPosture posture, final int opinionOfAsker, final int opinionOfEnemy,
                                      final NpcStance stanceToEnemy, final double ourStrength, final double enemyStrength) {
        if (stanceToEnemy == NpcStance.NEVER_WAR) {
            return Double.NEGATIVE_INFINITY;
        }
        double s = p.diplomacy() / 100.0 * 0.4 + opinionOfAsker / 100.0 * 0.4 - opinionOfEnemy / 200.0 + p.aggression() / 100.0 * 0.2;
        s += ourStrength >= enemyStrength ? 0.2 : -0.2;
        s += switch (stanceToEnemy == null ? NpcStance.AUTO : stanceToEnemy) {
            case PREFERRED_TARGET -> 0.3;
            case HOSTILE -> 0.15;
            case FRIENDLY -> -0.3;
            default -> 0.0;
        };
        if (posture == NpcPosture.DEFENSIVE) {
            s -= 0.2;
        } else if (posture == NpcPosture.OFFENSIVE) {
            s += 0.1;
        }
        return s;
    }

    public static boolean joinWar(final AiRules r, final Personality p, final NpcPosture posture, final int opinionOfAsker,
                                  final int opinionOfEnemy, final NpcStance stanceToEnemy, final boolean protectedEnemy,
                                  final double ourStrength, final double enemyStrength) {
        return !protectedEnemy && joinWarScore(p, posture, opinionOfAsker, opinionOfEnemy, stanceToEnemy, ourStrength, enemyStrength) >= r.allyJoinThreshold();
    }

    // ------------------------------------------------------------------ tribute demands

    /**
     * Does an NPC country agree to pay {@code amount} per day for {@code days} days to a country demanding it?
     * Yes when the demander is clearly stronger (agresja raises the needed ratio) and the tribute is affordable, or
     * as goodwill when the opinion is high and the sum is small. A HOSTILE / PREFERRED_TARGET demander needs a far
     * bigger advantage.
     *
     * @param ourDailyIncome expected income per day (taxes + AI income)
     */
    public static boolean acceptTributeDemand(final AiRules r, final Personality p, final double demanderStrength, final double ourStrength,
                                              final int opinionOfDemander, final NpcStance stanceToDemander, final long amount,
                                              final long ourDailyIncome, final long balance, final int days) {
        if (amount <= 0) {
            return false;
        }
        final long total = amount * Math.max(1, days);
        if (total > balance + Math.max(0, ourDailyIncome) * Math.max(1, days)) {
            return false; // cannot pay anyway
        }
        final double ratio = demanderStrength / Math.max(0.5, ourStrength);
        double needed = r.tributeAcceptRatio() * (0.5 + p.aggression() / 100.0);
        if (stanceToDemander == NpcStance.HOSTILE || stanceToDemander == NpcStance.PREFERRED_TARGET) {
            needed *= 1.5;
        } else if (stanceToDemander == NpcStance.FRIENDLY || stanceToDemander == NpcStance.NEVER_WAR) {
            needed *= 0.8;
        }
        final boolean heavy = ourDailyIncome > 0 && amount > ourDailyIncome * 0.8;
        if (ratio >= needed) {
            return !heavy || p.aggression() < 60 || ratio >= needed * 1.5;
        }
        final boolean goodwill = opinionOfDemander >= 60 && p.diplomacy() >= 60 && amount <= Math.max(1, ourDailyIncome) * 0.3;
        return goodwill && stanceToDemander != NpcStance.HOSTILE && stanceToDemander != NpcStance.PREFERRED_TARGET;
    }

    // ------------------------------------------------------------------ sieges

    /**
     * One enemy as seen for sieges.
     *
     * @param id         enemy id
     * @param ratio      our strength / theirs
     * @param lostChunks our chunks they hold
     * @param stance     our stance towards them
     */
    public record EnemyView(UUID id, double ratio, int lostChunks, NpcStance stance) {
    }

    /**
     * Order in which enemies are besieged: the admin's siege target (when at war with it), then whoever holds our
     * lost land (most chunks first), then PREFERRED_TARGET, then the weakest relative to us.
     */
    public static List<UUID> siegeOrder(final UUID manualTarget, final List<EnemyView> enemies) {
        return enemies.stream()
            .sorted(Comparator
                .comparing((EnemyView e) -> !e.id().equals(manualTarget))
                .thenComparing((EnemyView e) -> -Math.min(99, e.lostChunks()))
                .thenComparing((EnemyView e) -> e.stance() != NpcStance.PREFERRED_TARGET)
                .thenComparingDouble((EnemyView e) -> -e.ratio()))
            .map(EnemyView::id)
            .toList();
    }
}
