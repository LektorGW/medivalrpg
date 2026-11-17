// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;

/** {@link Integrations.Population} backed by {@link LudnoscApi}; loaded only when Ludnosc is enabled. */
final class LudnoscPopulation implements Integrations.Population {

    private final LudnoscApi api;
    private final Logger logger;
    private final Set<String> warned = new HashSet<>();

    LudnoscPopulation(final LudnoscApi api, final Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public int population(final UUID country) {
        try {
            return Math.max(0, this.api.population(country));
        } catch (final RuntimeException | LinkageError e) {
            this.warn("population", e);
            return 0;
        }
    }

    @Override
    public int tier(final UUID country) {
        try {
            return this.api.settlementTier(country).map(LudnoscApi.SettlementTier::level).orElse(-1);
        } catch (final RuntimeException | LinkageError e) {
            this.warn("settlementTier", e);
            return -1;
        }
    }

    @Override
    public Map<String, Integer> buildings(final UUID country) {
        final Map<String, Integer> out = new HashMap<>();
        try {
            final java.util.Optional<LudnoscInfo> info = this.api.info(country);
            if (info.isPresent()) {
                for (final LudnoscInfo.Building b : info.get().buildings()) {
                    if (b.type() != null && (b.function() == null || !b.function().contains("w naprawie"))) {
                        out.merge(b.type(), 1, Integer::sum);
                    }
                }
                return out;
            }
        } catch (final RuntimeException | LinkageError e) {
            this.warn("info", e);
        }
        try {
            for (final LudnoscApi.BuildingRequirement r : this.api.buildingRequirements(country)) {
                if (r.built() > 0) {
                    out.merge(r.type(), r.built(), Integer::sum);
                }
            }
        } catch (final RuntimeException | LinkageError e) {
            this.warn("buildingRequirements", e);
        }
        return out;
    }

    @Override
    public long dailyTax(final UUID country) {
        try {
            return Math.max(0, this.api.dailyTaxIncome(country));
        } catch (final RuntimeException | LinkageError e) {
            this.warn("dailyTaxIncome", e);
            return 0;
        }
    }

    private void warn(final String method, final Throwable e) {
        if (this.warned.add(method)) {
            this.logger.warning("LudnoscApi#" + method + " zawiodło: " + e);
        }
    }
}
