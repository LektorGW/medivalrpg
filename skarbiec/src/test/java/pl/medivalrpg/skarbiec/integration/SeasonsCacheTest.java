// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression (found on Paper): the first "unknown" season must not stick forever. */
class SeasonsCacheTest {

    @Test
    void neverReadIsNeverFreshAndUnknownExpiresQuickly() {
        final long now = 1_793_246_000_000L;
        assertFalse(Seasons.fresh(-1, now, false), "never read");
        assertFalse(Seasons.fresh(-1, now, true));
        assertTrue(Seasons.fresh(now - 4_000, now, false));
        assertFalse(Seasons.fresh(now - 6_000, now, false), "unknown kept only 5 s (Ludnosc enables after Skarbiec)");
        assertTrue(Seasons.fresh(now - 59_000, now, true));
        assertFalse(Seasons.fresh(now - 61_000, now, true));
        assertFalse(Seasons.fresh(now + 1_000, now, true), "clock went back");
    }
}
