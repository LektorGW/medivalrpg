// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Typed view of the {@code zlecenia:} and {@code reputacja:} sections of config.yml (immutable). Materials stay
 * strings here (resolved to {@code Material} only by the Bukkit glue), so the class is testable without a server.
 * Invalid values are clamped, never fatal.
 */
public final class QuestConfig {

    /**
     * One deliverable item type.
     *
     * @param material Bukkit material name, e.g. {@code WHEAT}
     * @param name     Polish name for chat and prompt (plural/collective form, e.g. "pszenica")
     * @param min      smallest amount asked
     * @param max      largest amount asked
     * @param price    reward in copper for every {@code per} items
     * @param per      how many items {@code price} pays for (merchant-style, e.g. 10 md per 16 wheat)
     */
    public record PoolItem(String material, String name, int min, int max, long price, int per) {

        /** Reward for {@code amount} items, rounded down (at least 0). */
        public long rewardFor(final int amount) {
            return Math.max(0, amount) * this.price / Math.max(1, this.per);
        }
    }

    /**
     * A resource a country may lack ({@code zlecenia.surowce.resources.<key>}).
     *
     * @param threshold the country lacks it when its store (Ludnosc {@code resources}) is below this; for
     *                  {@code zywnosc} the granary/hunger state decides
     */
    public record Resource(String key, String name, long threshold, List<PoolItem> pool) {
    }

    public final boolean enabled;
    public final int offersPerDay;
    public final Map<QuestType, Integer> weights;
    public final double premiumMultiplier;
    public final int premiumReputationBonus;

    public final int maxActive;
    public final int maxCompletedPerDay;
    public final int maxPerCountryPerDay;
    public final int acceptCooldownSeconds;
    public final int afterFailMinutes;
    public final boolean acceptAnywhere;
    public final int capitalRadiusChunks;

    /**
     * Always false since the closed world pool (2026-10-29): rewards are covered by the NPC treasury through an escrow.
     * {@code reward.create-money: true} in an old config is ignored with a warning ({@link #createMoneyIgnored}).
     */
    public final boolean createMoney;
    public final boolean createMoneyIgnored;
    /** Days of the NPC army's wages kept out of quest rewards, and the minimum kept (copper). */
    public final double reserveDaysOfWages;
    public final long reserveMin;
    public final long minReward;
    public final long maxReward;
    public final double treasuryPercentPerQuest;
    public final double treasuryPercentPerDay;
    public final double wealthEffect;

    public final int deliveryMinutes;
    public final int deliveryReputation;
    public final long deliveryBonus;
    public final List<PoolItem> deliveryPool;

    public final int resourceMinutes;
    public final int resourceReputation;
    public final long resourceBonus;
    public final double needMultiplier;
    public final Map<String, Resource> resources;

    public final int transportReputation;
    public final int transportBaseMinutes;
    public final int transportMinutesPer100;
    public final int transportMinDistance;
    public final int transportMaxDistance;
    public final long transportBaseReward;
    public final long transportRewardPer100;
    public final String cargoMaterial;
    public final List<String> transportGoods;

    // reputation
    public final int repRefuse;
    public final int repAbandon;
    public final int repFail;
    public final int repTributePaid;
    public final int repTributeRefused;
    public final int repDailyGainCap;
    public final Map<RepTier, Double> rewardMultiplier;
    public final Map<RepTier, Double> giftMultiplier;

