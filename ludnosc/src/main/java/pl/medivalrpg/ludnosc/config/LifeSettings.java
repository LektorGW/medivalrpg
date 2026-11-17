// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.ludnosc.logic.DailyRoutine;
import pl.medivalrpg.ludnosc.logic.LifeGrowth;
import pl.medivalrpg.ludnosc.logic.Workplaces;

/**
 * Settings of the citizens' life ({@code life.*} in config.yml). Every key is optional: a config.yml without the
 * section uses the defaults below, so the feature works without merging the config.
 */
public record LifeSettings(boolean enabled,
                           // home & work & physical
                           int assignEverySeconds, int perTick, double budgetMs, int playerRange, boolean vanillaHome,
                           boolean steerHome, int craftSlots, int siteHelpers, int storeHelpers, DailyRoutine routine,
                           // money
                           boolean wages, Map<Workplaces.Kind, Long> wage, long minTreasury, long mealPrice,
                           boolean taxFromCitizens, double employedIncome, double unemployedIncome, boolean winterIndoors,
                           // growth & family
                           boolean growth, LifeGrowth.Settings growthSettings, boolean family, double marriageChance,
                           long childhoodHours, long birthCooldownHours, int maxChildren, boolean oldAge, long lifespanMinDays,
                           long lifespanMaxDays, double oldAgeChance, int maxDeathsPerDay, double initialAgeMaxShare,
                           // huts
                           boolean huts, String hutType, double hutCostShare, int hutLimit, int hutCheckMinutes,
                           boolean felling, int fellingSeconds, int fellingRadius, int spawnRadius, int treesPerHut,
                           int maxLogs) {

    public static LifeSettings defaults() {
        return from(null);
    }

    public static LifeSettings from(final ConfigurationSection root) {
        final ConfigurationSection c = root == null ? null : root.getConfigurationSection("life");
        final DailyRoutine d = DailyRoutine.defaults();
        DailyRoutine routine = new DailyRoutine(i(c, "routine.work-start", d.workStart()), i(c, "routine.noon-start", d.noonStart()),
            i(c, "routine.noon-end", d.noonEnd()), i(c, "routine.evening-start", d.eveningStart()),
            i(c, "routine.night-start", d.nightStart()));
        if (!routine.valid()) {
            routine = d;
        }
        final Map<Workplaces.Kind, Long> wage = new EnumMap<>(Workplaces.Kind.class);
        final Map<Workplaces.Kind, Long> defWage = Map.of(Workplaces.Kind.POLE, 2L, Workplaces.Kind.KOPALNIA, 3L,
            Workplaces.Kind.KAMIENIOLOM, 3L, Workplaces.Kind.TARTAK, 2L, Workplaces.Kind.PRZYSTAN, 2L, Workplaces.Kind.WARSZTAT, 3L,
            Workplaces.Kind.BUDOWA, 2L, Workplaces.Kind.SPICHLERZ, 1L, Workplaces.Kind.MAGAZYN, 1L);
        for (final Workplaces.Kind k : Workplaces.Kind.values()) {
            wage.put(k, Math.max(0, l(c, "wages.per-day." + k.key(), defWage.getOrDefault(k, 1L))));
        }
        final LifeGrowth.Settings g = new LifeGrowth.Settings(Math.max(0, dbl(c, "growth.food-days-target", 3.0)),
            Math.max(0, l(c, "growth.wealth-target", 40)), clamp01(dbl(c, "growth.birth-chance", 0.15)),
            Math.max(0, dbl(c, "growth.immigration-chance", 0.8)), Math.max(0, i(c, "growth.max-per-tick", 2)));
        return new LifeSettings(b(c, "enabled", true),
            Math.max(1, i(c, "assign-every-seconds", 2)), Math.max(1, i(c, "physical.per-tick", 6)),
            Math.max(0.1, dbl(c, "physical.budget-ms", 1.0)), Math.max(8, i(c, "physical.player-range", 64)),
            b(c, "physical.vanilla-home-memory", true), b(c, "physical.steer-home", true),
            Math.max(0, i(c, "work.craft-slots", 3)), Math.max(0, i(c, "work.site-helpers", 2)),
            Math.max(0, i(c, "work.store-helpers", 2)), routine,
            b(c, "wages.enabled", true), Map.copyOf(wage), Math.max(0, l(c, "wages.min-treasury", 0)),
            Math.max(0, l(c, "meals.price", 1)),
            b(c, "income.tax-from-citizens", true), Math.max(0, dbl(c, "income.employed-factor", 1.1)),
            Math.max(0, dbl(c, "income.unemployed-factor", 1.0)), b(c, "work.winter-farmers-indoors", true),
            b(c, "growth.enabled", true), g, b(c, "family.enabled", true), clamp01(dbl(c, "family.marriage-chance", 0.25)),
            Math.max(1, l(c, "family.childhood-hours", 72)), Math.max(0, l(c, "family.birth-cooldown-hours", 24)),
            Math.max(0, i(c, "family.max-children", 4)), b(c, "family.old-age.enabled", true),
            lifeMin(c), Math.max(lifeMin(c), l(c, "family.old-age.lifespan-max-days", 50)),
            clamp01(dbl(c, "family.old-age.chance-per-day", 0.35)), Math.max(0, i(c, "family.old-age.max-deaths-per-day", 2)),
            Math.max(0, Math.min(0.9, dbl(c, "family.old-age.initial-age-max-share", 0.4))),
            b(c, "huts.enabled", true), s(c, "huts.type", "chata"), Math.max(0, dbl(c, "huts.cost-share", 1.0)),
            Math.max(0, i(c, "huts.limit", 1)), Math.max(1, i(c, "huts.check-minutes", 10)),
            b(c, "huts.felling.enabled", true), Math.max(5, i(c, "huts.felling.interval-seconds", 30)),
            Math.max(8, Math.min(64, i(c, "huts.felling.radius", 32))), Math.max(0, i(c, "huts.felling.spawn-radius", 150)),
            Math.max(0, i(c, "huts.felling.max-trees-per-hut", 8)), Math.max(4, Math.min(128, i(c, "huts.felling.max-logs", 40))));
    }

    private static long lifeMin(final ConfigurationSection c) {
        return Math.max(1, l(c, "family.old-age.lifespan-min-days", 35));
    }

    public long wageOf(final Workplaces.Kind kind) {
        return kind == null ? 0 : this.wage.getOrDefault(kind, 0L);
    }

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static boolean b(final ConfigurationSection c, final String k, final boolean d) {
        return c == null ? d : c.getBoolean(k, d);
    }

    private static int i(final ConfigurationSection c, final String k, final int d) {
        return c == null ? d : c.getInt(k, d);
    }

    private static long l(final ConfigurationSection c, final String k, final long d) {
        return c == null ? d : c.getLong(k, d);
    }

    private static double dbl(final ConfigurationSection c, final String k, final double d) {
        return c == null ? d : c.getDouble(k, d);
    }

    private static String s(final ConfigurationSection c, final String k, final String d) {
        return c == null ? d : c.getString(k, d);
    }
}
