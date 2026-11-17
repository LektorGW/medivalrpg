// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Safe landing spot of {@code /kraj admin tp} on a synthetic world (stone floor at y = 63, air above). */
class SafeSpotTest {

    private static final int FLOOR = 63;

    /** Flat world: solid up to {@link #FLOOR}, air above; overrides per block. */
    private static final class World implements SafeSpot.Probe {
        private final Map<String, SafeSpot.Cell> overrides = new HashMap<>();

        World set(final int x, final int y, final int z, final SafeSpot.Cell cell) {
            this.overrides.put(x + "," + y + "," + z, cell);
            return this;
        }

        @Override
        public SafeSpot.Cell at(final int x, final int y, final int z) {
            final SafeSpot.Cell o = this.overrides.get(x + "," + y + "," + z);
            if (o != null) {
                return o;
            }
            return y <= FLOOR ? SafeSpot.Cell.SOLID : SafeSpot.Cell.PASSABLE;
        }
    }

    private static Optional<SafeSpot.Spot> find(final World w, final int x, final int y, final int z) {
        return SafeSpot.find(w, x, y, z, SafeSpot.DEFAULT_RADIUS, -63, 318);
    }

    @Test
    void safePointIsKept() {
        assertEquals(Optional.of(new SafeSpot.Spot(5, 64, 5)), find(new World(), 5, 64, 5));
    }

    @Test
    void buriedPointMovesUpToTheSurface() {
        // spawn point saved inside the ground (e.g. the terrain changed): nearest safe = straight up
        assertEquals(Optional.of(new SafeSpot.Spot(0, 64, 0)), find(new World(), 0, 61, 0));
    }

    @Test
    void floatingPointMovesDownToTheGround() {
        assertEquals(Optional.of(new SafeSpot.Spot(0, 64, 0)), find(new World(), 0, 67, 0));
    }

    @Test
    void lavaBelowIsAvoidedForTheNearestNeighbour() {
        final World w = new World().set(0, FLOOR, 0, SafeSpot.Cell.DANGER);
        final SafeSpot.Spot spot = find(w, 0, 64, 0).orElseThrow();
        assertEquals(64, spot.y());
        assertEquals(1, spot.x() * spot.x() + spot.z() * spot.z(), "one block away: " + spot);
    }

    @Test
    void waterIsNotSafe() {
        final World w = new World().set(0, 64, 0, SafeSpot.Cell.LIQUID).set(0, 65, 0, SafeSpot.Cell.LIQUID);
        assertTrue(find(w, 0, 64, 0).map(s -> s.x() != 0 || s.z() != 0).orElse(false));
    }

    @Test
    void nothingSafeWithinRadius() {
        final SafeSpot.Probe lava = (x, y, z) -> SafeSpot.Cell.DANGER;
        assertTrue(SafeSpot.find(lava, 0, 64, 0, SafeSpot.DEFAULT_RADIUS, -63, 318).isEmpty());
    }

    @Test
    void radiusIsRespected() {
        // only one safe column, 9 blocks away → outside radius 8
        final SafeSpot.Probe probe = (x, y, z) -> {
            if (x == 9 && z == 0) {
                return y <= FLOOR ? SafeSpot.Cell.SOLID : SafeSpot.Cell.PASSABLE;
            }
            return SafeSpot.Cell.DANGER;
        };
        assertTrue(SafeSpot.find(probe, 0, 64, 0, 8, -63, 318).isEmpty());
        assertEquals(Optional.of(new SafeSpot.Spot(9, 64, 0)), SafeSpot.find(probe, 0, 64, 0, 9, -63, 318));
    }
}
