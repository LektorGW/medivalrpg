// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Map;

/**
 * Numbers of the daily economy of NPC citizens (from config.yml). Food is counted in abstract "food points"
 * (bread = 5, wheat = 1... see {@code food.items}); money in copper.
 *
 * @param foodPerCitizen          food eaten by one citizen per day
 * @param farmerFood              food produced by one adult farmer per day
 * @param npcFoodPerDay           virtual food an NPC country receives per day
 * @param npcFoodPerCitizen       extra virtual food per citizen of an NPC country per day
 * @param npcIgnoreCapacity       NPC countries are not limited by the granary capacity
 * @param taxPerCitizen           base daily tax of one adult citizen
 * @param professionMultipliers   profession key without namespace ({@code farmer}, {@code none}...) → tax multiplier (default 1)
 * @param babyMultiplier          tax multiplier of baby villagers
 * @param hungryTaxMultiplier     tax multiplier when nobody gets food (scaled linearly with the fed ratio)
 * @param homelessTaxMultiplier   tax multiplier of citizens above the housing capacity
 * @param hungerGraceDays         hungry days tolerated before citizens leave
 * @param hungerLeaveFraction     fraction of the population leaving per further hungry day (scaled with hunger)
 * @param homelessGraceDays       days with too few beds tolerated before the homeless leave
 * @param homelessLeaveFraction   fraction of the homeless leaving per further day
 * @param maxLeavePerDay          cap of departures per country and day
 * @param farmFood                food produced by one farm field building per day
 */
public record EconomyRules(
    long foodPerCitizen,
    long farmerFood,
    long npcFoodPerDay,
    long npcFoodPerCitizen,
    boolean npcIgnoreCapacity,
    long taxPerCitizen,
    Map<String, Double> professionMultipliers,
    double babyMultiplier,
    double hungryTaxMultiplier,
    double homelessTaxMultiplier,
    int hungerGraceDays,
    double hungerLeaveFraction,
    int homelessGraceDays,
    double homelessLeaveFraction,
    int maxLeavePerDay,
    long farmFood
) {

    public EconomyRules {
        professionMultipliers = Map.copyOf(professionMultipliers);
    }

    /** Rules without farm fields. */
    public EconomyRules(final long foodPerCitizen, final long farmerFood, final long npcFoodPerDay, final long npcFoodPerCitizen,
                        final boolean npcIgnoreCapacity, final long taxPerCitizen, final Map<String, Double> professionMultipliers,
                        final double babyMultiplier, final double hungryTaxMultiplier, final double homelessTaxMultiplier,
                        final int hungerGraceDays, final double hungerLeaveFraction, final int homelessGraceDays,
                        final double homelessLeaveFraction, final int maxLeavePerDay) {
        this(foodPerCitizen, farmerFood, npcFoodPerDay, npcFoodPerCitizen, npcIgnoreCapacity, taxPerCitizen,
            professionMultipliers, babyMultiplier, hungryTaxMultiplier, homelessTaxMultiplier, hungerGraceDays,
            hungerLeaveFraction, homelessGraceDays, homelessLeaveFraction, maxLeavePerDay, 0);
    }

    public double multiplier(final String profession) {
        return this.professionMultipliers.getOrDefault(Economy.professionKey(profession), 1.0);
    }
}
