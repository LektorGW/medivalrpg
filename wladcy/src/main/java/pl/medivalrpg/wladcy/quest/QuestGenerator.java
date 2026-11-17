// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds a country's daily board of offers. Deterministic for the same input (seeded by country, day and salt), so
 * a board regenerated after a crash looks the same. Pure, no Bukkit.
 */
public final class QuestGenerator {

    /** Granary state from Ludnosc; {@code null} when unknown. */
    public record Food(long food, long capacity, long balance, int hungryDays) {
    }

    /**
     * A possible destination of a transport quest.
     *
     * @param distance blocks between the two capitals (same world)
     * @param ally     allied with the quest giver (picked twice as often)
     */
    public record Target(String id, String name, int distance, boolean ally) {
    }

    /**
     * @param wealth  NPC personality 0–100 (Armia), 50 when unknown
     * @param needs   resource keys the country lacks, most urgent first ({@link #needs}); empty = unknown
     * @param targets transport destinations already filtered by distance and relation
     * @param salt    0 normally; an admin refresh changes it to get a different board the same day
     */
    public record Input(String countryId, String day, int wealth, List<String> needs, List<Target> targets, long salt) {
    }

    private QuestGenerator() {
    }

    /**
     * Resource keys the country lacks, most urgent first: Ludnosc stores below the configured threshold (ratio
     * ascending), and {@code zywnosc} when citizens are hungry, the food balance is negative or the granary is
     * below a quarter. Only keys present in config count. Missing, null or negative stock values are unknown,
     * not evidence of an empty store (older Ludnosc providers return an empty map).
     */
    public static List<String> needs(final Map<String, Long> stock, final Food food, final Map<String, QuestConfig.Resource> resources) {
        final List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (final QuestConfig.Resource r : resources.values()) {
            if ("zywnosc".equals(r.key())) {
                if (food == null) {
                    continue;
                }
                final double fill = food.capacity() <= 0 ? 1.0 : (double) Math.max(0, food.food()) / food.capacity();
                if (food.hungryDays() > 0) {
                    scored.add(Map.entry(r.key(), -1.0));
                } else if (food.balance() < 0 || fill < 0.25) {
                    scored.add(Map.entry(r.key(), Math.min(fill, 0.99)));
                }
                continue;
            }
            if (r.threshold() <= 0 || stock == null) {
                continue;
            }
            final Long have = stock.get(r.key());
            if (have == null || have < 0) {
                continue;
            }
            if (have < r.threshold()) {
                scored.add(Map.entry(r.key(), (double) have / r.threshold()));
            }
        }
        scored.sort(Map.Entry.comparingByValue());
        return scored.stream().map(Map.Entry::getKey).toList();
    }

    static long seed(final Input in) {
        long h = 1125899906842597L;
        for (final String s : new String[] {in.countryId(), in.day()}) {
            for (int i = 0; i < s.length(); i++) {
                h = 31 * h + s.charAt(i);
            }
        }
        return h ^ (in.salt() * 0x9E3779B97F4A7C15L);
    }

    public static List<QuestData.Offer> generate(final Input in, final QuestConfig cfg, final Supplier<String> ids) {
        final Random rng = new Random(seed(in));
        final List<QuestType> available = new ArrayList<>();
        final Map<QuestType, Integer> weights = new EnumMap<>(cfg.weights);
        if (cfg.deliveryPool.isEmpty()) {
            weights.put(QuestType.DOSTAWA, 0);
        }
        if (cfg.resources.isEmpty()) {
            weights.put(QuestType.SUROWCE, 0);
        }
        if (in.targets().isEmpty()) {
            weights.put(QuestType.TRANSPORT, 0);
        }
        for (final QuestType t : QuestType.values()) {
            if (weights.getOrDefault(t, 0) > 0) {
                available.add(t);
            }
        }
        final List<QuestData.Offer> out = new ArrayList<>();
        if (available.isEmpty()) {
            return out;
        }
        // Every available type once when the board has room, the rest by weight.
        final List<QuestType> plan = new ArrayList<>();
        if (cfg.offersPerDay >= available.size()) {
            plan.addAll(available);
        }
        while (plan.size() < cfg.offersPerDay) {
            plan.add(weighted(rng, weights, available));
        }
        final Set<String> usedMaterials = new HashSet<>();
        int needIndex = 0;
        for (final QuestType t : plan) {
            final QuestData.Offer o = switch (t) {
                case DOSTAWA -> delivery(rng, cfg, usedMaterials);
                case SUROWCE -> resource(rng, cfg, in.needs(), needIndex++, usedMaterials);
                case TRANSPORT -> transport(rng, cfg, in.targets());
            };
            if (o == null) {
                continue;
            }
            o.id = ids.get();
            o.country = in.countryId();
            o.type = t.key();
            o.reputation = cfg.reputationFor(t);
            o.baseReward = QuestRules.clampReward(Math.round(o.baseReward * QuestRules.wealthFactor(in.wealth(), cfg.wealthEffect)), cfg);
            out.add(o);
        }
        if (cfg.premiumMultiplier > 1.0 && out.size() >= 2) {
            final QuestData.Offer p = out.get(rng.nextInt(out.size()));
            p.premium = true;
            p.baseReward = QuestRules.clampReward(Math.round(p.baseReward * cfg.premiumMultiplier), cfg);
            p.reputation += cfg.premiumReputationBonus;
        }
        return out;
    }

