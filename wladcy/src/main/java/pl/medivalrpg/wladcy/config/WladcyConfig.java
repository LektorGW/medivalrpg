// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.wladcy.quest.QuestConfig;

/** Typed view of config.yml (immutable). Invalid values are clamped and logged, never fatal. */
public final class WladcyConfig {

    public enum Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }

    // api
    public final String configApiKey;
    public final String model;
    public final Effort effort;
    public final long maxTokens;
    public final boolean fallbacks;
    public final int timeoutSeconds;
    public final int maxRetries;
    public final int threads;
    public final int maxToolRounds;

    // limits
    public final int perPlayerPerDay;
    public final int cooldownSeconds;
    public final int maxInputChars;
    public final double dailyBudgetUsd;
    public final double monthlyBudgetUsd;

    // conversation
    public final double startRadius;
    public final double maxDistance;
    public final int idleSeconds;
    public final double rpRadius;
    public final int maxMessages;
    public final int replyMaxChars;

    // display
    public final boolean textDisplay;
    public final int displaySeconds;
    public final int displayMaxChars;

    // memory
    public final int memoryExchanges;
    public final int memoryNotes;
    public final int memoryReplyChars;

    // diplomacy / economy
    public final Rank diplomacyRank;
    public final long tributeMax;
    public final int tributeExpireMinutes;
    public final int tributeSympathyPerGold;
    public final int tributeRefuseSympathy;
    public final double giftTreasuryPercent;
    public final long giftMaxPerPlayer;

    // style
    /** {@code style.freedom} 0–100: how lively/coarse the speech is and how freely mature RP themes are played. */
    public final int styleFreedom;

    // war
    public final boolean allowWar;
    public final int warCooldownHours;
    public final int warMinAggression;

    /** {@code zlecenia:} and {@code reputacja:} — quests of NPC rulers and player reputation. */
    public final QuestConfig quests;

    private final Map<String, ModelPrice> pricing;
    private final ModelPrice defaultPrice;

    public WladcyConfig(final ConfigurationSection c, final Logger logger) {
        this.configApiKey = c.getString("api-key", "").trim();
        this.model = nonBlank(c.getString("api.model"), "claude-opus-5-5");
        this.effort = parseEffort(c.getString("api.effort", "low"), logger);
        this.maxTokens = clamp(c.getLong("api.max-tokens", 2048), 256, 64_000);
        this.fallbacks = c.getBoolean("api.fallbacks", true);
        this.timeoutSeconds = (int) clamp(c.getInt("api.timeout-seconds", 30), 5, 300);
        this.maxRetries = (int) clamp(c.getInt("api.max-retries", 1), 0, 5);
        this.threads = (int) clamp(c.getInt("api.threads", 2), 1, 8);
        this.maxToolRounds = (int) clamp(c.getInt("api.max-tool-rounds", 3), 1, 8);

        this.perPlayerPerDay = (int) clamp(c.getInt("limits.per-player-per-day", 40), 0, 10_000);
        this.cooldownSeconds = (int) clamp(c.getInt("limits.cooldown-seconds", 4), 0, 3600);
        this.maxInputChars = (int) clamp(c.getInt("limits.max-input-chars", 300), 20, 2000);
        this.dailyBudgetUsd = Math.max(0, c.getDouble("limits.daily-budget-usd", 3.00));
        this.monthlyBudgetUsd = Math.max(0, c.getDouble("limits.monthly-budget-usd", 0));

        this.startRadius = Math.max(1, c.getDouble("conversation.start-radius", 6));
        this.maxDistance = Math.max(this.startRadius, c.getDouble("conversation.max-distance", 8));
        this.idleSeconds = (int) clamp(c.getInt("conversation.idle-seconds", 180), 20, 3600);
        this.rpRadius = Math.max(0, c.getDouble("conversation.rp-radius", 8));
        this.maxMessages = (int) clamp(c.getInt("conversation.max-messages", 20), 1, 200);
        this.replyMaxChars = (int) clamp(c.getInt("conversation.reply-max-chars", 600), 50, 4000);

        this.textDisplay = c.getBoolean("display.text-display", true);
        this.displaySeconds = (int) clamp(c.getInt("display.seconds", 6), 1, 60);
        this.displayMaxChars = (int) clamp(c.getInt("display.max-chars", 90), 10, 300);

        this.memoryExchanges = (int) clamp(c.getInt("memory.exchanges", 6), 0, 50);
        this.memoryNotes = (int) clamp(c.getInt("memory.notes", 5), 0, 50);
        this.memoryReplyChars = (int) clamp(c.getInt("memory.reply-chars", 300), 50, 2000);

        this.diplomacyRank = parseRank(c.getString("diplomacy.min-rank", "wladca"), logger);
        this.tributeMax = clamp(c.getLong("tribute.max-amount", 5000), 1, Long.MAX_VALUE / 4);
        this.tributeExpireMinutes = (int) clamp(c.getInt("tribute.expire-minutes", 5), 1, 1440);
        this.tributeSympathyPerGold = (int) clamp(c.getInt("tribute.sympathy-per-gold", 2), 0, 100);
        this.tributeRefuseSympathy = (int) clamp(c.getInt("tribute.refuse-sympathy", -10), -100, 0);
        this.giftTreasuryPercent = Math.max(0, Math.min(100, c.getDouble("gift.max-treasury-percent-per-day", 5)));
        this.giftMaxPerPlayer = clamp(c.getLong("gift.max-per-player-per-day", 1000), 0, Long.MAX_VALUE / 4);

        this.styleFreedom = (int) clamp(c.getInt("style.freedom", 75), 0, 100);
        this.allowWar = c.getBoolean("allow-war", true);
        this.warCooldownHours = (int) clamp(c.getInt("war.cooldown-hours", 24), 0, 24 * 365);
        this.warMinAggression = (int) clamp(c.getInt("war.min-aggression", 40), 0, 100);
        this.quests = new QuestConfig(c, logger);

        final Map<String, ModelPrice> prices = new HashMap<>();
        final ConfigurationSection p = c.getConfigurationSection("pricing");
        ModelPrice def = ModelPrice.OPUS_5_5;
        if (p != null) {
            for (final String key : p.getKeys(false)) {
                final ConfigurationSection e = p.getConfigurationSection(key);
                if (e == null) {
                    continue;
                }
                final ModelPrice price = new ModelPrice(e.getDouble("input"), e.getDouble("output"), e.getDouble("cache-read"), e.getDouble("cache-write"));
                if ("default".equals(key)) {
                    def = price;
                } else {
                    prices.put(key.toLowerCase(Locale.ROOT), price);
                }
            }
        }
        this.pricing = Map.copyOf(prices);
        this.defaultPrice = def;
    }

    /**
     * Price list for a model id. Exact id first, then the longest configured prefix (dated ids such as
     * {@code claude-opus-5-5-20261001}), then {@code pricing.default}.
     */
    public ModelPrice price(final String modelId) {
        if (modelId == null) {
            return this.defaultPrice;
        }
        final String id = modelId.toLowerCase(Locale.ROOT);
        final ModelPrice exact = this.pricing.get(id);
        if (exact != null) {
            return exact;
        }
        String best = null;
        for (final String k : this.pricing.keySet()) {
            if (id.startsWith(k) && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        return best == null ? this.defaultPrice : this.pricing.get(best);
    }

    /**
     * Server-side refusal fallbacks ({@code fallbacks: "default"}) are offered for the Opus/Fable line on the Claude
     * API; Claude Haiku 5.5 has no server-side fallback, so it is never sent there.
     */
    public boolean fallbacksFor(final String modelId) {
        return this.fallbacks && modelId != null && !modelId.toLowerCase(Locale.ROOT).contains("haiku");
    }

    static Effort parseEffort(final String raw, final Logger logger) {
        try {
            return Effort.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final RuntimeException e) {
            if (logger != null) {
                logger.warning("Nieznany api.effort: " + raw + " — używam low");
            }
            return Effort.LOW;
        }
    }

    static Rank parseRank(final String raw, final Logger logger) {
        final String r = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "doradca", "advisor" -> Rank.ADVISOR;
            case "obywatel", "citizen" -> Rank.CITIZEN;
            case "wladca", "władca", "ruler" -> Rank.RULER;
            default -> {
                if (logger != null) {
                    logger.warning("Nieznana diplomacy.min-rank: " + raw + " — używam wladca");
                }
                yield Rank.RULER;
            }
        };
    }

    private static String nonBlank(final String s, final String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }

    private static long clamp(final long v, final long min, final long max) {
        return Math.max(min, Math.min(max, v));
    }
}