    public QuestConfig(final ConfigurationSection root, final Logger logger) {
        final ConfigurationSection q = section(root, "zlecenia");
        this.enabled = q.getBoolean("enabled", true);
        this.offersPerDay = clampInt(q.getInt("offers-per-day", 4), 1, 12);
        final Map<QuestType, Integer> w = new EnumMap<>(QuestType.class);
        for (final QuestType t : QuestType.values()) {
            w.put(t, clampInt(q.getInt("weights." + t.key(), t == QuestType.TRANSPORT ? 25 : 40), 0, 1000));
        }
        this.weights = Map.copyOf(w);
        this.premiumMultiplier = clampD(q.getDouble("premium-multiplier", 1.5), 1.0, 5.0);
        this.premiumReputationBonus = clampInt(q.getInt("premium-reputation-bonus", 2), 0, 50);

        this.maxActive = clampInt(q.getInt("limits.max-active", 2), 1, 10);
        this.maxCompletedPerDay = clampInt(q.getInt("limits.max-completed-per-day", 6), 1, 100);
        this.maxPerCountryPerDay = clampInt(q.getInt("limits.max-per-country-per-day", 3), 1, 100);
        this.acceptCooldownSeconds = clampInt(q.getInt("cooldowns.accept-seconds", 10), 0, 3600);
        this.afterFailMinutes = clampInt(q.getInt("cooldowns.after-fail-minutes", 30), 0, 24 * 60);
        this.acceptAnywhere = q.getBoolean("accept-anywhere", false);
        this.capitalRadiusChunks = clampInt(q.getInt("capital-radius-chunks", 2), 0, 16);

        this.createMoneyIgnored = q.getBoolean("reward.create-money", false);
        this.createMoney = false;
        if (this.createMoneyIgnored && logger != null) {
            logger.warning("zlecenia.reward.create-money: true jest ignorowane — zamknięta pula pieniędzy; nagrody tylko z depozytu skarbca kraju");
        }
        this.reserveDaysOfWages = clampD(q.getDouble("reward.reserve-days-of-wages", 2), 0, 60);
        this.reserveMin = clampL(q.getLong("reward.reserve-min", 500), 0, 1_000_000_000L);
        this.minReward = clampL(q.getLong("reward.min", 20), 1, 1_000_000_000L);
        this.maxReward = clampL(q.getLong("reward.max", 2000), this.minReward, 1_000_000_000L);
        this.treasuryPercentPerQuest = clampD(q.getDouble("reward.max-treasury-percent-per-quest", 3), 0, 100);
        this.treasuryPercentPerDay = clampD(q.getDouble("reward.max-treasury-percent-per-day", 10), 0, 100);
        this.wealthEffect = clampD(q.getDouble("reward.wealth-effect", 0.2), 0, 0.9);

        this.deliveryMinutes = clampInt(q.getInt("dostawa.time-limit-minutes", 120), 5, 7 * 24 * 60);
        this.deliveryReputation = clampInt(q.getInt("dostawa.reputation", 4), 0, 50);
        this.deliveryBonus = clampL(q.getLong("dostawa.bonus", 30), 0, 1_000_000_000L);
        this.deliveryPool = pool(q.getMapList("dostawa.pool"), logger, "zlecenia.dostawa.pool");

        this.resourceMinutes = clampInt(q.getInt("surowce.time-limit-minutes", 180), 5, 7 * 24 * 60);
        this.resourceReputation = clampInt(q.getInt("surowce.reputation", 5), 0, 50);
        this.resourceBonus = clampL(q.getLong("surowce.bonus", 40), 0, 1_000_000_000L);
        this.needMultiplier = clampD(q.getDouble("surowce.need-multiplier", 1.3), 1.0, 5.0);
        final Map<String, Resource> res = new LinkedHashMap<>();
        final ConfigurationSection rs = q.getConfigurationSection("surowce.resources");
        if (rs != null) {
            for (final String key : rs.getKeys(false)) {
                final ConfigurationSection r = rs.getConfigurationSection(key);
                if (r == null) {
                    continue;
                }
                final List<PoolItem> pool = pool(r.getMapList("pool"), logger, "zlecenia.surowce.resources." + key);
                if (!pool.isEmpty()) {
                    final String k = key.toLowerCase(Locale.ROOT);
                    res.put(k, new Resource(k, r.getString("name", k), Math.max(0, r.getLong("threshold", 0)), pool));
                }
            }
        }
        this.resources = java.util.Collections.unmodifiableMap(res);

        this.transportReputation = clampInt(q.getInt("transport.reputation", 6), 0, 50);
        this.transportBaseMinutes = clampInt(q.getInt("transport.base-minutes", 20), 5, 24 * 60);
        this.transportMinutesPer100 = clampInt(q.getInt("transport.minutes-per-100-blocks", 4), 0, 600);
        this.transportMinDistance = clampInt(q.getInt("transport.min-distance", 150), 0, 1_000_000);
        this.transportMaxDistance = clampInt(q.getInt("transport.max-distance", 3000), this.transportMinDistance, 10_000_000);
        this.transportBaseReward = clampL(q.getLong("transport.base-reward", 60), 0, 1_000_000_000L);
        this.transportRewardPer100 = clampL(q.getLong("transport.reward-per-100-blocks", 25), 0, 1_000_000_000L);
        this.cargoMaterial = q.getString("transport.cargo-material", "BARREL").trim().toUpperCase(Locale.ROOT);
        final List<String> goods = new ArrayList<>();
        for (final String g : q.getStringList("transport.goods")) {
            if (g != null && !g.isBlank()) {
                goods.add(g.trim());
            }
        }
        this.transportGoods = goods.isEmpty() ? List.of("towar") : List.copyOf(goods);

        final ConfigurationSection r = section(root, "reputacja");
        this.repRefuse = clampInt(r.getInt("refuse", -1), -100, 0);
        this.repAbandon = clampInt(r.getInt("abandon", -3), -100, 0);
        this.repFail = clampInt(r.getInt("fail", -4), -100, 0);
        this.repTributePaid = clampInt(r.getInt("tribute-paid", 3), 0, 100);
        this.repTributeRefused = clampInt(r.getInt("tribute-refused", -2), -100, 0);
        this.repDailyGainCap = clampInt(r.getInt("daily-gain-cap", 15), 0, 200);
        this.rewardMultiplier = tierMap(r, "reward-multiplier", new double[] {0, 0.8, 1.0, 1.1, 1.25, 1.5});
        this.giftMultiplier = tierMap(r, "gift-multiplier", new double[] {0, 0.5, 1.0, 1.2, 1.5, 2.0});
    }

