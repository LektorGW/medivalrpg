// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Map;
import java.util.function.BooleanSupplier;

/** Inventory is debited only after the treasury accepts a sale. Main thread only. */
public final class ResourceSale {
    private ResourceSale() { }

    public static boolean sell(final Map<String, Long> stock, final Map<String, Long> quantities,
                               final long proceeds, final BooleanSupplier deposit) {
        if (proceeds <= 0 || quantities.isEmpty()) return false;
        for (final var e : quantities.entrySet()) {
            if (e.getValue() < 0 || stock.getOrDefault(e.getKey(), 0L) < e.getValue()) return false;
        }
        if (!deposit.getAsBoolean()) return false;
        quantities.forEach((key, amount) -> stock.compute(key, (k, value) -> (value == null ? 0 : value) - amount));
        return true;
    }
}
