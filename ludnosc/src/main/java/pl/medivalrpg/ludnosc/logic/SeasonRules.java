// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.EnumMap;
import java.util.Map;

/**
 * Numbers of seasons and poor harvests (config {@code seasons}; pure, unit tested).
 *
 * @param enabled           seasons on/off (off = always 1.0, no poor harvests)
 * @param lengthDays        real days of one season
 * @param first             season of the first cycle
 * @param food              season → food production multiplier
 * @param cropGrowth        season → chance that a vanilla crop growth step happens (1 = vanilla)
 * @param cropGrowthEnabled apply {@code cropGrowth} to crops in the world
 * @param affectsNpc        NPC countries (virtual food) are affected too
 * @param blightChance      season → daily chance of a poor harvest per country
 * @param blightFactor      production multiplier during a poor harvest
 * @param blightMinDays     shortest poor harvest (real days)
 * @param blightMaxDays     longest poor harvest
 * @param blightCooldownDays days without a new poor harvest after one ended
 * @param maxLeaveFraction  at most this share of the population leaves in one day (starvation is gradual)
 */
public record SeasonRules(boolean enabled, double lengthDays, Season first, Map<Season, Double> food,
                          Map<Season, Double> cropGrowth, boolean cropGrowthEnabled, boolean affectsNpc,
                          Map<Season, Double> blightChance, double blightFactor, int blightMinDays, int blightMaxDays,
                          int blightCooldownDays, double maxLeaveFraction) {

    public SeasonRules {
        food = complete(food, 1.0, 0, 5);
        cropGrowth = complete(cropGrowth, 1.0, 0, 1);
        blightChance = complete(blightChance, 0.0, 0, 1);
        blightFactor = Math.max(0, Math.min(1, blightFactor));
        blightMinDays = Math.max(1, blightMinDays);
        blightMaxDays = Math.max(blightMinDays, blightMaxDays);
        blightCooldownDays = Math.max(0, blightCooldownDays);
        lengthDays = Math.max(0.01, lengthDays);
        first = first == null ? Season.JESIEN : first;
        maxLeaveFraction = Math.max(0, Math.min(1, maxLeaveFraction));
    }

    /** Defaults of the shipped config. */
    public static SeasonRules defaults() {
        return new SeasonRules(true, 7, Season.JESIEN,
            Map.of(Season.WIOSNA, 0.9, Season.LATO, 1.1, Season.JESIEN, 1.4, Season.ZIMA, 0.25),
            Map.of(Season.WIOSNA, 1.0, Season.LATO, 1.0, Season.JESIEN, 0.9, Season.ZIMA, 0.4), true, true,
            Map.of(Season.WIOSNA, 0.04, Season.LATO, 0.06, Season.JESIEN, 0.03, Season.ZIMA, 0.0), 0.4, 2, 4, 10, 0.1);
    }

    private static Map<Season, Double> complete(final Map<Season, Double> in, final double def, final double min, final double max) {
        final EnumMap<Season, Double> out = new EnumMap<>(Season.class);
        for (final Season s : Season.values()) {
            final Double v = in == null ? null : in.get(s);
            out.put(s, Math.max(min, Math.min(max, v == null || v.isNaN() ? def : v)));
        }
        return Map.copyOf(out);
    }

    public long lengthMillis() {
        return SeasonClock.lengthOfDays(this.lengthDays);
    }

    /** Cooldown belongs to the actual end of the blight, including time spent offline. */
    public long cooldownUntil(final long endedAt) {
        return endedAt + (long) this.blightCooldownDays * SeasonClock.DAY;
    }

    /** Food multiplier of a season (1 when disabled). */
    public double foodMultiplier(final Season s) {
        return this.enabled && s != null ? this.food.get(s) : 1.0;
    }

    /** Chance 0..1 that a crop growth step succeeds now. */
    public double growthChance(final Season s) {
        return this.enabled && this.cropGrowthEnabled && s != null ? this.cropGrowth.get(s) : 1.0;
    }

    /**
     * Total food production factor of a country.
     *
     * @param season  current season
     * @param blight  the country has a poor harvest now
     * @param npc     NPC country
     */
    public double productionFactor(final Season season, final boolean blight, final boolean npc) {
        if (!this.enabled || npc && !this.affectsNpc) {
            return 1.0;
        }
        return this.foodMultiplier(season) * (blight ? this.blightFactor : 1.0);
    }

    /** Whether a poor harvest starts today; {@code roll} is uniform 0..1. */
    public boolean blightStarts(final Season season, final double roll, final boolean onCooldown) {
        return this.enabled && !onCooldown && season != null && roll < this.blightChance.get(season);
    }

    /** Length in days of a new poor harvest; {@code roll} is uniform 0..1. */
    public int blightDays(final double roll) {
        final int span = this.blightMaxDays - this.blightMinDays + 1;
        return this.blightMinDays + Math.min(span - 1, (int) Math.floor(Math.max(0, roll) * span));
    }

    /** Departures of a day never exceed {@link #maxLeaveFraction} of the population (at least one when any). */
    public int capDepartures(final int departures, final int population) {
        if (departures <= 0 || population <= 0) {
            return 0;
        }
        final int cap = Math.max(1, (int) Math.floor(population * this.maxLeaveFraction));
        return Math.min(departures, cap);
    }
}
