// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Sampler pacing: a country whose read is costly is sampled proportionally less often. */
class EconomyServiceTest {

    @Test
    void costlyCountriesAreSampledLessOften() {
        final long base = 300_000L;
        assertEquals(base, EconomyService.intervalFor(base, 0, 8), "nieznany koszt");
        assertEquals(base, EconomyService.intervalFor(base, 7.9, 8), "w budżecie");
        assertEquals(750_000L, EconomyService.intervalFor(base, 20, 8), "20 ms przy budżecie 8 → co 12,5 min");
    }
}
