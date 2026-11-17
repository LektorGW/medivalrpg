// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * Knobs of AI v2 (config {@code ai.v2}): stance, posture, budget, defence and rebuilding, capitulation and tributes.
 * Pure value; money in copper.
 *
 * @param friendlyOpinion            opinion bonus of a FRIENDLY stance (wars rarer, alliances easier)
 * @param hostileOpinion             opinion malus of a HOSTILE stance (positive number, subtracted)
 * @param preferredWeight            war/siege weight multiplier of a PREFERRED_TARGET
 * @param preferredAdvantage         multiplier of the required strength ratio against a PREFERRED_TARGET (≤ 1)
 * @param offensiveWarChance         war chance multiplier of the OFFENSIVE posture
 * @param offensiveAdvantage         multiplier of the required strength ratio in the OFFENSIVE posture
 * @param offensiveSiegeCooldown     multiplier of the siege cooldown in the OFFENSIVE posture
 * @param defensiveSiegeCooldown     multiplier of the siege cooldown in the DEFENSIVE posture
 * @param defensiveGuardBonus        extra share of soldiers on border guard in the DEFENSIVE posture
 * @param offensiveGuardCut          smaller share of border guards in the OFFENSIVE posture
 * @param reclaimWeight              extra war weight per chunk the neighbour took from us (at most 3 chunks count)
 * @param reclaimCooldownMinutes     after peace, minutes before lost land is a reason for a new war
 * @param revengeWarDeclared         opinion lost about a country that declared war on us (positive number)
 * @param revengeChunkLost           opinion lost per chunk conquered from us
 * @param revengeSiege               opinion lost when a siege of our land starts
 * @param negativeDecayTicks         a negative opinion recovers 1 point every this many AI ticks (positive: every tick)
 * @param crisisHours                a chunk lost (or a siege of our land) within this many hours in a war = crisis mode
 * @param crisisArmyShare            share of the buildings budget moved to the army in the crisis mode (0..1)
 * @param crisisRecruitMultiplier    recruits per tick multiplier in the crisis mode
 * @param crisisSoldiersMultiplier   desired army multiplier in the crisis mode
 * @param warArmyShare               share of the buildings budget moved to the army in a normal war (0..1)
 * @param rebuildHours               hours after peace with rebuilding priority
 * @param rebuildBuildingsShare      share of the army budget moved to buildings while rebuilding (0..1)
 * @param helpCooldownMinutes        minutes between calls for help to allies
 * @param allyJoinThreshold          score an NPC ally needs to join a war it was asked to join
 * @param capitulateRatio            strength ratio (we / they) under which a losing NPC considers capitulation
 * @param capitulateLostChunks       chunks lost in this war that make capitulation an option
 * @param capitulateTreasuryDrop     treasury drop since the war started (0..1) that makes capitulation an option
 * @param capitulateCooldownMinutes  minutes between capitulation offers to the same enemy
 * @param capitulationThreshold      score needed to accept somebody's capitulation
 * @param greedyTributePerStrength   a greedy ruler wants at least this daily tribute per strength point of the loser
 * @param releaseOpinion             opinion about a vassal from which a peaceful overlord releases it (agresja raises it)
 * @param buyoutReserveFactor        a vassal buys out only if the treasury keeps this × its normal reserve afterwards
 * @param tributeDays                length of a tribute agreed after a tribute demand
 * @param tributeExpiryMinutes       how long a player country may answer a tribute demand
 * @param tributeMax                 largest daily tribute that can be demanded
 * @param tributeAcceptRatio         strength ratio (demander / us) from which a tribute demand is accepted at agresja 50
 * @param tributeRefusedOpinion      opinion lost when a tribute demand is refused (both sides)
 */
public record AiRules(
    int friendlyOpinion,
    int hostileOpinion,
    double preferredWeight,
    double preferredAdvantage,
    double offensiveWarChance,
    double offensiveAdvantage,
    double offensiveSiegeCooldown,
    double defensiveSiegeCooldown,
    double defensiveGuardBonus,
    double offensiveGuardCut,
    double reclaimWeight,
    long reclaimCooldownMinutes,
    int revengeWarDeclared,
    int revengeChunkLost,
    int revengeSiege,
    int negativeDecayTicks,
    double crisisHours,
    double crisisArmyShare,
    double crisisRecruitMultiplier,
    double crisisSoldiersMultiplier,
    double warArmyShare,
    double rebuildHours,
    double rebuildBuildingsShare,
    long helpCooldownMinutes,
    double allyJoinThreshold,
    double capitulateRatio,
    int capitulateLostChunks,
    double capitulateTreasuryDrop,
    long capitulateCooldownMinutes,
    double capitulationThreshold,
    long greedyTributePerStrength,
    int releaseOpinion,
    double buyoutReserveFactor,
    int tributeDays,
    long tributeExpiryMinutes,
    long tributeMax,
    double tributeAcceptRatio,
    int tributeRefusedOpinion
) {

    public static AiRules defaults() {
        return new AiRules(30, 30, 3.0, 0.8, 1.5, 0.9, 0.5, 1.5, 0.25, 0.2, 0.5, 360,
            40, 15, 10, 4,
            2.0, 0.85, 2.0, 1.3, 0.6, 6.0, 0.5, 60, 0.6,
            0.6, 2, 0.4, 60, 0.5, 15, 85, 1.5,
            7, 30, 100_000, 1.5, 15);
    }
}
