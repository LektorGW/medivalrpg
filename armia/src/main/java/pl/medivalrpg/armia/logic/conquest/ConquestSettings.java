// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic.conquest;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code ai.conquest.*}: background (abstract) wars of NPC countries far from players. Immutable.
 *
 * @param enabled                 master switch
 * @param abstractVsPlayers       land of player countries may be taken in the background (default false: only physically)
 * @param intervalMinutes         one simulation cycle (battle round) every N minutes
 * @param budgetMs                main-thread budget of one cycle step; the rest continues a second later
 * @param playerRadius            a player this close (blocks) to the target or the attacker's army → physical war instead
 * @param minWarMinutes           war age before the first background offensive
 * @param offensiveCooldownMinutes pause of a country between two offensives
 * @param targetRetryMinutes      the same chunk is not attacked again for this long after a failure
 * @param maxChunksPerDay         chunks one country may take per day (rolling 24 h)
 * @param maxLossPerDay           chunks one country may lose per day (protects the weak)
 * @param globalMaxChunksPerDay   all background conquests on the server per day
 * @param minSoldiers             free soldiers needed for an offensive
 * @param minOdds                 the AI launches an offensive only when its estimated strength is this many times the defence
 * @param maxForce                soldiers committed to one offensive
 * @param homeGuardShare          share of the army that never leaves home
 * @param maxRounds               rounds before a stalled offensive ends
 * @param progressPerRound        progress gained per round at full superiority (ratio 1.0)
 * @param luck                    random swing of a round (0..0.5)
 * @param casualtyRate            share of each side lost per round in an even fight
 * @param supplyCostPerSoldier    md per committed soldier and round (attacker's treasury → world:payments)
 * @param expeditions             non-adjacent targets (overland expedition → bridgehead)
 * @param expeditionMaxDistance   expedition reach in chunks from the attacker's land
 * @param expeditionPenalty       strength multiplier of an expedition (supply lines)
 * @param navalBonus              an own ship improves the expedition multiplier by this much (landing support)
 * @param warRangeChunks          NPC countries this close become possible war targets (not only direct neighbours)
 * @param distancePenalty         maximum strength loss at the maximum distance from the attacker's capital
 * @param capitalBonus            defender bonus at its capital, decreasing to 0 at {@code capitalBonusRange} chunks
 * @param capitalBonusRange       chunks
 * @param fortWall                defender bonus per wall/gate segment near the target
 * @param fortTower               per tower
 * @param fortCastle              per castle/palace
 * @param fortMax                 cap of the fortification bonus
 * @param fortRadiusChunks        fortifications counted within this radius of the target
 * @param engineVsFort            each siege engine of the attacker removes this share of the fortification bonus
 * @param militia                 strength of armed citizens defending a chunk without soldiers
 * @param garrisonRadiusChunks    defender soldiers posted this close fight fully, others send a part
 * @param reinforcementShare      share of the remaining free defenders that comes to help
 * @param allyShare               NPC allies at war with the enemy add this share of their free strength (no losses)
 * @param exhaustion              war exhaustion rules
 * @param overextension           large territory rules
 * @param revolt                  revolts on conquered land
 * @param secessionChancePerDay   chance a vassal breaks away from an exhausted/overextended NPC overlord
 * @param secessionOverlordExhaustion overlord exhaustion that allows secession
 * @param coalition               coalition against the strongest NPC country
 * @param peaceTributeShare       peace after conquests: loser pays this share of its daily income …
 * @param peaceTributePerChunk    … plus this many md per chunk taken (capped by {@code peaceTributeMax})
 * @param peaceTributeMax         daily maximum
 * @param announceMinutes         chat summary of background conquests every N minutes (aggregated)
 * @param logRounds               log every battle round in the AI decision log (else only start/end)
 */
public record ConquestSettings(
    boolean enabled, boolean abstractVsPlayers, int intervalMinutes, double budgetMs, double playerRadius,
    long minWarMinutes, long offensiveCooldownMinutes, long targetRetryMinutes,
    int maxChunksPerDay, int maxLossPerDay, int globalMaxChunksPerDay,
    int minSoldiers, double minOdds, int maxForce, double homeGuardShare, int maxRounds, double progressPerRound, double luck,
    double casualtyRate, long supplyCostPerSoldier,
    boolean expeditions, int expeditionMaxDistance, double expeditionPenalty, double navalBonus, int warRangeChunks,
    double distancePenalty, double capitalBonus, int capitalBonusRange,
    double fortWall, double fortTower, double fortCastle, double fortMax, int fortRadiusChunks, double engineVsFort,
    double militia, int garrisonRadiusChunks, double reinforcementShare, double allyShare,
    Exhaustion exhaustion, Overextension overextension, Revolt revolt,
    double secessionChancePerDay, double secessionOverlordExhaustion, Coalition coalition,
    double peaceTributeShare, long peaceTributePerChunk, long peaceTributeMax,
    int announceMinutes, boolean logRounds) {

    /**
     * War exhaustion 0–100 of a country.
     *
     * @param perHourAtWar       growth per hour of war (any number of wars)
     * @param perSoldierLost     per soldier lost in a background battle
     * @param perChunkLost       per chunk lost
     * @param perFailedOffensive per failed offensive
     * @param decayPerHourPeace  recovery per hour at peace
     * @param blockOffensive     no new offensive from this value
     * @param proposePeace       the country offers peace from this value
     * @param acceptPeace        the country accepts any peace from this value
     * @param strengthPenalty    strength loss at 100 (linear)
     */
    public record Exhaustion(double perHourAtWar, double perSoldierLost, double perChunkLost, double perFailedOffensive,
                             double decayPerHourPeace, double blockOffensive, double proposePeace, double acceptPeace,
                             double strengthPenalty) {
    }

    /**
     * @param freeChunks    land up to this size costs nothing extra
     * @param costPerChunk  md per day and chunk above {@code freeChunks}, growing with the excess ({@code × (1 + excess/free)})
     * @param strengthFloor strength multiplier never below this for huge countries
     */
    public record Overextension(int freeChunks, long costPerChunk, double strengthFloor) {
    }

    /**
     * @param windowHours  conquered chunks may revolt this long after the conquest
     * @param chancePerDay base chance per conquered chunk and day (× overextension and exhaustion)
     * @param maxPerDay    revolts per country per day
     */
    public record Revolt(double windowHours, double chancePerDay, int maxPerDay) {
    }

    /**
     * @param share           a country holding this share of all NPC land …
     * @param ratio           … or this many times the land of the second biggest is the hegemon
     * @param opinionPerDay   other NPC countries' opinion of the hegemon drops by this per day …
     * @param opinionFloor    … down to this value
     * @param joinChancePerDay chance per day that a hostile NPC joins a war against the hegemon
     */
    public record Coalition(double share, double ratio, int opinionPerDay, int opinionFloor, double joinChancePerDay) {
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampI(final long v, final int lo, final int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /** Defaults (tests, missing section). */
    public static ConquestSettings defaults() {
        return from(null);
    }

    public static ConquestSettings from(final ConfigurationSection c) {
        final ConfigurationSection s = c == null ? new org.bukkit.configuration.MemoryConfiguration() : c;
        return new ConquestSettings(
            s.getBoolean("enabled", true),
            s.getBoolean("abstract-vs-players", false),
            clampI(s.getLong("interval-minutes", 5), 1, 120),
            clamp(s.getDouble("budget-ms", 4.0), 0.5, 50),
            clamp(s.getDouble("player-radius", 160), 32, 1024),
            Math.max(0, s.getLong("min-war-minutes", 30)),
            Math.max(1, s.getLong("offensive-cooldown-minutes", 45)),
            Math.max(0, s.getLong("target-retry-minutes", 120)),
            clampI(s.getLong("max-chunks-per-day", 3), 0, 100),
            clampI(s.getLong("max-loss-per-day", 3), 0, 100),
            clampI(s.getLong("global-max-chunks-per-day", 20), 0, 1000),
            clampI(s.getLong("min-soldiers", 4), 1, 100),
            clamp(s.getDouble("min-odds", 1.2), 0.5, 10),
            clampI(s.getLong("max-force", 12), 1, 200),
            clamp(s.getDouble("home-guard-share", 0.35), 0, 0.95),
            clampI(s.getLong("max-rounds", 18), 1, 500),
            clamp(s.getDouble("progress-per-round", 0.35), 0.01, 1),
            clamp(s.getDouble("luck", 0.15), 0, 0.5),
            clamp(s.getDouble("casualty-rate", 0.10), 0, 0.5),
            Math.max(0, s.getLong("supply-cost-per-soldier", 3)),
            s.getBoolean("expeditions.enabled", true),
            clampI(s.getLong("expeditions.max-distance-chunks", 96), 1, 4096),
            clamp(s.getDouble("expeditions.strength", 0.8), 0.1, 1),
            clamp(s.getDouble("expeditions.naval-bonus", 0.1), 0, 0.5),
            clampI(s.getLong("war-range-chunks", 80), 0, 4096),
            clamp(s.getDouble("distance-penalty", 0.35), 0, 0.9),
            clamp(s.getDouble("defence.capital-bonus", 0.5), 0, 5),
            clampI(s.getLong("defence.capital-bonus-range", 8), 1, 256),
            clamp(s.getDouble("defence.fort-wall", 0.12), 0, 2),
            clamp(s.getDouble("defence.fort-tower", 0.10), 0, 2),
            clamp(s.getDouble("defence.fort-castle", 0.30), 0, 5),
            clamp(s.getDouble("defence.fort-max", 1.0), 0, 10),
            clampI(s.getLong("defence.fort-radius-chunks", 2), 0, 16),
            clamp(s.getDouble("defence.engine-vs-fort", 0.15), 0, 1),
            clamp(s.getDouble("defence.militia", 1.0), 0, 50),
            clampI(s.getLong("defence.garrison-radius-chunks", 6), 0, 256),
            clamp(s.getDouble("defence.reinforcement-share", 0.5), 0, 1),
            clamp(s.getDouble("ally-share", 0.25), 0, 1),
            new Exhaustion(
                clamp(s.getDouble("exhaustion.per-hour-at-war", 0.6), 0, 50),
                clamp(s.getDouble("exhaustion.per-soldier-lost", 1.5), 0, 50),
                clamp(s.getDouble("exhaustion.per-chunk-lost", 4), 0, 100),
                clamp(s.getDouble("exhaustion.per-failed-offensive", 5), 0, 100),
                clamp(s.getDouble("exhaustion.decay-per-hour-peace", 2), 0, 100),
                clamp(s.getDouble("exhaustion.block-offensive", 70), 0, 100),
                clamp(s.getDouble("exhaustion.propose-peace", 60), 0, 100),
                clamp(s.getDouble("exhaustion.accept-peace", 75), 0, 101),
                clamp(s.getDouble("exhaustion.strength-penalty", 0.4), 0, 0.95)),
            new Overextension(
                clampI(s.getLong("overextension.free-chunks", 100), 1, 100_000),
                Math.max(0, s.getLong("overextension.cost-per-chunk", 4)),
                clamp(s.getDouble("overextension.strength-floor", 0.6), 0.1, 1)),
            new Revolt(
                clamp(s.getDouble("revolt.window-hours", 72), 0, 24 * 60),
                clamp(s.getDouble("revolt.chance-per-day", 0.04), 0, 1),
                clampI(s.getLong("revolt.max-per-day", 1), 0, 100)),
            clamp(s.getDouble("vassals.secession-chance-per-day", 0.15), 0, 1),
            clamp(s.getDouble("vassals.secession-overlord-exhaustion", 60), 0, 100),
            new Coalition(
                clamp(s.getDouble("coalition.share", 0.30), 0.05, 1),
                clamp(s.getDouble("coalition.ratio", 1.8), 1, 100),
                clampI(s.getLong("coalition.opinion-per-day", 8), 0, 100),
                clampI(s.getLong("coalition.opinion-floor", -60), -100, 0),
                clamp(s.getDouble("coalition.join-chance-per-day", 0.25), 0, 1)),
            clamp(s.getDouble("peace.tribute-share", 0.15), 0, 1),
            Math.max(0, s.getLong("peace.tribute-per-chunk", 20)),
            Math.max(0, s.getLong("peace.tribute-max", 2000)),
            clampI(s.getLong("announce-minutes", 15), 1, 1440),
            s.getBoolean("log-rounds", true));
    }
}
