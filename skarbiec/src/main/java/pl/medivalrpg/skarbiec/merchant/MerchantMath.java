// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Pure pricing / limit arithmetic of the NPC merchant (unit tested, no Bukkit). */
public final class MerchantMath {

    private MerchantMath() {
    }

    /**
     * Supply rule: every {@code step} items sold to merchants today (by everybody) lowers the buying price by
     * {@code dropPercent}, never below {@code minPercent} of the base price and never below 1 copper.
     */
    public record Supply(boolean enabled, int step, int dropPercent, int minPercent) {

        public static final Supply NONE = new Supply(false, 1, 0, 100);

        public int percent(final long soldToday) {
            if (!this.enabled || this.dropPercent <= 0) {
                return 100;
            }
            final long steps = Math.max(0, soldToday) / Math.max(1, this.step);
            final long p = 100 - Math.min(100, steps) * this.dropPercent;
            return (int) Math.max(this.minPercent, Math.max(0, p));
        }

        public long price(final long base, final long soldToday) {
            if (base <= 0) {
                return 0;
            }
            return Math.max(1, base * this.percent(soldToday) / 100);
        }
    }

    /**
     * Rotating offer: every {@code periodHours} the merchant changes prices by up to ±{@code swingPercent} and picks
     * {@code featured} goods he is looking for (+{@code bonusPercent} price, item limit × {@code limitMultiplier}).
     * Deterministic from the period number, so every merchant and every player see the same offer and nothing has
     * to be stored. The buying price never reaches the merchant's selling price (no buy-and-resell profit).
     */
    public record Rotation(boolean enabled, int periodHours, int swingPercent, int featured, int bonusPercent,
                           int limitMultiplier) {

        public static final Rotation NONE = new Rotation(false, 24, 0, 0, 0, 1);

        public long periodMillis() {
            return Math.max(1, this.periodHours) * 3_600_000L;
        }

        /** Number of the rotation period that contains {@code epochMillis}. */
        public long period(final long epochMillis) {
            return Math.floorDiv(epochMillis, this.periodMillis());
        }

        /** Milliseconds until the next rotation. */
        public long millisToNext(final long epochMillis) {
            return (this.period(epochMillis) + 1) * this.periodMillis() - epochMillis;
        }

        /** Keys of the featured goods in {@code period} (among {@code keys}, in a stable order). */
        public Set<String> featured(final List<String> keys, final long period) {
            if (!this.enabled || this.featured <= 0 || keys.isEmpty()) {
                return Set.of();
            }
            final List<String> shuffled = new ArrayList<>(keys);
            Collections.shuffle(shuffled, new Random(period * 0x9E3779B97F4A7C15L ^ 0x5EEDL));
            return Set.copyOf(shuffled.subList(0, Math.min(this.featured, shuffled.size())));
        }

        /** Price percent of one good in {@code period}: 100 ± swing, plus the bonus when featured. */
        public int percent(final String key, final long period, final boolean featured) {
            if (!this.enabled) {
                return 100;
            }
            final int swing = Math.max(0, this.swingPercent);
            final long seed = (period * 31 + key.hashCode()) * 0x2545F4914F6CDD1DL;
            final int offset = swing == 0 ? 0 : new Random(seed).nextInt(2 * swing + 1) - swing;
            return Math.max(1, 100 + offset + (featured ? this.bonusPercent : 0));
        }

        /** Buying price of one bundle; stays below {@code sellPrice} when the merchant also sells the good. */
        public long buyPrice(final long base, final long sellPrice, final String key, final long period, final boolean featured) {
            if (base <= 0) {
                return 0;
            }
            long price = Math.max(1, base * this.percent(key, period, featured) / 100);
            if (sellPrice > 0) {
                price = Math.min(price, Math.max(1, sellPrice - 1));
            }
            return price;
        }

        public int itemLimit(final int base, final boolean featured) {
            return featured && base > 0 ? base * Math.max(1, this.limitMultiplier) : base;
        }
    }

    /** Result of {@link #quoteSale}: how many bundles can be sold now and what they pay in total. */
    public record Quote(int bundles, long payout, Limit limitedBy) {
    }

    /** What stopped the sale before {@code requested} bundles (NONE = all requested sold). */
    public enum Limit { NONE, ITEMS, ITEM_DAILY, MONEY_DAILY, BUDGET, STOCK }

