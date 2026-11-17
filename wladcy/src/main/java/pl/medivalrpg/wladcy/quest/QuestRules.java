// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.List;
import java.util.Optional;

/**
 * Pure quest rules: rewards, time limits, who may take which offer. Used by the command path, the ruler's tools and
 * the tests alike. No Bukkit.
 */
public final class QuestRules {

    /**
     * Why an offer cannot be accepted. {@link #key()} selects {@code quest-deny-<key>} in config.yml (chat);
     * {@link #forModel()} is the Polish text for the ruler's tool result.
     */
    public enum Denial {
        DISABLED("disabled", "Zlecenia są wyłączone na tym serwerze."),
        ENEMY("enemy", "Rozmówca jest wrogiem twojego kraju (reputacja Wróg) — nie powierzasz mu żadnych zleceń."),
        TIER_TYPE("tier-type", "Przy reputacji Nieufny rozmówca może dostać tylko prostą dostawę."),
        PREMIUM("premium", "To zaszczytne zlecenie wymaga reputacji co najmniej Znajomy."),
        MAX_ACTIVE("max-active", "Rozmówca ma już najwięcej zleceń naraz — niech najpierw je skończy."),
        DAILY("daily", "Rozmówca wykonał dziś już najwięcej zleceń. Niech wróci jutro."),
        COUNTRY_DAILY("country-daily", "Rozmówca wykonał dziś dla ciebie już najwięcej zleceń. Niech wróci jutro."),
        USED("used", "Rozmówca już dziś przyjął albo odrzucił to zlecenie."),
        COOLDOWN("cooldown", "Rozmówca niedawno zawiódł przy twoim zleceniu — na razie nic mu nie powierzasz."),
        TOO_FAST("too-fast", "Rozmówca przed chwilą przyjął zlecenie. Niech chwilę odczeka."),
        NO_FUNDS("no-funds", "Skarbiec twojego kraju nie udźwignie dziś takiej nagrody — więcej zleceń nie dasz."),
        ACTIVE_HERE("active-here", "Rozmówca już wykonuje to zlecenie."),
        NEED_CHANGED("need-changed", "Pilne zapotrzebowanie nie jest już potwierdzone. Nie proponuj ani nie przyjmuj tej oferty; wybierz inne zlecenie."),
        SUPPLY_UNAVAILABLE("supply-unavailable", "Nowe zlecenia surowcowe wymagają dostępnej obsługi rzeczywistych magazynów Ludnosc."),
        BANK_UNAVAILABLE("bank-unavailable", "Nowe zlecenia z depozytem wymagają obsługi trwałego zapisu banku Skarbiec.");

        private final String key;
        private final String forModel;

        Denial(final String key, final String forModel) {
            this.key = key;
            this.forModel = forModel;
        }

        public String key() {
            return this.key;
        }

        public String forModel() {
            return this.forModel;
        }
    }

    /**
     * Facts about one player and one offer at the moment of acceptance.
     *
     * @param cooldownUntil epoch millis until which this country gives the player nothing (0 = none)
     * @param reward        the player's reward after multipliers and caps ({@link #quote})
     */
    public record AcceptFacts(boolean enabled, RepTier tier, QuestType type, boolean premium, int active, boolean alreadyActive,
                              int completedToday, int completedForCountryToday, boolean usedToday, long cooldownUntil,
                              long lastAcceptAt, long now, RewardQuote reward) {

        /** Same facts without the short accept cooldown (for listing what the ruler may offer). */
        public AcceptFacts withoutTiming() {
            return new AcceptFacts(this.enabled, this.tier, this.type, this.premium, this.active, this.alreadyActive, this.completedToday,
                this.completedForCountryToday, this.usedToday, this.cooldownUntil, 0, this.now, this.reward);
        }
    }

    /** What a player would get for an offer now. {@code affordable} false = the treasury cannot carry it. */
    public record RewardQuote(long reward, boolean affordable) {
    }

    private QuestRules() {
    }