    private static QuestType weighted(final Random rng, final Map<QuestType, Integer> weights, final List<QuestType> available) {
        int total = 0;
        for (final QuestType t : available) {
            total += weights.getOrDefault(t, 0);
        }
        int r = rng.nextInt(Math.max(1, total));
        for (final QuestType t : available) {
            r -= weights.getOrDefault(t, 0);
            if (r < 0) {
                return t;
            }
        }
        return available.get(available.size() - 1);
    }

    private static QuestConfig.PoolItem pick(final Random rng, final List<QuestConfig.PoolItem> pool, final Set<String> used) {
        final List<QuestConfig.PoolItem> fresh = pool.stream().filter(p -> !used.contains(p.material())).toList();
        final List<QuestConfig.PoolItem> from = fresh.isEmpty() ? pool : fresh;
        final QuestConfig.PoolItem item = from.get(rng.nextInt(from.size()));
        used.add(item.material());
        return item;
    }

    /** Amount in [min, max], rounded to a multiple of 4 when the range allows it (tidier numbers). */
    static int amount(final Random rng, final QuestConfig.PoolItem item) {
        final int a = item.min() + rng.nextInt(item.max() - item.min() + 1);
        final int rounded = Math.round(a / 4f) * 4;
        return rounded >= item.min() && rounded <= item.max() ? rounded : a;
    }

    private static QuestData.Offer delivery(final Random rng, final QuestConfig cfg, final Set<String> used) {
        final QuestConfig.PoolItem item = pick(rng, cfg.deliveryPool, used);
        final QuestData.Offer o = new QuestData.Offer();
        o.material = item.material();
        o.itemName = item.name();
        o.amount = amount(rng, item);
        o.baseReward = item.rewardFor(o.amount) + cfg.deliveryBonus;
        o.timeLimitMinutes = cfg.deliveryMinutes;
        return o;
    }

    private static QuestData.Offer resource(final Random rng, final QuestConfig cfg, final List<String> needs, final int index,
                                            final Set<String> used) {
        final List<String> known = needs.stream().filter(cfg.resources::containsKey).toList();
        final QuestConfig.Resource res;
        final boolean urgent;
        if (!known.isEmpty()) {
            // The two most urgent needs take turns; a third resource quest goes back to the most urgent.
            res = cfg.resources.get(known.get(index % Math.min(2, known.size())));
            urgent = true;
        } else {
            final List<QuestConfig.Resource> all = List.copyOf(cfg.resources.values());
            res = all.get(rng.nextInt(all.size()));
            urgent = false;
        }
        final QuestConfig.PoolItem item = pick(rng, res.pool(), used);
        final QuestData.Offer o = new QuestData.Offer();
        o.material = item.material();
        o.itemName = item.name();
        o.amount = amount(rng, item);
        o.need = res.key();
        o.urgent = urgent;
        o.baseReward = Math.round(item.rewardFor(o.amount) * (urgent ? cfg.needMultiplier : 1.0)) + cfg.resourceBonus;
        o.timeLimitMinutes = cfg.resourceMinutes;
        return o;
    }

    private static QuestData.Offer transport(final Random rng, final QuestConfig cfg, final List<Target> targets) {
        final List<Target> bag = new ArrayList<>();
        for (final Target t : targets) {
            bag.add(t);
            if (t.ally()) {
                bag.add(t);
            }
        }
        final Target t = bag.get(rng.nextInt(bag.size()));
        final QuestData.Offer o = new QuestData.Offer();
        o.material = cfg.cargoMaterial;
        o.itemName = cfg.transportGoods.get(rng.nextInt(cfg.transportGoods.size()));
        o.amount = 1;
        o.targetCountry = t.id();
        o.targetName = t.name();
        o.distance = t.distance();
        o.baseReward = QuestRules.transportReward(t.distance(), cfg);
        o.timeLimitMinutes = QuestRules.transportMinutes(t.distance(), cfg);
        return o;
    }
}
