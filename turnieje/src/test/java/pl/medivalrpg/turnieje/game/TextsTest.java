// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextsTest {

    private static final List<Integer> ANNOUNCE = List.of(1800, 900, 600, 300, 180, 60);

    @Test
    void crossedThresholdsOncePerTick() {
        assertEquals(List.of(300), Texts.crossed(301, 300, ANNOUNCE));
        assertEquals(List.of(), Texts.crossed(300, 299, ANNOUNCE), "already announced");
        assertEquals(List.of(), Texts.crossed(500, 499, ANNOUNCE));
    }

    @Test
    void lagSpikeAnnouncesEverySkippedThresholdLargestFirst() {
        assertEquals(List.of(600, 300), Texts.crossed(601, 250, ANNOUNCE));
    }

    @Test
    void startOfCountdownDoesNotAnnounceItsOwnLength() {
        // sign-ups opened with exactly 30 min left: the first tick goes 1800 → 1799, 1800 is not "crossed" (prev must be > t)
        assertEquals(List.of(), Texts.crossed(1800, 1799, ANNOUNCE));
    }

    @Test
    void durations() {
        assertEquals("9 s", Texts.duration(9));
        assertEquals("2 min 5 s", Texts.duration(125));
        assertEquals("5 min", Texts.duration(300));
        assertEquals("1 h 2 min", Texts.duration(3725));
        assertEquals("2 d 3 h", Texts.duration(2 * 86_400 + 3 * 3600 + 59));
        assertEquals("0 s", Texts.duration(-5));
        assertEquals("1:35", Texts.clock(95));
        assertEquals("0:05", Texts.clock(5));
    }

    @Test
    void normalizeAndIds() {
        assertEquals("zolc gesla", Texts.normalize(" ŻÓŁĆ gęśla "));
        assertTrue(Texts.validId("arena_1-a"));
        assertFalse(Texts.validId("Arena"));
        assertFalse(Texts.validId("a b"));
        assertFalse(Texts.validId(""));
        assertFalse(Texts.validId("x".repeat(25)));
    }
}
