// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.storage;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EraLimitTest {
    @Test
    void loweringLimitPreservesResearchAndRecordsMigrationOnce() {
        final CountryState state = new CountryState();
        state.era = 4;
        state.points = 123;
        state.totalPoints = 456;
        state.discoveries.put("internet", 10L);
        state.pendingAdvance = true;
        assertTrue(state.limitEra(2, 100L));
        assertEquals(2, state.era);
        assertEquals(100L, state.eraSince);
        assertEquals(123, state.points);
        assertEquals(456, state.totalPoints);
        assertTrue(state.discoveries.containsKey("internet"));
        assertFalse(state.pendingAdvance);
        assertEquals("limit-epok", state.history.getFirst().cause);
        assertFalse(state.limitEra(2, 200L));
        assertEquals(1, state.history.size());
    }

    @Test
    void increasingLimitDoesNotAdvanceCountry() {
        final CountryState state = new CountryState();
        assertFalse(state.limitEra(2, 100L));
        assertEquals(1, state.era);
    }
}
