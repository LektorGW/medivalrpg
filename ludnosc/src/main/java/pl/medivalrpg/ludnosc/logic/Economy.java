// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Locale;
import java.util.Map;

/**
 * Daily food and tax computation for one country (pure, unit tested). Order of a day: production (farmers,
 * virtual NPC food) up to the granary capacity → everybody eats → tax (reduced by hunger and homelessness) →
 * departures after the grace days.
 */
public final class Economy {

    private Economy() {
    }

    /**
     * State of a country before the day.
     *
     * @param adultsByProfession adult citizens per profession key (e.g. {@code farmer → 3}, {@code none → 1})
     * @param babies             baby citizens
     * @param housing            beds in housing buildings
     * @param food               food stored now
     * @param capacity           granary capacity
     * @param npc                NPC country (virtual food)
     * @param hungryDays         consecutive days without enough food so far
     * @param homelessDays       consecutive days with more citizens than beds so far
     * @param farms              farm field buildings of the country
     */
    public record DayInput(Map<String, Integer> adultsByProfession, int babies, int housing, long food, long capacity,
                           boolean npc, int hungryDays, int homelessDays, int farms, long bonusFood) {

        public DayInput {
            adultsByProfession = Map.copyOf(adultsByProfession);
            farms = Math.max(0, farms);
            // may be negative (bad mood lowers the production; the total never goes below 0)
        }

        /** Input without building bonuses. */
        public DayInput(final Map<String, Integer> adultsByProfession, final int babies, final int housing, final long food,
                        final long capacity, final boolean npc, final int hungryDays, final int homelessDays, final int farms) {
            this(adultsByProfession, babies, housing, food, capacity, npc, hungryDays, homelessDays, farms, 0);
        }

        /** Input without farm fields. */
        public DayInput(final Map<String, Integer> adultsByProfession, final int babies, final int housing, final long food,
                        final long capacity, final boolean npc, final int hungryDays, final int homelessDays) {
            this(adultsByProfession, babies, housing, food, capacity, npc, hungryDays, homelessDays, 0, 0);
        }

        public int adults() {
            int n = 0;
            for (final int v : this.adultsByProfession.values()) {
                n += v;
            }
            return n;
        }

        public int population() {
            return this.adults() + this.babies;
        }
    }

    /**
     * Outcome of a day.
     *
     * @param food         food left after eating
     * @param produced     food added (farmers + virtual), after the capacity cap
     * @param eaten        food eaten
     * @param fedRatio     0..1, share of the need that was covered
     * @param tax          copper paid into the treasury
     * @param departures   citizens that leave the country today
     * @param hungryDays   new hungry-days counter
     * @param homelessDays new homeless-days counter
     */
    public record DayResult(long food, long produced, long eaten, double fedRatio, long tax, int departures,
                            int hungryDays, int homelessDays) {

        public boolean fed() {
            return this.fedRatio >= 1.0;
        }
    }

    /** {@code minecraft:farmer} / {@code FARMER} / {@code farmer} → {@code farmer}. */
    public static String professionKey(final String profession) {
        if (profession == null || profession.isBlank()) {
            return "none";
        }
        final String p = profession.trim().toLowerCase(Locale.ROOT);
        final int colon = p.indexOf(':');
        return colon >= 0 ? p.substring(colon + 1) : p;
    }

    public static long production(final DayInput in, final EconomyRules rules) {
        final int farmers = in.adultsByProfession().getOrDefault("farmer", 0);
        long produced = farmers * rules.farmerFood() + (long) in.farms() * rules.farmFood() + in.bonusFood();
        if (in.npc()) {
            produced += rules.npcFoodPerDay() + (long) in.population() * rules.npcFoodPerCitizen();
        }
        return Math.max(0, produced);
    }

    /** Untaxed daily income: adults × base × profession multiplier + babies × base × baby multiplier. */
    public static double grossTax(final DayInput in, final EconomyRules rules) {
        double gross = 0;
        for (final Map.Entry<String, Integer> e : in.adultsByProfession().entrySet()) {
            gross += e.getValue() * rules.taxPerCitizen() * rules.multiplier(e.getKey());
        }
        gross += in.babies() * rules.taxPerCitizen() * rules.babyMultiplier();
        return Math.max(0, gross);
    }

    /** Multiplier for citizens above capacity: housed pay fully, the homeless pay {@code homelessTaxMultiplier}. */
    public static double housingFactor(final int population, final int housing, final EconomyRules rules) {
        if (population <= 0 || housing >= population) {
            return 1.0;
        }
        final double housed = Math.max(0, housing) / (double) population;
        return housed + (1 - housed) * rules.homelessTaxMultiplier();
    }

    /** Linear between {@code hungryTaxMultiplier} (no food) and 1 (fully fed). */
    public static double hungerFactor(final double fedRatio, final EconomyRules rules) {
        final double r = Math.max(0, Math.min(1, fedRatio));
        return rules.hungryTaxMultiplier() + (1 - rules.hungryTaxMultiplier()) * r;
    }

    public static DayResult day(final DayInput in, final EconomyRules rules) {
        final int population = in.population();
        // 1. production, capped by the granary (NPC countries optionally uncapped); never destroys stored food
        final long before = Math.max(0, in.food());
        long food = before + production(in, rules);
        if (!(in.npc() && rules.npcIgnoreCapacity())) {
            food = Math.min(food, Math.max(before, in.capacity()));
        }
        final long produced = food - before;
        // 2. eating
        final long need = (long) population * rules.foodPerCitizen();
        final long eaten = Math.min(need, food);
        food -= eaten;
        final double fedRatio = need == 0 ? 1.0 : eaten / (double) need;
        // 3. tax
        final double tax = grossTax(in, rules) * housingFactor(population, in.housing(), rules) * hungerFactor(fedRatio, rules);
        // 4. departures
        final int homeless = Math.max(0, population - Math.max(0, in.housing()));
        final int homelessDays = homeless > 0 ? in.homelessDays() + 1 : 0;
        final int hungryDays = fedRatio < 1.0 ? in.hungryDays() + 1 : 0;
        int leave = 0;
        if (homeless > 0 && homelessDays > rules.homelessGraceDays()) {
            leave += (int) Math.ceil(homeless * rules.homelessLeaveFraction());
        }
        if (hungryDays > rules.hungerGraceDays()) {
            final int remaining = population - leave;
            leave += (int) Math.ceil(remaining * rules.hungerLeaveFraction() * (1 - fedRatio));
        }
        leave = Math.max(0, Math.min(Math.min(leave, population), rules.maxLeavePerDay()));
        return new DayResult(food, produced, eaten, fedRatio, Math.round(tax), leave, hungryDays, homelessDays);
    }

    /** Expected tax of the next day without changing anything (for {@code LudnoscApi#dailyTaxIncome}). */
    public static long expectedTax(final DayInput in, final EconomyRules rules) {
        return day(in, rules).tax();
    }
}
