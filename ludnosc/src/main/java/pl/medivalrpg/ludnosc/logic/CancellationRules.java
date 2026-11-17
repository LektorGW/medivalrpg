// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;

/** Unused cut material stays in the existing resource ledger, in the same units as consumption. */
public final class CancellationRules {
    private CancellationRules() {}
    public static void returnBuffer(ConstructionSite site, CountryState country) {
        for (final var e : site.materials().entrySet()) {
            if (e.getValue() > 0) country.resources().merge(e.getKey(), e.getValue().longValue(), Long::sum);
        }
        site.materials().clear();
    }
}
