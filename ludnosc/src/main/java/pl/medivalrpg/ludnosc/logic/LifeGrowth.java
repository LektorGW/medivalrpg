// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.random.RandomGenerator;

/**
 * Population growth of a country in one growth tick (pure, unit tested): children born to couples and settlers
 * arriving from outside. Nothing happens without a free bed and food for the newcomer; beyond that the chances grow
 * with the food reserve (days of meals in the granary) and with the wealth of the citizens (average savings).
 */
public final class LifeGrowth {

    private LifeGrowth() {
    }

    /**
     * @param foodDaysTarget     days of meals in stock at which the food factor reaches 1
     * @param wealthTarget       average savings (md) at which the wealth factor reaches 1 (0 = wealth ignored)
     * @param birthChance        chance per eligible couple and tick at a full food factor
     * @param immigrationChance  expected settlers per tick at full factors (the old fixed growth = 1.0)
     * @param maxPerTick         cap per tick (births + settlers)
     */
    public record Settings(double foodDaysTarget, long wealthTarget, double birthChance, double immigrationChance,
                           int maxPerTick) {
    }

    /**
     * @param couples couples that may have a child now (married adults, under the child limit, after the cooldown)
     * @param mood    Zycie multiplier (1 = neutral)
     */
    public record Input(int population, int pending, int housing, long food, long foodPerSettler, long foodPerCitizen,
                        long averageSavings, int couples, double mood, double bonus) {
    }

    /** Births (to the first {@code births} eligible couples) and settlers. */
    public record Outcome(int births, int settlers) {

        public int total() {
            return this.births + this.settlers;
        }
    }

    public static double foodFactor(final Input in, final Settings s) {
        final long daily = Math.max(1, (long) Math.max(1, in.population()) * Math.max(0, in.foodPerCitizen()));
        final double days = Math.max(0, in.food()) / (double) daily;
        return s.foodDaysTarget() <= 0 ? 1.0 : Math.max(0, Math.min(1.0, days / s.foodDaysTarget()));
    }

    public static double wealthFactor(final Input in, final Settings s) {
        if (s.wealthTarget() <= 0 || in.population() <= 0) {
            return 1.0;
        }
        return Math.max(0.25, Math.min(1.5, 0.25 + 0.75 * in.averageSavings() / (double) s.wealthTarget()));
    }

    public static Outcome tick(final Input in, final Settings s, final RandomGenerator random) {
        final int free = in.housing() - in.population() - in.pending();
        final long affordable = in.foodPerSettler() <= 0 ? Long.MAX_VALUE : Math.max(0, in.food()) / in.foodPerSettler();
        int room = (int) Math.min(Math.min(free, affordable), Math.max(0, s.maxPerTick()));
        if (room <= 0) {
            return new Outcome(0, 0);
        }
        final double food = foodFactor(in, s);
        final double wealth = wealthFactor(in, s);
        final double mood = Math.max(0, in.mood());
        int births = 0;
        for (int i = 0; i < in.couples() && births < room; i++) {
            if (random.nextDouble() < s.birthChance() * food * Math.min(1.25, mood)) {
                births++;
            }
        }
        room -= births;
        final double expected = Math.max(0, s.immigrationChance() * food * wealth * mood + in.bonus());
        int settlers = (int) Math.floor(expected) + (random.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        settlers = Math.max(0, Math.min(room, settlers));
        return new Outcome(births, settlers);
    }
}
