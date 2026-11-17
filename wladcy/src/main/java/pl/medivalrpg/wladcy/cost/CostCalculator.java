// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.cost;

import java.util.List;
import java.util.function.Function;
import pl.medivalrpg.wladcy.config.ModelPrice;

/** USD cost of API usage from {@code response.usage} and the price list in config.yml. Pure. */
public final class CostCalculator {

    private static final double MILLION = 1_000_000.0;

    private final Function<String, ModelPrice> prices;

    public CostCalculator(final Function<String, ModelPrice> prices) {
        this.prices = prices;
    }

    public double cost(final UsageSample u) {
        final ModelPrice p = this.prices.apply(u.model());
        return (u.input() * p.input() + u.output() * p.output() + u.cacheRead() * p.cacheRead() + u.cacheWrite() * p.cacheWrite()) / MILLION;
    }

    /** Sum over all attempts of one request (with server-side fallbacks each attempt is priced at its own model). */
    public double cost(final List<UsageSample> attempts) {
        double sum = 0;
        for (final UsageSample u : attempts) {
            sum += this.cost(u);
        }
        return sum;
    }
}
