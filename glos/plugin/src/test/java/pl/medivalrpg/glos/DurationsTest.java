// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {

    @Test
    void parsesCompoundDurations() {
        assertEquals(600_000L, Durations.parseMillis("10m"));
        assertEquals(5_400_000L, Durations.parseMillis("1h30m"));
        assertEquals(86_400_000L, Durations.parseMillis(" 1D "));
        assertEquals(604_800_000L, Durations.parseMillis("1w"));
    }

    @Test
    void rejectsInvalidInput() {
        assertEquals(-1L, Durations.parseMillis(null));
        assertEquals(-1L, Durations.parseMillis(""));
        assertEquals(-1L, Durations.parseMillis("10"));
        assertEquals(-1L, Durations.parseMillis("m10"));
        assertEquals(-1L, Durations.parseMillis("10x"));
        assertEquals(-1L, Durations.parseMillis("0m"));
        assertEquals(-1L, Durations.parseMillis("99999999999999999999d"));
        assertEquals(-1L, Durations.parseMillis("9999999999999999w"));
    }

    @Test
    void formatsShortly() {
        assertEquals("10m", Durations.format(600_000L));
        assertEquals("1d 2h", Durations.format(93_600_000L));
        assertEquals("1m 5s", Durations.format(65_000L));
        assertEquals("1s", Durations.format(0L));
    }
}
