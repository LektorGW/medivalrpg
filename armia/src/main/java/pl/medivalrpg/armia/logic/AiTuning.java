// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * Global knobs of the NPC-country AI (config {@code ai.tuning}). Money in copper. Pure value.
 *
 * @param baseIncomePerDay         virtual income of every NPC country per day
 * @param incomePerChunkPerDay     extra virtual income per claimed chunk per day
 * @param expansionChance          chance per AI tick to claim a chunk at ekspansja = 100 (scaled linearly)
 * @param reserveDaysOfWages       days of wages kept before spending on claims/recruits (at bogactwo = 50; scaled)
 * @param minReserve               money never spent on expansion/recruitment
 * @param soldiersPerChunk         desired army size per chunk at agresja = 50
 * @param maxRecruitPerTick        recruits per AI tick
 * @param minAggressionForWar      below this agresja the AI never declares war
 * @param warAdvantage             strength ratio (we / they) required to attack at agresja = 50
 * @param warAdvantageAggression   how much 100 points of agresja above 50 lower the required ratio
 * @param warChance                chance per AI tick to declare war at agresja = 100 when a target qualifies
 * @param maxConcurrentWars        wars at once
 * @param warOpinionBlock          opinion (−100..100) from which the AI refuses to attack a neighbour
 * @param minWarHours              minimum war duration before peace is offered or accepted
 * @param fatigueHours             war length (hours, at agresja = 0) that means full time fatigue
 * @param lossTolerance            soldiers lost at agresja = 0 that mean full loss fatigue (+1 per 10 agresja)
 * @param losingRatio              strength ratio under which the AI considers itself losing
 * @param peaceThreshold           score needed to accept peace
 * @param allianceThreshold        score needed to accept an alliance
 * @param minDiplomacyForAlliance  below this dyplomacja alliances are always refused
 * @param allianceProposeDiplomacy from this dyplomacja the AI proposes alliances itself
 * @param allianceProposeChance    chance per AI tick to propose (at dyplomacja = 100)
 * @param playerWeight             strength of one online player compared to one average soldier (1.0)
 * @param offlinePlayerWeight      strength of one offline member
 */
public record AiTuning(
    long baseIncomePerDay,
    long incomePerChunkPerDay,
    double expansionChance,
    double reserveDaysOfWages,
    long minReserve,
    double soldiersPerChunk,
    int maxRecruitPerTick,
    int minAggressionForWar,
    double warAdvantage,
    double warAdvantageAggression,
    double warChance,
    int maxConcurrentWars,
    int warOpinionBlock,
    double minWarHours,
    double fatigueHours,
    int lossTolerance,
    double losingRatio,
    double peaceThreshold,
    double allianceThreshold,
    int minDiplomacyForAlliance,
    int allianceProposeDiplomacy,
    double allianceProposeChance,
    double playerWeight,
    double offlinePlayerWeight
) {

    public static AiTuning defaults() {
        return new AiTuning(300, 25, 0.6, 2.0, 200, 0.5, 3, 55, 1.6, 0.8, 0.35, 1, 40,
            2.0, 6.0, 4, 0.8, 0.5, 0.55, 30, 65, 0.25, 1.5, 0.3);
    }
}
