// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResourceSaleTest {
    @Test void refusedDepositKeepsAllMineOutputForRetry() {
        final var stock = new HashMap<>(Map.of("ore", 12L, "stone", 20L));
        final var quantities = Map.of("ore", 7L, "stone", 12L);
        assertFalse(ResourceSale.sell(stock, quantities, 19, () -> false));
        assertEquals(Map.of("ore", 12L, "stone", 20L), stock);
        assertTrue(ResourceSale.sell(stock, quantities, 19, () -> true));
        assertEquals(Map.of("ore", 5L, "stone", 8L), stock);
    }

    @Test void successfulSaleDebitsExactlyOnceAndCannotSellMissingStock() {
        final var stock = new HashMap<>(Map.of("precious", 5L));
        final var calls = new AtomicInteger();
        final var quantities = Map.of("precious", 5L);
        assertTrue(ResourceSale.sell(stock, quantities, 125, () -> { calls.incrementAndGet(); return true; }));
        assertFalse(ResourceSale.sell(stock, quantities, 125, () -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
        assertEquals(0L, stock.get("precious"));
    }

    @Test void invalidSaleNeverCallsTreasury() {
        final var stock = new HashMap<>(Map.of("ore", 4L));
        final java.util.function.BooleanSupplier forbidden = () -> { fail("invalid sale reached treasury"); return true; };
        assertFalse(ResourceSale.sell(stock, Map.of("ore", -1L), 5, forbidden));
        assertFalse(ResourceSale.sell(stock, Map.of("ore", 1L), 0, forbidden));
        assertFalse(ResourceSale.sell(stock, Map.of("ore", 5L), 5, forbidden));
        assertEquals(4L, stock.get("ore"));
    }
}
