// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.postep.model.Discovery;

/**
 * Research decisions of NPC countries: which discovery to research next (by personality) and how much of the daily
 * income to invest. Pure functions.
 */
public final class NpcStrategy {

    private NpcStrategy() {
    }

    /** Personality from Armia's {@code NpcSettings} (0–100 each); {@link #NEUTRAL} without Armia. */
    public record Personality(int aggression, int expansion, int diplomacy, int wealth) {

        public static final Personality NEUTRAL = new Personality(50, 50, 50, 50);
    }

    /**
     * @param baseShare     percent of the daily income invested by a neutral personality
     * @param maxShare      upper bound of the share, percent
     * @param warFactor     multiplier of the share while at war (e.g. 0.4)
     * @param reserve       copper always left in the treasury
     * @param maxOfBalance  at most this fraction of the treasury per day (0..1)
     * @param capFraction   never invest beyond this fraction of the daily investment cap (0..1)
     */
    public record InvestSettings(double baseShare, double maxShare, double warFactor, long reserve, double maxOfBalance,
                                 double capFraction) {
    }

    /** Weight of a discovery category for a personality (≥ 0.3). */
    public static double categoryWeight(final String category, final Personality p) {
        final String c = category == null ? "" : category;
        return switch (c) {
            case "wojsko" -> 0.4 + p.aggression() / 100.0 * 1.2;
            case "gospodarka" -> 0.4 + p.wealth() / 100.0 * 1.2;
            case "budownictwo" -> 0.4 + p.expansion() / 100.0 * 1.2;
            case "dyplomacja" -> 0.4 + p.diplomacy() / 100.0 * 1.2;
            case "nauka" -> 0.7 + (p.wealth() + p.diplomacy()) / 200.0 * 0.6;
            default -> 0.7;
        };
    }

    /**
     * Next discovery to research: the available one with the best score
     * {@code categoryWeight × requiredBonus × olderEraBonus / sqrt(cost / 100) × jitter}, where required-for-era
     * discoveries of the current era get {@code requiredBonus} (bigger when {@code urgent}: the minimum time in the
     * era is over), leftovers of earlier eras are slightly preferred (cheap catch-up), and the jitter (0.9–1.1) is a
     * deterministic hash of the country id and discovery id so different NPC countries diverge. Empty when nothing is
     * available.
     */
    public static Optional<Discovery> chooseResearch(final TechTree tree, final Set<String> owned, final int era,
                                                     final Personality p, final UUID country, final boolean urgent) {
        final List<Discovery> available = tree.available(owned, era);
        Discovery best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (final Discovery d : available) {
            final double score = score(d, era, p, country, urgent);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return Optional.ofNullable(best);
    }

    static double score(final Discovery d, final int era, final Personality p, final UUID country, final boolean urgent) {
        double s = categoryWeight(d.category(), p);
        if (d.requiredForEra() && d.era() == era) {
            s *= urgent ? 3.0 : 1.6;
        }
        if (d.era() < era) {
            s *= 1.2;
        }
        s /= Math.sqrt(Math.max(1.0, d.cost() / 100.0));
        s *= jitter(country, d.id());
        return s;
    }

    /** Deterministic 0.9..1.1 from the pair. */
    static double jitter(final UUID country, final String id) {
        final long h = (country == null ? 0 : country.getLeastSignificantBits() ^ country.getMostSignificantBits()) * 31 + id.hashCode();
        final long mixed = (h ^ (h >>> 29)) * 0x9E3779B97F4A7C15L;
        final double unit = ((mixed >>> 11) & ((1L << 20) - 1)) / (double) (1L << 20);
        return 0.9 + 0.2 * unit;
    }

    /**
     * Share of the income (percent) an NPC country invests: {@code fixedShare} when ≥ 0 (set through the API), else
     * {@code baseShare × (0.5 + wealth/100) × (0.7 + diplomacy/100 × 0.6)}, × {@code warFactor} at war, clamped to
     * {@code 0..maxShare}.
     */
    public static double investShare(final InvestSettings s, final Personality p, final boolean atWar, final int fixedShare) {
        if (fixedShare >= 0) {
            return Math.min(100, fixedShare);
        }
        double share = s.baseShare() * (0.5 + p.wealth() / 100.0) * (0.7 + p.diplomacy() / 100.0 * 0.6);
        if (atWar) {
            share *= s.warFactor();
        }
        return Math.max(0, Math.min(s.maxShare(), share));
    }

    /**
     * Copper to invest today: {@code income × share}, never touching the reserve, at most {@code maxOfBalance} of the
     * treasury and never beyond {@code capFraction} of the daily investment cap (given what was invested already).
     */
    public static long investAmount(final InvestSettings s, final long income, final long balance, final double sharePercent,
                                    final double investedToday, final double ratePerCopper, final double dailyCap) {
        if (income <= 0 || sharePercent <= 0 || balance <= s.reserve()) {
            return 0L;
        }
        long amount = (long) Math.floor(income * sharePercent / 100.0);
        amount = Math.min(amount, balance - s.reserve());
        amount = Math.min(amount, (long) Math.floor(balance * Math.max(0, Math.min(1, s.maxOfBalance()))));
        final long useful = InvestmentMath.investedForFraction(s.capFraction(), ratePerCopper, dailyCap) - (long) Math.ceil(investedToday);
        amount = Math.min(amount, Math.max(0, useful));
        return Math.max(0L, amount);
    }
}