    /**
     * @param bundleSize       items per bundle
     * @param basePrice        price of one bundle at full price
     * @param globalSoldToday  items of this kind sold today by all players (drives the supply drop)
     * @param playerSoldToday  items of this kind sold today by this player
     * @param itemDailyLimit   per-player item limit (0 = none)
     * @param earnedToday      copper this player already got from merchants today
     * @param moneyDailyLimit  per-player daily money limit (0 = none)
     * @param availableItems   items the player has
     * @param requested        bundles the player wants to sell
     */
    public static Quote quoteSale(final int bundleSize, final long basePrice, final long globalSoldToday,
                                  final long playerSoldToday, final int itemDailyLimit, final long earnedToday,
                                  final long moneyDailyLimit, final long availableItems, final int requested,
                                  final Supply supply) {
        int bundles = 0;
        long payout = 0;
        Limit limit = Limit.NONE;
        while (bundles < requested) {
            final long items = (long) (bundles + 1) * bundleSize;
            if (items > availableItems) {
                limit = Limit.ITEMS;
                break;
            }
            if (itemDailyLimit > 0 && playerSoldToday + items > itemDailyLimit) {
                limit = Limit.ITEM_DAILY;
                break;
            }
            final long price = supply.price(basePrice, globalSoldToday + (long) bundles * bundleSize);
            if (moneyDailyLimit > 0 && earnedToday + payout + price > moneyDailyLimit) {
                limit = Limit.MONEY_DAILY;
                break;
            }
            payout += price;
            bundles++;
        }
        return new Quote(bundles, payout, limit);
    }

    // ================================================================== dynamic market (stock + budget)

