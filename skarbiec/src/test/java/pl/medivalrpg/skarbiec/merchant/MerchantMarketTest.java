// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;

class MerchantMarketTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    /** Defaults of config.yml: target 32 bundles, (target/stock)^0.5 in 50..175 %, budget 500 zł in 30..125 %. */
    private static final MerchantMath.Market M = new MerchantMath.Market(true, 32, 0.5, 50, 175, 25, 6, 10, 50_000, 0.5, 30, 125, 50, 150);
    private static final long TARGET = 32 * 16;

    // ------------------------------------------------------------------ curves and bounds

    @Test
    void stockCurveIsSmoothMonotonicAndBounded() {
        assertEquals(1.0, M.stockFactor(TARGET, TARGET), 1e-9);
        assertEquals(1.75, M.stockFactor(0, TARGET), 1e-9); // empty → upper bound
        assertEquals(0.5, M.stockFactor(100 * TARGET, TARGET), 1e-9); // huge surplus → lower bound
        assertEquals(Math.sqrt(2), M.stockFactor(TARGET / 2.0, TARGET), 1e-9);
        double previous = Double.MAX_VALUE;
        for (int s = 0; s <= 8 * TARGET; s += 4) {
            final double f = M.stockFactor(s, TARGET);
            assertTrue(f <= previous + 1e-12, "non-increasing at " + s);
            assertTrue(f >= 0.5 && f <= 1.75);
            // no jumps: one item more never changes the factor by more than one percentage point
            assertTrue(Math.abs(f - M.stockFactor(s + 1, TARGET)) <= 0.01, "smooth at " + s);
            previous = f;
        }
    }

    @Test
    void budgetCurveLowersPricesAndLimitsAndStopsWhenEmpty() {
        assertEquals(0.0, M.budgetFactor(0));
        assertEquals(0.0, M.budgetFactor(-5));
        assertEquals(1.0, M.budgetFactor(50_000), 1e-9);
        assertEquals(1.25, M.budgetFactor(5_000_000), 1e-9);
        assertEquals(0.3, M.budgetFactor(1), 1e-9);
        assertTrue(M.budgetFactor(10_000) < M.budgetFactor(40_000));
        assertEquals(0, M.buyPrice(30, TARGET, TARGET, 0, 100), "empty budget: no buying");
        assertTrue(M.buyPrice(30, TARGET, TARGET, 5_000, 100) < M.buyPrice(30, TARGET, TARGET, 200_000, 100));
        assertEquals(2048, M.scaledLimit(2048, 16, 50_000));
        assertEquals(512, M.scaledLimit(2048, 16, 12_500));
        assertEquals(16, M.scaledLimit(2048, 16, 1), "a non-empty budget keeps one bundle");
        assertEquals(0, M.scaledLimit(2048, 16, 0));
        assertEquals(0, M.scaledLimit(0, 16, 0), "no limit stays no limit");
    }

    @Test
    void sellPriceAlwaysAboveBuyPriceSoRoundTripsLoseMoney() {
        final Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            final long baseBuy = 1 + random.nextInt(200);
            final long baseSell = baseBuy + random.nextInt(200);
            final double stock = random.nextInt(4000);
            final long budget = random.nextInt(500_000);
            final int rotation = 85 + random.nextInt(60);
            final long buy = M.buyPrice(baseBuy, stock, TARGET, budget, rotation);
            final long sell = M.sellPrice(baseSell, stock, TARGET, buy);
            assertTrue(sell > buy, "sell " + sell + " must exceed buy " + buy);
        }
    }

    @Test
    void stockLevels() {
        assertEquals("NONE", M.level(10, TARGET, 16));
        assertEquals("LOW", M.level(100, TARGET, 16));
        assertEquals("OK", M.level(TARGET, TARGET, 16));
        assertEquals("HIGH", M.level(3 * TARGET, TARGET, 16));
    }

    // ------------------------------------------------------------------ quotes

    @Test
    void saleQuoteFollowsTheCurveAndStopsAtTheBudget() {
        // 10 bundles into an empty warehouse with a full budget: every next bundle pays less (stock grows).
        final MerchantMath.Quote q = MerchantMath.quoteMarketSale(M, 16, 30, 0, 0, TARGET, 50_000, 100, 0, 0, 0, 0, 160, 10);
        assertEquals(10, q.bundles());
        assertEquals(MerchantMath.Limit.NONE, q.limitedBy());
        assertTrue(q.payout() < 10 * Math.round(30 * 1.75));
        // Tiny budget: only what the merchant can pay.
        final MerchantMath.Quote poor = MerchantMath.quoteMarketSale(M, 16, 30, TARGET, TARGET, TARGET, 40, 100, 0, 0, 0, 0, 1_600, 100);
        assertEquals(MerchantMath.Limit.BUDGET, poor.limitedBy());
        assertTrue(poor.payout() <= 40);
        // Empty budget: nothing.
        final MerchantMath.Quote none = MerchantMath.quoteMarketSale(M, 16, 30, TARGET, TARGET, TARGET, 0, 100, 0, 0, 0, 0, 1_600, 1);
        assertEquals(0, none.bundles());
        assertEquals(MerchantMath.Limit.BUDGET, none.limitedBy());
        // Daily limits still apply first.
        assertEquals(MerchantMath.Limit.ITEM_DAILY, MerchantMath.quoteMarketSale(M, 16, 30, 0, 0, TARGET, 50_000, 100, 32, 40, 0, 0, 160, 5).limitedBy());
        assertEquals(MerchantMath.Limit.MONEY_DAILY, MerchantMath.quoteMarketSale(M, 16, 30, 0, 0, TARGET, 50_000, 100, 0, 0, 49_990, 50_000, 160, 5).limitedBy());
    }

    @Test
    void referenceStockProtectsAgainstAQuickDump() {
        // Real stock was just bought out (0) but the reference still remembers a full warehouse: buying stays cheap.
        final long cheap = M.buyPrice(30, Math.max(0, TARGET * 3), TARGET, 50_000, 100);
        final MerchantMath.Quote dump = MerchantMath.quoteMarketSale(M, 16, 30, 0, TARGET * 3, TARGET, 50_000, 100, 0, 0, 0, 0, 16, 1);
        assertEquals(cheap, dump.payout());
    }

    @Test
    void purchaseOnlyFromRealStockAndGetsDearerWhileBuyingOut() {
        final MerchantMath.Quote q = MerchantMath.quoteMarketPurchase(M, 16, 45, 40, 40, TARGET, s -> 0, 10);
        assertEquals(2, q.bundles(), "only 40 items → two bundles of 16");
        assertEquals(MerchantMath.Limit.STOCK, q.limitedBy());
        final MerchantMath.Quote none = MerchantMath.quoteMarketPurchase(M, 16, 45, 15, 15, TARGET, s -> 0, 1);
        assertEquals(0, none.bundles());
        final long first = M.sellPrice(45, TARGET, TARGET, 0);
        final MerchantMath.Quote three = MerchantMath.quoteMarketPurchase(M, 16, 45, TARGET, TARGET, TARGET, s -> 0, 3);
        assertTrue(three.payout() > 3 * first - 1, "price rises as stock leaves");
    }

    // ------------------------------------------------------------------ time: return to equilibrium

    @Test
    void referenceAndSurplusReturnToEquilibriumAndNothingIsCreated() {
        final long t0 = 1_761_600_000_000L;
        final MerchantMarket market = new MerchantMarket(t0, ZONE);
        market.good("WHEAT").stock = 4 * TARGET; // big dump
        final Map<String, Long> targets = Map.of("WHEAT", TARGET, "IRON_INGOT", 256L);
        long t = t0;
        for (int h = 1; h <= 24 * 30; h++) {
            t += HOUR;
            market.advance(t, ZONE, M, targets, Map.of());
        }
        final MerchantMarket.Good wheat = market.good("WHEAT");
        assertTrue(wheat.stock() < 4 * TARGET && wheat.stock() > TARGET, "surplus shrinks towards the target: " + wheat.stock());
        assertEquals(wheat.stock(), wheat.reference(), wheat.stock() * 0.02, "reference followed the stock (lagging by its half-life)");
        assertEquals(0, market.good("IRON_INGOT").stock(), "without restock no item is ever created");
        // after a whole year the surplus is almost gone but never below the target
        for (int d = 0; d < 365; d++) {
            t += DAY;
            market.advance(t, ZONE, M, targets, Map.of());
        }
        assertTrue(wheat.stock() >= TARGET && wheat.stock() <= TARGET + 2, "stock " + wheat.stock());
    }

    @Test
    void halfLifeOfTheReference() {
        assertEquals(50, M.smooth(0, 100, 6 * HOUR), 1e-9);
        assertEquals(75, M.smooth(0, 100, 12 * HOUR), 1e-9);
        assertEquals(30, M.smooth(30, 100, 0), 1e-9);
    }

    @Test
    void explicitRestockOnlyUpToTheTarget() {
        final long t0 = 1_761_600_000_000L;
        final MerchantMarket market = new MerchantMarket(t0, ZONE);
        market.advance(t0 + DAY / 2, ZONE, M, Map.of("BREAD", 100L), Map.of("BREAD", 64L));
        assertEquals(32, market.good("BREAD").stock());
        market.advance(t0 + 10 * DAY, ZONE, M, Map.of("BREAD", 100L), Map.of("BREAD", 64L));
        assertEquals(100, market.good("BREAD").stock());
    }

    @Test
    void trendComparesWithADayAgo() {
        final long t0 = 1_761_600_000_000L;
        final MerchantMarket market = new MerchantMarket(t0, ZONE);
        market.sample(t0, 10, Map.of("WHEAT", new long[] {0, 30, 50}));
        assertFalse(market.sample(t0 + HOUR / 2, 10, Map.of()), "at most one sample an hour");
        market.sample(t0 + 24 * HOUR, 10, Map.of("WHEAT", new long[] {0, 36, 60}));
        assertEquals(30, market.buyPriceAgo("WHEAT", t0 + 24 * HOUR));
        assertEquals(-1, market.buyPriceAgo("IRON", t0 + 24 * HOUR));
    }

    @Test
    void stateSurvivesSaving() {
        final long t0 = 1_761_600_000_000L;
        final MerchantMarket market = new MerchantMarket(t0, ZONE);
        market.good("WHEAT").stock = 77;
        market.good("WHEAT").reference = 70.5;
        market.sample(t0, 123, Map.of("WHEAT", new long[] {77, 30, 50}));
        final MerchantMarket back = MerchantMarket.fromJson(market.toJson(), t0 + 1, ZONE);
        assertEquals(77, back.good("WHEAT").stock());
        assertEquals(70.5, back.good("WHEAT").reference(), 1e-9);
        assertEquals(1, back.history().size());
        assertEquals(123, back.history().peekFirst().budget());
    }

    // ------------------------------------------------------------------ flow through the closed ledger

    private static Ledger closedLedger(final long merchantBudget) {
        final Ledger ledger = new Ledger(Ledger.Sink.NONE);
        ledger.restore(Map.of(WorldMoney.RESERVE, WorldMoney.CAP - 1_000_000 - merchantBudget, WorldMoney.CASH, 0L, WorldMoney.PAYMENTS, 0L,
            WorldMoney.MERCHANT, merchantBudget, WorldMoney.HOUSEHOLDS, 0L, "player:a", 1_000_000L), 1);
        ledger.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        return ledger;
    }

    @Test
    void saleMovesRealMoneyAndRealItemsOrNothing() {
        final Ledger ledger = closedLedger(1_000);
        final MerchantMarket market = new MerchantMarket(0, ZONE);
        final MerchantMath.Quote q = MerchantMath.quoteMarketSale(M, 16, 30, 0, 0, TARGET, ledger.balance(WorldMoney.MERCHANT), 100, 0, 0, 0, 0, 64, 4);
        final TxResult ok = market.settleSale("WHEAT", (long) q.bundles() * 16, q.payout(),
            () -> ledger.transfer(WorldMoney.MERCHANT, "player:a", q.payout(), "kupiec: skup"));
        assertTrue(ok.ok());
        assertEquals(q.bundles() * 16L, market.good("WHEAT").stock());
        assertEquals(1_000 - q.payout(), ledger.balance(WorldMoney.MERCHANT));
        assertEquals(WorldMoney.CAP, ledger.totalMoney(), "closed pool unchanged");

        // A payment the budget cannot cover (e.g. budget spent meanwhile) leaves the warehouse untouched.
        final long before = market.good("WHEAT").stock();
        final TxResult fail = market.settleSale("WHEAT", 16, 5_000, () -> ledger.transfer(WorldMoney.MERCHANT, "player:a", 5_000, "kupiec: skup"));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, fail);
        assertEquals(before, market.good("WHEAT").stock());
        assertEquals(WorldMoney.CAP, ledger.totalMoney());
    }

    @Test
    void purchaseNeedsStockAndMoneyReturnsToTheBudget() {
        final Ledger ledger = closedLedger(0);
        final MerchantMarket market = new MerchantMarket(0, ZONE);
        final TxResult noStock = market.settlePurchase("WHEAT", 16, 50, () -> {
            throw new AssertionError("no charge without stock");
        });
        assertEquals(TxResult.INSUFFICIENT_FUNDS, noStock);
        market.good("WHEAT").stock = 32;
        final TxResult ok = market.settlePurchase("WHEAT", 16, 50, () -> ledger.transfer("player:a", WorldMoney.MERCHANT, 50, "kupiec: zakup"));
        assertTrue(ok.ok());
        assertEquals(16, market.good("WHEAT").stock());
        assertEquals(50, ledger.balance(WorldMoney.MERCHANT));
        assertEquals(WorldMoney.CAP, ledger.totalMoney());
    }

    @Test
    void sellingAndBuyingBackAlwaysLosesMoney() {
        long stock = TARGET / 2;
        final double reference = stock;
        final long budget = 50_000;
        final MerchantMath.Quote sold = MerchantMath.quoteMarketSale(M, 16, 30, stock, reference, TARGET, budget, 140, 0, 0, 0, 0, 16 * 8, 8);
        stock += sold.bundles() * 16L;
        final long after = stock;
        final MerchantMath.Quote back = MerchantMath.quoteMarketPurchase(M, 16, 45, after, reference, TARGET,
            s -> M.buyPrice(30, Math.max(s, reference), TARGET, budget - sold.payout(), 140), sold.bundles());
        assertEquals(sold.bundles(), back.bundles());
        assertTrue(back.payout() > sold.payout(), "buy-back " + back.payout() + " vs sale " + sold.payout());
    }
}