    public int timeLimitMinutes(final QuestType type) {
        return type == QuestType.DOSTAWA ? this.deliveryMinutes : this.resourceMinutes;
    }

    public int reputationFor(final QuestType type) {
        return switch (type) {
            case DOSTAWA -> this.deliveryReputation;
            case SUROWCE -> this.resourceReputation;
            case TRANSPORT -> this.transportReputation;
        };
    }

    public double rewardMultiplier(final RepTier tier) {
        return this.rewardMultiplier.getOrDefault(tier, 1.0);
    }

    public double giftMultiplier(final RepTier tier) {
        return this.giftMultiplier.getOrDefault(tier, 1.0);
    }

    private static Map<RepTier, Double> tierMap(final ConfigurationSection r, final String path, final double[] defaults) {
        final Map<RepTier, Double> m = new EnumMap<>(RepTier.class);
        for (final RepTier t : RepTier.values()) {
            m.put(t, clampD(r.getDouble(path + "." + t.key(), defaults[t.ordinal()]), 0, 10));
        }
        return Map.copyOf(m);
    }

    private static List<PoolItem> pool(final List<Map<?, ?>> raw, final Logger logger, final String where) {
        final List<PoolItem> out = new ArrayList<>();
        for (final Map<?, ?> m : raw) {
            final Object mat = field(m, "material");
            if (mat == null || String.valueOf(mat).isBlank()) {
                if (logger != null) {
                    logger.warning("Pozycja bez material w " + where + " — pomijam");
                }
                continue;
            }
            final String material = String.valueOf(mat).trim().toUpperCase(Locale.ROOT);
            final String name = field(m, "name") == null ? material.toLowerCase(Locale.ROOT).replace('_', ' ') : String.valueOf(field(m, "name"));
            final int min = clampInt(num(field(m, "min"), 16), 1, 2304);
            final int max = clampInt(num(field(m, "max"), min), min, 2304);
            final long price = clampL(num(field(m, "price"), 1), 0, 1_000_000L);
            final int per = clampInt(num(field(m, "per"), 1), 1, 2304);
            out.add(new PoolItem(material, name, min, max, price, per));
        }
        return List.copyOf(out);
    }

    /** Value of a key in a YAML map entry (helper keeps map lookups apart from message lookups in tests). */
    private static Object field(final Map<?, ?> m, final String key) {
        return m.get(key);
    }

    private static int num(final Object o, final int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (final NumberFormatException ignored) {
                // default
            }
        }
        return def;
    }

    private static ConfigurationSection section(final ConfigurationSection root, final String path) {
        final ConfigurationSection s = root.getConfigurationSection(path);
        return s != null ? s : new org.bukkit.configuration.MemoryConfiguration();
    }

    private static int clampInt(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampL(final long v, final long min, final long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampD(final double v, final double min, final double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }
}
