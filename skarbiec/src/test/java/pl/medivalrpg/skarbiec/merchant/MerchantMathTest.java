// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MerchantMathTest {

    private static final MerchantMath.Supply SUPPLY = new MerchantMath.Supply(true, 100, 10, 50);

    @Test
    void supplyLowersPriceStepwiseWithFloor() {
        assertEquals(100, SUPPLY.percent(0));
        assertEquals(100, SUPPLY.percent(99));
        assertEquals(90, SUPPLY.percent(100));
        assertEquals(50, SUPPLY.percent(10_000));
        assertEquals(9, SUPPLY.price(10, 150));
        assertEquals(1, SUPPLY.price(1, 10_000));
        assertEquals(0, SUPPLY.price(0, 0));
        assertEquals(10, MerchantMath.Supply.NONE.price(10, 1_000_000));
    }

    @Test
    void saleStopsAtItemsAvailable() {
        assertEquals(new MerchantMath.Quote(2, 8, MerchantMath.Limit.ITEMS),
            MerchantMath.quoteSale(16, 4, 0, 0, 0, 0, 0, 40, 10, MerchantMath.Supply.NONE));
    }

    @Test
    void saleStopsAtPerItemDailyLimit() {
        assertEquals(new MerchantMath.Quote(2, 8, MerchantMath.Limit.ITEM_DAILY),
            MerchantMath.quoteSale(16, 4, 0, 600, 640, 0, 0, 1000, 10, MerchantMath.Supply.NONE));
    }

    @Test
    void saleStopsAtDailyMoneyLimit() {
        assertEquals(new MerchantMath.Quote(1, 30, MerchantMath.Limit.MONEY_DAILY),
            MerchantMath.quoteSale(1, 30, 0, 0, 0, 450, 500, 64, 10, MerchantMath.Supply.NONE));
    }

    @Test
    void priceDropsDuringOneBigSale() {
        // step 100 items, bundles of 50, base 10: 10, 10, then 9, 9.
        assertEquals(new MerchantMath.Quote(4, 38, MerchantMath.Limit.NONE),
            MerchantMath.quoteSale(50, 10, 0, 0, 0, 0, 0, 200, 4, SUPPLY));
    }

    private static final MerchantMath.Rotation ROTATION = new MerchantMath.Rotation(true, 3, 15, 2, 40, 2);
    private static final List<String> KEYS = List.of("WHEAT", "COAL", "IRON_INGOT", "OAK_LOG", "DIAMOND", "LEATHER");

    @Test
    void rotationPeriodsAndCountdown() {
        final long hour = 3_600_000L;
        assertEquals(0, ROTATION.period(0));
        assertEquals(0, ROTATION.period(3 * hour - 1));
        assertEquals(1, ROTATION.period(3 * hour));
        assertEquals(hour, ROTATION.millisToNext(2 * hour));
    }

    @Test
    void featuredGoodsAreStableWithinPeriodAndChangeOverTime() {
        final Set<String> first = ROTATION.featured(KEYS, 100);
        assertEquals(2, first.size());
        assertTrue(KEYS.containsAll(first));
        assertEquals(first, ROTATION.featured(KEYS, 100));
        boolean changed = false;
        for (long p = 101; p < 120 && !changed; p++) {
            changed = !ROTATION.featured(KEYS, p).equals(first);
        }
        assertTrue(changed, "offer must rotate");
        assertEquals(Set.of(), MerchantMath.Rotation.NONE.featured(KEYS, 100));
    }

    @Test
    void pricesSwingWithinBoundsAndFeaturedGetBonus() {
        for (long p = 0; p < 200; p++) {
            for (final String k : KEYS) {
                final int percent = ROTATION.percent(k, p, false);
                assertTrue(percent >= 85 && percent <= 115, k + " " + percent);
                assertEquals(percent + 40, ROTATION.percent(k, p, true));
            }
        }
        assertEquals(100, MerchantMath.Rotation.NONE.percent("WHEAT", 5, true));
    }

    @Test
    void buyPriceNeverReachesSellPrice() {
        for (long p = 0; p < 200; p++) {
            // wheat: buy 5, sell 10 — +40 % and +15 % swing would give 7.75; coal-like 18/20 would exceed.
            assertTrue(ROTATION.buyPrice(18, 20, "COAL", p, true) <= 19);
            assertTrue(ROTATION.buyPrice(5, 10, "WHEAT", p, true) < 10);
            assertTrue(ROTATION.buyPrice(5, 0, "EGG", p, true) >= 1); // merchant does not sell: no cap
        }
        assertEquals(0, ROTATION.buyPrice(0, 10, "X", 1, true));
        assertEquals(1, ROTATION.buyPrice(5, 1, "X", 1, true));
    }

    @Test
    void featuredLimitIsMultiplied() {
        assertEquals(256, ROTATION.itemLimit(128, true));
        assertEquals(128, ROTATION.itemLimit(128, false));
        assertEquals(0, ROTATION.itemLimit(0, true)); // 0 = no limit stays no limit
    }
}
