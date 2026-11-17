// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.model.Condition;

class ReturnAndSafeSpotTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final StoredLocation HOME = new StoredLocation("world", 500.5, 70, -20.5, 90f, 0f);
    private static final long T0 = 10_000_000L;

    // ------------------------------------------------------------------ return window

    @Test
    void windowIsOpenForItsMinutes() {
        final ReturnWindow w = new ReturnWindow();
        assertFalse(w.canReturn(A, T0));
        w.open(A, HOME, T0, 10);
        assertEquals(Optional.of(HOME), w.get(A, T0));
        assertTrue(w.canReturn(A, T0 + 9 * 60_000L + 59_999));
        assertEquals(60_000L, w.remainingMs(A, T0 + 9 * 60_000L));
        assertFalse(w.canReturn(A, T0 + 10 * 60_000L), "closes after 10 min");
        assertFalse(w.canReturn(A, T0), "expired offer is gone");
    }

    @Test
    void windowIsSingleUse() {
        final ReturnWindow w = new ReturnWindow();
        w.open(A, HOME, T0, 10);
        w.consume(A);
        assertFalse(w.canReturn(A, T0 + 1));
        assertEquals(0, w.remainingMs(A, T0));
    }

    @Test
    void zeroMinutesDisables() {
        final ReturnWindow w = new ReturnWindow();
        w.open(A, HOME, T0, 10);
        w.open(A, HOME, T0, 0);
        assertFalse(w.canReturn(A, T0));
    }

    @Test
    void quitAtTheSpawnDoesNotReplaceTheLastPosition() {
        final StoredLocation spawn = new StoredLocation("world", 0.5, 70, 0.5, 0f, 0f);
        assertFalse(ReturnWindow.worthRemembering(new StoredLocation("world", 30, 70, 30, 0f, 0f), spawn, 48));
        assertTrue(ReturnWindow.worthRemembering(new StoredLocation("world", 40, 70, 40, 0f, 0f), spawn, 48));
        assertTrue(ReturnWindow.worthRemembering(new StoredLocation("world_nether", 1, 70, 1, 0f, 0f), spawn, 48));
        assertTrue(ReturnWindow.worthRemembering(HOME, null, 48), "no spawn known");
        assertFalse(ReturnWindow.worthRemembering(null, spawn, 48));
    }

    @Test
    void canReturnCondition() {
        assertEquals(Condition.CAN_RETURN, Condition.parse("can-return"));
        assertEquals(Condition.CAN_RETURN, Condition.parse("moze_wrocic"));
    }

    @Test
    void lastPositionsJsonRoundTrip() {
        final Map<UUID, StoredLocation> data = Map.of(A, HOME);
        assertEquals(data, PositionStore.fromJson(PositionStore.toJson(data)));
        assertEquals(Map.of(), PositionStore.fromJson("{\"x\": {\"world\": \"w\", \"x\": 1, \"y\": 2, \"z\": 3}}"));
    }

    // ------------------------------------------------------------------ safe spot on a synthetic grid

    /** Sparse grid: everything not set is air; {@link #floor} fills a solid layer. */
    private static final class Grid implements SafeSpot.Probe {
        final Map<String, SafeSpot.Cell> cells = new HashMap<>();
        int floorY = Integer.MIN_VALUE;

        Grid floor(final int y) {
            this.floorY = y;
            return this;
        }

        Grid set(final int x, final int y, final int z, final SafeSpot.Cell c) {
            this.cells.put(x + "," + y + "," + z, c);
            return this;
        }

        @Override
        public SafeSpot.Cell at(final int x, final int y, final int z) {
            final SafeSpot.Cell c = this.cells.get(x + "," + y + "," + z);
            if (c != null) {
                return c;
            }
            return y <= this.floorY ? SafeSpot.Cell.SOLID : SafeSpot.Cell.PASSABLE;
        }
    }

    private static Optional<SafeSpot.Spot> find(final Grid g, final int x, final int y, final int z) {
        return SafeSpot.find(g, x, y, z, 8, -63, 318);
    }

    @Test
    void safeOriginalIsKept() {
        assertEquals(Optional.of(new SafeSpot.Spot(0, 65, 0)), find(new Grid().floor(64), 0, 65, 0));
    }

    @Test
    void insideABlockMovesUp() {
        // feet inside the ground (e.g. a block was placed there) → one block up
        assertEquals(Optional.of(new SafeSpot.Spot(0, 65, 0)), find(new Grid().floor(64), 0, 64, 0));
        // stuck two blocks deep in a pillar → the free top of the neighbouring column is nearer than the pillar top
        final Grid pillar = new Grid().floor(64).set(0, 65, 0, SafeSpot.Cell.SOLID).set(0, 66, 0, SafeSpot.Cell.SOLID);
        final SafeSpot.Spot spot = find(pillar, 0, 65, 0).orElseThrow();
        assertEquals(65, spot.y());
        assertEquals(1, Math.abs(spot.x()) + Math.abs(spot.z()), "cost 1 beats climbing the pillar (cost 4): " + spot);
    }

    @Test
    void lavaIsAvoided() {
        final Grid g = new Grid().floor(64)
            .set(0, 64, 0, SafeSpot.Cell.DANGER)   // lava floor under the feet
            .set(0, 65, 0, SafeSpot.Cell.DANGER);  // lava at the feet
        final SafeSpot.Spot spot = find(g, 0, 65, 0).orElseThrow();
        assertEquals(65, spot.y());
        assertEquals(1, Math.abs(spot.x()) + Math.abs(spot.z()), "next column, not diagonal: " + spot);
    }

    @Test
    void airOverAChasmFindsTheEdge() {
        // floor at 64 except a 5×5 pit in the middle (down to the void)
        final Grid g = new Grid().floor(64);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -64; y <= 64; y++) {
                    g.set(x, y, z, SafeSpot.Cell.PASSABLE);
                }
            }
        }
        final SafeSpot.Spot spot = find(g, 0, 65, 0).orElseThrow();
        assertEquals(65, spot.y());
        assertEquals(3, Math.max(Math.abs(spot.x()), Math.abs(spot.z())), "first column outside the pit: " + spot);
    }

    @Test
    void waterIsNotSafe() {
        final Grid g = new Grid().floor(60);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 61; y <= 66; y++) {
                    g.set(x, y, z, SafeSpot.Cell.LIQUID);
                }
            }
        }
        assertTrue(find(g, 0, 65, 0).isEmpty(), "open ocean, no land within 8 → refuse");
        g.set(6, 66, 0, SafeSpot.Cell.SOLID).set(6, 67, 0, SafeSpot.Cell.PASSABLE).set(6, 68, 0, SafeSpot.Cell.PASSABLE);
        assertEquals(Optional.of(new SafeSpot.Spot(6, 67, 0)), find(g, 0, 65, 0), "small island");
    }

    @Test
    void worldLimits() {
        final Grid g = new Grid().floor(-64);
        assertTrue(SafeSpot.find(g, 0, -63, 0, 2, -50, 300).isEmpty(), "floor below minY");
        assertTrue(SafeSpot.safe(g, 0, -63, 0));
    }
}
