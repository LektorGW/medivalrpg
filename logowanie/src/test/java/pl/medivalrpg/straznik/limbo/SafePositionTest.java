// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.limbo;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class SafePositionTest {
    @Test void exactLogoutPosition() {
        assertEquals(64, SafePosition.select(64, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> true).orElseThrow());
    }
    @Test void airDescendsToGround() {
        assertEquals(64, SafePosition.select(97.25, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> true).orElseThrow());
    }
    @Test void slabKeepsExactHeight() {
        assertEquals(63.5, SafePosition.select(63.5, -64, 320, b -> b == 63 ? 63.5 : Double.NaN, y -> true).orElseThrow());
    }
    @Test void voidAndLiquidHaveNoSurface() {
        assertTrue(SafePosition.select(97.25, -64, 320, b -> Double.NaN, y -> true).isEmpty());
    }
    @Test void obstructedHeadroomRejected() {
        assertTrue(SafePosition.select(97.25, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> false).isEmpty());
    }
    @Test void depthIsBounded() {
        assertTrue(SafePosition.select(129, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> true).isEmpty());
        assertEquals(64, SafePosition.select(128, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> true).orElseThrow());
    }
    @Test void worldBoundsAndInvalidCoordinates() {
        for (double y : new double[]{-65, 321, Double.NaN, Double.POSITIVE_INFINITY})
            assertTrue(SafePosition.select(y, -64, 320, b -> 64, h -> true).isEmpty());
    }
    @Test void neverMovesUpward() {
        assertTrue(SafePosition.select(63.25, -64, 320, b -> b == 63 ? 64 : Double.NaN, y -> true).isEmpty());
    }
}
