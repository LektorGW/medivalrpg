// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarmupTest {

    private static final long T0 = 1_000_000L;

    private static Warmup warmup() {
        return new Warmup("world", 10.5, 64.0, -3.5, 0.5, T0, 5);
    }

    @Test
    void smallMovesAreAllowed() {
        final Warmup w = warmup();
        assertFalse(w.movedTooFar("world", 10.5, 64.0, -3.5), "standing still");
        assertFalse(w.movedTooFar("world", 10.9, 64.0, -3.5), "0.4 bl.");
        assertFalse(w.movedTooFar("world", 10.8, 64.0, -3.2), "0.42 bl. diagonally");
    }

    @Test
    void movingMoreThanHalfABlockCancels() {
        final Warmup w = warmup();
        assertTrue(w.movedTooFar("world", 11.1, 64.0, -3.5), "0.6 bl. on x");
        assertTrue(w.movedTooFar("world", 10.5, 64.0, -2.9), "0.6 bl. on z");
        assertTrue(w.movedTooFar("world", 10.5, 63.4, -3.5), "falling 0.6 bl.");
        assertTrue(w.movedTooFar("world", 10.9, 64.4, -3.1), "0.4 + 0.4 + 0.4 → 0.69 bl.");
    }

    @Test
    void anotherWorldCancels() {
        assertTrue(warmup().movedTooFar("world_nether", 10.5, 64.0, -3.5));
        assertTrue(warmup().movedTooFar(null, 10.5, 64.0, -3.5));
    }

    @Test
    void countdownSecondsRoundUp() {
        final Warmup w = warmup();
        assertEquals(5, w.secondsLeft(T0));
        assertEquals(5, w.secondsLeft(T0 + 1));
        assertEquals(4, w.secondsLeft(T0 + 1000));
        assertEquals(3, w.secondsLeft(T0 + 2500));
        assertEquals(1, w.secondsLeft(T0 + 4999));
        assertEquals(0, w.secondsLeft(T0 + 5000));
        assertEquals(0, w.secondsLeft(T0 + 60_000));
    }

    @Test
    void finishesAfterWarmup() {
        final Warmup w = warmup();
        assertFalse(w.finished(T0));
        assertFalse(w.finished(T0 + 4999));
        assertTrue(w.finished(T0 + 5000));
    }

    @Test
    void zeroAndNegativeValues() {
        final Warmup instant = new Warmup("world", 0, 0, 0, -1, T0, -3);
        assertTrue(instant.finished(T0));
        assertEquals(0, instant.secondsLeft(T0));
        assertFalse(instant.movedTooFar("world", 0, 0, 0));
        assertTrue(instant.movedTooFar("world", 0.01, 0, 0), "max-move 0 → any move cancels");
    }
}