    /**
     * Dynamic market of the NPC merchant. Prices follow smooth power curves, never jumps:
     * <ul>
     *   <li>stock factor {@code (target / stock)^elasticity}, clamped to {@code [minPercent, maxPercent]} % - much stock
     *       means cheaper buying and selling, a shortage means higher prices on both sides;</li>
     *   <li>budget factor {@code (budget / budgetTarget)^budgetElasticity}, clamped to
     *       {@code [budgetMinPercent, budgetMaxPercent]} % - only the buying price; an empty budget stops buying;</li>
     *   <li>the selling price is always at least {@code spreadPercent} % above the current buying price, so selling to the
     *       merchant and buying back can never earn money;</li>
     *   <li>the reference stock follows the real stock with a half-life of {@code smoothingHours}; buying uses the larger
     *       and selling the smaller of the two, so a quick dump or buy-out cannot move a price in the player's favour.</li>
     * </ul>
     * Surplus above the target is shipped away by {@code surplusDecayPercentPerDay} % a day (the items leave the game,
     * money does not move). Items are never created unless a restock rate is configured for a good.
     */
    public record Market(boolean enabled, int targetBundles, double elasticity, int minPercent, int maxPercent, int spreadPercent,
                         double smoothingHours, double surplusDecayPercentPerDay, long budgetTarget, double budgetElasticity,
                         int budgetMinPercent, int budgetMaxPercent, int lowStockPercent, int highStockPercent) {

        public static final Market DISABLED = new Market(false, 32, 0.5, 50, 175, 25, 6, 10, 50_000, 0.5, 30, 125, 50, 150);

        public Market {
            targetBundles = Math.max(1, targetBundles);
            elasticity = Math.max(0, Math.min(3, elasticity));
            minPercent = Math.max(1, Math.min(100, minPercent));
            maxPercent = Math.max(100, Math.min(1000, maxPercent));
            spreadPercent = Math.max(1, Math.min(500, spreadPercent));
            smoothingHours = Math.max(0, smoothingHours);
            surplusDecayPercentPerDay = Math.max(0, Math.min(100, surplusDecayPercentPerDay));
            budgetTarget = Math.max(0, budgetTarget);
            budgetElasticity = Math.max(0, Math.min(3, budgetElasticity));
            budgetMinPercent = Math.max(1, Math.min(100, budgetMinPercent));
            budgetMaxPercent = Math.max(100, Math.min(1000, budgetMaxPercent));
            lowStockPercent = Math.max(1, Math.min(100, lowStockPercent));
            highStockPercent = Math.max(100, Math.min(1000, highStockPercent));
        }

        /** Price multiplier for a stock level ({@code target <= 0} = neutral). Continuous and non-increasing in stock. */
        public double stockFactor(final double stock, final double target) {
            if (target <= 0) {
                return 1.0;
            }
            final double ratio = Math.max(Math.max(0, stock) / target, 1e-3);
            return clamp(Math.pow(1 / ratio, this.elasticity), this.minPercent / 100.0, this.maxPercent / 100.0);
        }

        /** Buying-price multiplier for the merchant's budget; 0 when the budget is empty. */
        public double budgetFactor(final long budget) {
            if (budget <= 0) {
                return 0.0;
            }
            if (this.budgetTarget <= 0) {
                return 1.0;
            }
            return clamp(Math.pow((double) budget / this.budgetTarget, this.budgetElasticity), this.budgetMinPercent / 100.0,
                this.budgetMaxPercent / 100.0);
        }

        /** Share of the daily item limit available at this budget (1 at or above the target, 0 when empty). */
        public double limitScale(final long budget) {
            if (budget <= 0) {
                return 0;
            }
            return this.budgetTarget <= 0 ? 1.0 : Math.min(1.0, (double) budget / this.budgetTarget);
        }

        /** Item limit after the budget scale (no limit stays no limit; a positive limit keeps at least one bundle). */
        public int scaledLimit(final int limit, final int bundle, final long budget) {
            if (limit <= 0) {
                return limit;
            }
            if (budget <= 0) {
                return 0;
            }
            return (int) Math.max(Math.min(limit, bundle), Math.floor(limit * this.limitScale(budget)));
        }

        /** What the merchant pays for one bundle; 0 = does not buy now. */
        public long buyPrice(final long base, final double stock, final double target, final long budget, final int rotationPercent) {
            final double bf = this.budgetFactor(budget);
            if (base <= 0 || bf <= 0) {
                return 0;
            }
            return Math.max(1, Math.round(base * this.stockFactor(stock, target) * bf * Math.max(1, rotationPercent) / 100.0));
        }

        /** What the merchant charges for one bundle; always above {@code buyNow} by the spread. */
        public long sellPrice(final long baseSell, final double stock, final double target, final long buyNow) {
            if (baseSell <= 0) {
                return 0;
            }
            final long curve = Math.max(1, Math.round(baseSell * this.stockFactor(stock, target)));
            final long floor = buyNow <= 0 ? 0 : Math.max(buyNow + 1, (long) Math.ceil(buyNow * (100 + this.spreadPercent) / 100.0));
            return Math.max(curve, floor);
        }

        /** One step of the reference stock towards the real stock. */
        public double smooth(final double reference, final double stock, final long dtMillis) {
            if (dtMillis <= 0) {
                return reference;
            }
            if (this.smoothingHours <= 0) {
                return stock;
            }
            final double k = 1 - Math.pow(0.5, dtMillis / (this.smoothingHours * 3_600_000.0));
            return reference + (stock - reference) * k;
        }

        /** Surplus items (above {@code target}) shipped away during {@code dtMillis}, as a fraction (caller keeps the carry). */
        public double decay(final double stock, final double target, final long dtMillis) {
            if (this.surplusDecayPercentPerDay <= 0 || stock <= target || dtMillis <= 0) {
                return 0;
            }
            final double surplus = stock - target;
            final double keep = Math.pow(1 - this.surplusDecayPercentPerDay / 100.0, dtMillis / 86_400_000.0);
            return surplus * (1 - keep);
        }

        /** NONE / LOW / OK / HIGH for the GUI and the statistics. */
        public String level(final long stock, final long target, final int bundle) {
            if (stock < Math.max(1, bundle)) {
                return "NONE";
            }
            if (target <= 0) {
                return "OK";
            }
            final double pct = stock * 100.0 / target;
            return pct < this.lowStockPercent ? "LOW" : pct > this.highStockPercent ? "HIGH" : "OK";
        }

        private static double clamp(final double v, final double min, final double max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    /**
     * Player sells bundles to the dynamic merchant. Every bundle is priced on the curve at the stock it creates
     * (the reference stock never lowers it below that) and at the remaining budget; the sale stops at the first bundle
     * the budget cannot pay. Item and money daily limits work as before (item limit already scaled by the caller).
     */
    public static Quote quoteMarketSale(final Market m, final int bundleSize, final long basePrice, final long stock, final double reference,
                                        final long target, final long budget, final int rotationPercent, final long playerSoldToday,
                                        final int itemDailyLimit, final long earnedToday, final long moneyDailyLimit,
                                        final long availableItems, final int requested) {
        int bundles = 0;
        long payout = 0;
        Limit limit = Limit.NONE;
        while (bundles < requested) {
            final long items = (long) (bundles + 1) * bundleSize;
            if (items > availableItems) {
                limit = Limit.ITEMS;
                break;
            }
            if (itemDailyLimit > 0 && playerSoldToday + items > itemDailyLimit) {
                limit = Limit.ITEM_DAILY;
                break;
            }
            final double effective = Math.max(stock + (long) bundles * bundleSize, reference);
            final long left = budget - payout;
            final long price = m.buyPrice(basePrice, effective, target, left, rotationPercent);
            if (price <= 0 || price > left) {
                limit = Limit.BUDGET;
                break;
            }
            if (moneyDailyLimit > 0 && earnedToday + payout + price > moneyDailyLimit) {
                limit = Limit.MONEY_DAILY;
                break;
            }
            payout += price;
            bundles++;
        }
        return new Quote(bundles, payout, limit);
    }

    /**
     * Player buys bundles from the dynamic merchant, only from real stock. Each bundle is priced at the stock before it
     * leaves (the reference stock never raises the stock used for pricing), so a buy-out gets gradually dearer.
     * {@code buyNowOf} gives the merchant's current buying price at a stock, for the spread floor.
     */
    public static Quote quoteMarketPurchase(final Market m, final int bundleSize, final long baseSell, final long stock, final double reference,
                                            final long target, final java.util.function.LongUnaryOperator buyNowOf, final int requested) {
        int bundles = 0;
        long price = 0;
        Limit limit = Limit.NONE;
        while (bundles < requested) {
            final long before = stock - (long) bundles * bundleSize;
            if (before < bundleSize) {
                limit = Limit.STOCK;
                break;
            }
            final double effective = Math.min(before, reference);
            price += m.sellPrice(baseSell, effective, target, buyNowOf.applyAsLong(before));
            bundles++;
        }
        return new Quote(bundles, price, limit);
    }
}
