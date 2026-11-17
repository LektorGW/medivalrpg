// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** KrajeApi.startSiege (NPC AI, panel): a non-adjacent chunk is attempted as a naval landing, never silently razed. */
class ApiSiegeModeTest {

    @Test
    void nonAdjacentChunkBecomesLandingOnlyWhenEnabled() {
        assertTrue(SiegeMode.landing(false, true, true), "overseas target → landing (transport proof still required)");
        assertFalse(SiegeMode.landing(true, true, true), "land front → normal siege");
        assertFalse(SiegeMode.landing(false, false, true), "landings disabled → normal siege refuses NOT_ADJACENT as before");
        assertFalse(SiegeMode.landing(false, true, false), "adjacency not required → old behaviour of a normal siege");
    }
}