    /** First reason the offer cannot be accepted, or empty. Order matters: the most useful message first. */
    public static Optional<Denial> canAccept(final AcceptFacts f, final QuestConfig cfg) {
        if (!f.enabled()) {
            return Optional.of(Denial.DISABLED);
        }
        final Optional<Denial> v = visible(f.tier(), f.type(), f.premium());
        if (v.isPresent()) {
            return v;
        }
        if (f.alreadyActive()) {
            return Optional.of(Denial.ACTIVE_HERE);
        }
        if (f.usedToday()) {
            return Optional.of(Denial.USED);
        }
        if (f.cooldownUntil() > f.now()) {
            return Optional.of(Denial.COOLDOWN);
        }
        if (f.active() >= cfg.maxActive) {
            return Optional.of(Denial.MAX_ACTIVE);
        }
        if (f.completedToday() >= cfg.maxCompletedPerDay) {
            return Optional.of(Denial.DAILY);
        }
        if (f.completedForCountryToday() >= cfg.maxPerCountryPerDay) {
            return Optional.of(Denial.COUNTRY_DAILY);
        }
        if (f.lastAcceptAt() > 0 && f.now() - f.lastAcceptAt() < cfg.acceptCooldownSeconds * 1000L) {
            return Optional.of(Denial.TOO_FAST);
        }
        if (!f.reward().affordable()) {
            return Optional.of(Denial.NO_FUNDS);
        }
        return Optional.empty();
    }

    /**
     * Revalidates a daily offer against live needs before listing, proposing or accepting it. Never used for
     * turn-in: an already accepted contract keeps its amount and reward even when other players replenish stores.
     * Empty live needs also cover an unavailable/older provider; they cannot justify an urgent reward.
     */
    public static Optional<Denial> canAccept(final AcceptFacts f, final QuestConfig cfg, final QuestData.Offer offer,
                                             final List<String> currentNeeds) {
        final Optional<Denial> denied = canAccept(f, cfg);
        if (denied.isPresent()) {
            return denied;
        }
        if (offer.type() == QuestType.SUROWCE && offer.urgent && !currentNeeds.contains(offer.need)) {
            return Optional.of(Denial.NEED_CHANGED);
        }
        return Optional.empty();
    }

    /** Whether the tier lets the player see / take this kind of offer at all. */
    public static Optional<Denial> visible(final RepTier tier, final QuestType type, final boolean premium) {
        if (tier == RepTier.WROG) {
            return Optional.of(Denial.ENEMY);
        }
        if (tier == RepTier.NIEUFNY && type != QuestType.DOSTAWA) {
            return Optional.of(Denial.TIER_TYPE);
        }
        if (premium && !tier.atLeast(RepTier.ZNAJOMY)) {
            return Optional.of(Denial.PREMIUM);
        }
        return Optional.empty();
    }

    /**
     * Reward a player gets for an offer: base × reputation multiplier, then (unless money may be created) at most
     * {@code max-treasury-percent-per-quest} of the treasury and what is left of today's
     * {@code max-treasury-percent-per-day}. Below {@code reward.min} (or above the treasury) it is not affordable.
     */
    public static RewardQuote quote(final long baseReward, final RepTier tier, final QuestConfig cfg, final long treasury, final long paidToday) {
        return quote(baseReward, tier, cfg, treasury, paidToday, false);
    }

    /**
     * Part of an NPC treasury that quest rewards may use: the balance minus {@code reserveDays} of army wages and
     * chunk upkeep, never less than {@code reserveMin} kept. Rewards are then capped by the %-of-treasury limits.
     */
    public static long spendable(final long treasury, final long dailyWages, final long dailyUpkeep, final double reserveDays, final long reserveMin) {
        final long reserve = Math.max(Math.max(0, reserveMin),
            Math.round((Math.max(0, dailyWages) + Math.max(0, dailyUpkeep)) * Math.max(0, reserveDays)));
        return Math.max(0, treasury - reserve);
    }

