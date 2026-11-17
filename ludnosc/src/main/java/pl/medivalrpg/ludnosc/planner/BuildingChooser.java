// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Decides WHAT a settlement should build next from its needs and wealth (pure, unit tested).
 *
 * <ol>
 *   <li>a well in the centre (first building of a town),</li>
 *   <li>housing when free beds &lt; {@code housingFreeRatio} of the population,</li>
 *   <li>farm fields when the town eats more than it produces,</li>
 *   <li>at war: towers, walls, gates,</li>
 *   <li>a granary when the stock is close to the capacity,</li>
 *   <li>barracks before a bigger army,</li>
 *   <li>growth: economy (smithy, mill, stable) → civic (tavern, market, town hall) → church → representative
 *       (castle, palace), one of each first,</li>
 *   <li>more housing for growth.</li>
 * </ol>
 * Essential needs (1–5) that are not affordable return {@link Reason#NO_FUNDS} (the treasury saves up); growth
 * candidates that are too expensive are skipped.
 */
public final class BuildingChooser {

    public enum Reason { WELL, HOUSING, FARM, DEFENSE, FOOD, MILITARY, GROWTH, MORE_HOUSING, NO_FUNDS, NOTHING_NEEDED }

    /**
     * Planner input.
     *
     * @param level        settlement level index
     * @param counts       template id → finished + under construction + queued
     * @param soldiers     NPC soldiers of the country (Armia), 0 without Armia
     * @param barracksBeds beds in barracks (finished)
     * @param budget       copper the caller allows to spend now
     * @param foodBalance  expected daily food change (production − consumption)
     */
    public record Input(int level, int population, int housing, long food, long foodCapacity, long foodBalance, int soldiers,
                        int barracksBeds, boolean atWar, long treasury, long budget, Map<String, Integer> counts,
                        List<BuildingTemplate.Meta> catalog, double housingFreeRatio, double granaryFullRatio, int era,
                        java.util.function.Predicate<String> discovered, long variantSeed) {

        /** Input without a variant seed (look variants are picked by the family count alone). */
        public Input(final int level, final int population, final int housing, final long food, final long foodCapacity,
                     final long foodBalance, final int soldiers, final int barracksBeds, final boolean atWar, final long treasury,
                     final long budget, final Map<String, Integer> counts, final List<BuildingTemplate.Meta> catalog,
                     final double housingFreeRatio, final double granaryFullRatio, final int era,
                     final java.util.function.Predicate<String> discovered) {
            this(level, population, housing, food, foodCapacity, foodBalance, soldiers, barracksBeds, atWar, treasury, budget, counts,
                catalog, housingFreeRatio, granaryFullRatio, era, discovered, 0L);
        }

        /** Input of the Middle Ages (no epochs). */
        public Input(final int level, final int population, final int housing, final long food, final long foodCapacity,
                     final long foodBalance, final int soldiers, final int barracksBeds, final boolean atWar, final long treasury,
                     final long budget, final Map<String, Integer> counts, final List<BuildingTemplate.Meta> catalog,
                     final double housingFreeRatio, final double granaryFullRatio) {
            this(level, population, housing, food, foodCapacity, foodBalance, soldiers, barracksBeds, atWar, treasury, budget, counts,
                catalog, housingFreeRatio, granaryFullRatio, 1, d -> false);
        }

        /**
         * Buildings of the TYPE of a template id: the base type and all its look variants together (limits per
         * country and "one of each" are shared by a family, e.g. {@code dom} + {@code dom_szachulec}).
         */
        public int count(final String id) {
            final String base = this.baseOf(id);
            int n = 0;
            for (final Map.Entry<String, Integer> e : this.counts.entrySet()) {
                if (e.getKey().equals(id) || this.baseOf(e.getKey()).equals(base)) {
                    n += e.getValue();
                }
            }
            return n;
        }

        /** Buildings of exactly this template id. */
        public int rawCount(final String id) {
            return this.counts.getOrDefault(id, 0);
        }

        /** Base type of a template id ({@code variant-of}), the id itself when unknown or not a variant. */
        public String baseOf(final String id) {
            for (final BuildingTemplate.Meta m : this.catalog) {
                if (m.id().equals(id)) {
                    return m.baseType();
                }
            }
            return id;
        }

        /** The same input with a seed for the choice of look variants (e.g. a hash of the country id). */
        public Input withVariantSeed(final long seed) {
            return new Input(this.level, this.population, this.housing, this.food, this.foodCapacity, this.foodBalance, this.soldiers,
                this.barracksBeds, this.atWar, this.treasury, this.budget, this.counts, this.catalog, this.housingFreeRatio,
                this.granaryFullRatio, this.era, this.discovered, seed);
        }

        /** The same input without some template ids (types the planner backs off from, or that do not fit a plot). */
        public Input without(final java.util.Set<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return this;
            }
            final List<BuildingTemplate.Meta> left = new ArrayList<>();
            for (final BuildingTemplate.Meta m : this.catalog) {
                if (!ids.contains(m.id())) {
                    left.add(m);
                }
            }
            return new Input(this.level, this.population, this.housing, this.food, this.foodCapacity, this.foodBalance, this.soldiers,
                this.barracksBeds, this.atWar, this.treasury, this.budget, this.counts, left, this.housingFreeRatio,
                this.granaryFullRatio, this.era, this.discovered, this.variantSeed);
        }

        /** The same input with another budget (admin "darmowo" orders: unlimited). */
        public Input withBudget(final long newBudget, final long newTreasury) {
            return new Input(this.level, this.population, this.housing, this.food, this.foodCapacity, this.foodBalance, this.soldiers,
                this.barracksBeds, this.atWar, newTreasury, newBudget, this.counts, this.catalog, this.housingFreeRatio,
                this.granaryFullRatio, this.era, this.discovered, this.variantSeed);
        }

        public int countCategory(final String category) {
            int n = 0;
            for (final BuildingTemplate.Meta m : this.catalog) {
                if (m.category().equals(category)) {
                    n += this.rawCount(m.id());
                }
            }
            return n;
        }
    }

    /** Decision: template id (null when nothing) and why. */
    public record Choice(String type, Reason reason) {

        public boolean found() {
            return this.type != null;
        }
    }

    private BuildingChooser() {
    }

    static boolean allowed(final Input in, final BuildingTemplate.Meta m) {
        return m.tier() <= in.level() && in.population() >= m.minPopulation() && in.treasury() >= m.minTreasury()
            && (m.maxPerCountry() == 0 || in.count(m.id()) < m.maxPerCountry())
            && m.era() <= in.era() && (m.discovery().isEmpty() || in.discovered().test(m.discovery()));
    }

    static boolean affordable(final Input in, final BuildingTemplate.Meta m) {
        return m.cost() <= in.budget() && m.cost() <= in.treasury();
    }

    /** Copies of player buildings ({@code kopia_*}) are never chosen by the planner. */
    static boolean plannable(final BuildingTemplate.Meta m) {
        return !m.id().startsWith("kopia_");
    }

    /**
     * Allowed templates matching the filter, one per building type: the look variants of a type ({@code variant-of})
     * are collapsed into the one {@link #variant picked} for the next building of that type.
     */
    private static List<BuildingTemplate.Meta> of(final Input in, final Predicate<BuildingTemplate.Meta> filter) {
        final Map<String, List<BuildingTemplate.Meta>> families = new java.util.LinkedHashMap<>();
        for (final BuildingTemplate.Meta m : in.catalog()) {
            if (plannable(m) && filter.test(m) && allowed(in, m)) {
                families.computeIfAbsent(m.baseType(), k -> new ArrayList<>()).add(m);
            }
        }
        final List<BuildingTemplate.Meta> out = new ArrayList<>();
        for (final Map.Entry<String, List<BuildingTemplate.Meta>> e : families.entrySet()) {
            out.add(variant(in, e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Deterministic choice among the templates of one building type (the base and its look variants): members sorted
     * by id, index = (hash of the type and {@link Input#variantSeed()} + number of buildings of the type) mod size.
     * Consecutive buildings of a type therefore cycle through every variant, starting at a different one per seed.
     */
    static BuildingTemplate.Meta variant(final Input in, final String type, final List<BuildingTemplate.Meta> members) {
        if (members.size() == 1) {
            return members.getFirst();
        }
        final List<BuildingTemplate.Meta> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(BuildingTemplate.Meta::id));
        final long h = mix(type.hashCode() * 0x9E3779B97F4A7C15L ^ in.variantSeed());
        final int index = (int) Math.floorMod(h + in.count(type), (long) sorted.size());
        return sorted.get(index);
    }

    private static long mix(final long v) {
        long z = v;
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /**
     * Template id to build for a requested building type (base id or any variant id): the look variant the planner
     * would pick now among the allowed templates of that type; the requested id when none of them is allowed.
     */
    public static String variantFor(final Input in, final String type) {
        final String base = in.baseOf(type);
        final List<BuildingTemplate.Meta> members = new ArrayList<>();
        for (final BuildingTemplate.Meta m : in.catalog()) {
            if (plannable(m) && m.baseType().equals(base) && allowed(in, m)) {
                members.add(m);
            }
        }
        return members.isEmpty() ? type : variant(in, base, members).id();
    }

    /** Best housing: highest tier first, then most beds, then cheapest; affordable or not. */
    private static List<BuildingTemplate.Meta> housingOptions(final Input in) {
        final List<BuildingTemplate.Meta> list = of(in, m -> m.category().equals("housing") && m.declaredBeds() > 0);
        list.sort(Comparator.comparingInt((BuildingTemplate.Meta m) -> -m.tier())
            .thenComparingInt(m -> -m.declaredBeds()).thenComparingLong(BuildingTemplate.Meta::cost));
        return list;
    }

    /** First affordable of the options, or null. */
    private static BuildingTemplate.Meta firstAffordable(final Input in, final List<BuildingTemplate.Meta> options) {
        for (final BuildingTemplate.Meta m : options) {
            if (affordable(in, m)) {
                return m;
            }
        }
        return null;
    }

    /** The town's well: function well, or an infrastructure template of the centre zone (custom templates). */
    static boolean isWell(final BuildingTemplate.Meta m) {
        return m.function() == pl.medivalrpg.ludnosc.template.BuildingFunction.WELL
            || m.category().equals("infrastructure") && m.zone().equals("center");
    }

    /** Best housing allowed now (affordable or not), best first — the redesign fills quarters with it. */
    public static List<BuildingTemplate.Meta> housing(final Input in) {
        return housingOptions(in);
    }

    public static Choice choose(final Input in) {
        // 1. the well (town centre) — only a real well: bridges, stations, car parks are infrastructure too, but never
        //    the first building of a town (a bridge without a river to cross would block the planner for good)
        final List<BuildingTemplate.Meta> wells = of(in, BuildingChooser::isWell);
        int wellCount = 0;
        for (final BuildingTemplate.Meta m : in.catalog()) {
            if (isWell(m)) {
                wellCount += in.count(m.id());
            }
        }
        if (!wells.isEmpty() && wellCount == 0) {
            final BuildingTemplate.Meta m = firstAffordable(in, wells);
            return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.WELL);
        }
        // 2. housing when nobody has a free bed (homeless or about to be)
        final int freeBeds = in.housing() - in.population();
        final boolean needHousing = in.housing() == 0 || freeBeds < Math.ceil(in.housingFreeRatio() * in.population());
        final List<BuildingTemplate.Meta> homes = housingOptions(in);
        if (needHousing && freeBeds <= 0 && !homes.isEmpty()) {
            final BuildingTemplate.Meta m = firstAffordable(in, homes);
            return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.HOUSING);
        }
        // 2b. fields when the town eats more than it produces
        if (in.foodBalance() < 0 && in.population() >= 4) {
            final List<BuildingTemplate.Meta> farms = of(in, m -> m.category().equals("farm"));
            if (!farms.isEmpty()) {
                final BuildingTemplate.Meta m = firstAffordable(in, farms);
                return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.FARM);
            }
        }
        // 3. war: defence (least built first)
        if (in.atWar()) {
            final List<BuildingTemplate.Meta> def = of(in, m -> m.category().equals("defense"));
            def.sort(Comparator.comparingInt((BuildingTemplate.Meta m) -> in.count(m.id())).thenComparingLong(BuildingTemplate.Meta::cost));
            if (!def.isEmpty()) {
                final BuildingTemplate.Meta m = firstAffordable(in, def);
                return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.DEFENSE);
            }
        }
        // 4. granary
        if (in.foodCapacity() > 0 && in.food() >= in.granaryFullRatio() * in.foodCapacity()) {
            final List<BuildingTemplate.Meta> food = of(in, m -> m.category().equals("food"));
            if (!food.isEmpty()) {
                final BuildingTemplate.Meta m = firstAffordable(in, food);
                return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.FOOD);
            }
        }
        // 5. barracks before a bigger army
        final int barracks = in.countCategory("military");
        if (barracks == 0 && in.population() >= 10 || in.barracksBeds() > 0 && in.soldiers() > 0.8 * in.barracksBeds()) {
            final List<BuildingTemplate.Meta> mil = of(in, m -> m.category().equals("military"));
            if (!mil.isEmpty()) {
                final BuildingTemplate.Meta m = firstAffordable(in, mil);
                return m == null ? new Choice(null, Reason.NO_FUNDS) : new Choice(m.id(), Reason.MILITARY);
            }
        }
        // 6. growth: one of each, in order of usefulness — while the town has fewer of them than its level warrants;
        //    otherwise free beds (< housingFreeRatio) come first
        boolean tooExpensive = false;
        final List<String> order = List.of("economy", "civic", "religious", "representative", "food", "defense");
        int growthBuilt = 0;
        for (final String category : order) {
            growthBuilt += in.countCategory(category);
        }
        BuildingTemplate.Meta growth = null;
        for (final String category : order) {
            final List<BuildingTemplate.Meta> list = of(in, m -> m.category().equals(category) && in.count(m.id()) == 0);
            list.sort(Comparator.comparingInt(BuildingTemplate.Meta::tier).thenComparingLong(BuildingTemplate.Meta::cost));
            for (final BuildingTemplate.Meta m : list) {
                if (affordable(in, m)) {
                    growth = m;
                    break;
                }
                tooExpensive = true;
            }
            if (growth != null) {
                break;
            }
        }
        final boolean growthFirst = growth != null && growthBuilt < 2 * in.level() + 2;
        if (needHousing && !homes.isEmpty() && !growthFirst) {
            final BuildingTemplate.Meta m = firstAffordable(in, homes);
            if (m != null) {
                return new Choice(m.id(), Reason.HOUSING);
            }
            if (growth == null) {
                return new Choice(null, Reason.NO_FUNDS);
            }
        }
        if (growth != null) {
            return new Choice(growth.id(), Reason.GROWTH);
        }
        // 7. more housing so the town keeps growing
        if (!homes.isEmpty() && freeBeds < Math.max(2, in.population() / 2)) {
            final BuildingTemplate.Meta m = firstAffordable(in, homes);
            if (m != null) {
                return new Choice(m.id(), Reason.MORE_HOUSING);
            }
            tooExpensive = true;
        }
        return new Choice(null, tooExpensive ? Reason.NO_FUNDS : Reason.NOTHING_NEEDED);
    }
}