    /** Resource warehouse deliveries always require existing treasury funds, regardless of the legacy mint switch. */
    public static RewardQuote quoteFunded(final long baseReward, final RepTier tier, final QuestConfig cfg, final long treasury, final long paidToday) {
        return quote(baseReward, tier, cfg, treasury, paidToday, false);
    }

    private static RewardQuote quote(final long baseReward, final RepTier tier, final QuestConfig cfg, final long treasury,
                                      final long paidToday, final boolean createMoney) {
        final long wanted = Math.round(Math.max(0, baseReward) * cfg.rewardMultiplier(tier));
        if (wanted <= 0) {
            return new RewardQuote(0, false);
        }
        if (createMoney) {
            return new RewardQuote(wanted, true);
        }
        final long t = Math.max(0, treasury);
        final long perQuest = (long) Math.floor(t * cfg.treasuryPercentPerQuest / 100.0);
        final long perDay = (long) Math.floor(t * cfg.treasuryPercentPerDay / 100.0) - Math.max(0, paidToday);
        final long reward = Math.max(0, Math.min(wanted, Math.min(perQuest, perDay)));
        return new RewardQuote(reward, reward >= cfg.minReward && reward <= t);
    }

    /** Wealth (0–100, 50 neutral) changes rewards by ±{@code effect}. */
    public static double wealthFactor(final int wealth, final double effect) {
        final int w = Math.max(0, Math.min(100, wealth));
        return 1.0 + effect * (w - 50) / 50.0;
    }

    /**
     * Reputation (per player) → AI opinion of the player's country (−100..100): half of the applied change, at least ±1,
     * at most ±5 per event, so one player cannot swing a country's diplomacy with a burst of quests or insults.
     */
    public static int opinionDelta(final int appliedReputation) {
        if (appliedReputation == 0) {
            return 0;
        }
        final int half = (int) Math.signum(appliedReputation) * Math.max(1, Math.abs(appliedReputation) / 2);
        return Math.max(-5, Math.min(5, half));
    }

    public static long clampReward(final long v, final QuestConfig cfg) {
        return Math.max(cfg.minReward, Math.min(cfg.maxReward, v));
    }

    /** Transport reward: base + per 100 blocks (rounded down to whole hundreds). */
    public static long transportReward(final int distance, final QuestConfig cfg) {
        return cfg.transportBaseReward + cfg.transportRewardPer100 * (Math.max(0, distance) / 100);
    }

    /** Transport time limit: base + per started 100 blocks. */
    public static int transportMinutes(final int distance, final QuestConfig cfg) {
        return cfg.transportBaseMinutes + cfg.transportMinutesPer100 * ((Math.max(0, distance) + 99) / 100);
    }

    public static long deadline(final long acceptedAt, final int minutes) {
        return acceptedAt + Math.max(1, minutes) * 60_000L;
    }

    public static boolean expired(final QuestData.Active a, final long now) {
        return a.deadline > 0 && now >= a.deadline;
    }

    /** Distance in blocks between the centres of two chunks. */
    public static int chunkDistance(final int ax, final int az, final int bx, final int bz) {
        final long dx = (long) (ax - bx) * 16;
        final long dz = (long) (az - bz) * 16;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt(dx * dx + dz * dz)));
    }

    /** True when chunk (x, z) lies within {@code radius} chunks (square) of the capital chunk. */
    public static boolean nearCapital(final int cx, final int cz, final int capitalX, final int capitalZ, final int radius) {
        return Math.abs(cx - capitalX) <= radius && Math.abs(cz - capitalZ) <= radius;
    }

    /** "1 h 20 min" / "45 min" / "mniej niż minuta". */
    public static String duration(final long millis) {
        final long minutes = Math.max(0, millis) / 60_000L;
        if (minutes <= 0) {
            return "mniej niż minuta";
        }
        final long h = minutes / 60;
        final long m = minutes % 60;
        if (h == 0) {
            return m + " min";
        }
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
