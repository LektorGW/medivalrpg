// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeSmootherTest {

    /** 10×10 footprint at 0..9; the edge grass is at y = 64. */
    private static final Box FOOT = new Box(0, 59, 0, 9, 90, 9);
    private static final int EDGE = 64;

    private static Map<Long, Integer> apply(final List<EdgeSmoother.Column> plan, final EdgeSmoother.HeightMap h,
                                            final int width) {
        final Map<Long, Integer> out = new HashMap<>();
        for (int x = -width - 2; x <= 9 + width + 2; x++) {
            for (int z = -width - 2; z <= 9 + width + 2; z++) {
                out.put(key(x, z), h.surface(x, z));
            }
        }
        for (final EdgeSmoother.Column c : plan) {
            assertEquals(h.surface(c.x(), c.z()), c.from());
            out.put(key(c.x(), c.z()), c.to());
        }
        return out;
    }

    private static long key(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Test
    void lowTerrainIsFilledAsASlope() {
        final List<EdgeSmoother.Column> plan = EdgeSmoother.plan(FOOT, EDGE, 5, (x, z) -> 56);
        final Map<Long, Integer> h = apply(plan, (x, z) -> 56, 5);
        // walking west from the wall: 63, 62, 61, 60, 59, then the untouched 56 (3-block step the band cannot hide)
        assertEquals(63, h.get(key(-1, 4)));
        assertEquals(62, h.get(key(-2, 4)));
        assertEquals(61, h.get(key(-3, 4)));
        assertEquals(60, h.get(key(-4, 4)));
        assertEquals(59, h.get(key(-5, 4)));
        assertEquals(56, h.get(key(-6, 4)));
        assertTrue(plan.stream().allMatch(EdgeSmoother.Column::raise));
        assertTrue(plan.stream().noneMatch(c -> FOOT.containsColumn(c.x(), c.z())), "never inside the schematic");
    }

    @Test
    void highTerrainIsCutDown() {
        final List<EdgeSmoother.Column> plan = EdgeSmoother.plan(FOOT, EDGE, 6, (x, z) -> 80);
        final Map<Long, Integer> h = apply(plan, (x, z) -> 80, 6);
        for (int d = 1; d <= 6; d++) {
            assertEquals(EDGE + d, h.get(key(9 + d, 5)), "east, distance " + d);
            assertEquals(EDGE + d, h.get(key(5, -d)), "north, distance " + d);
        }
        assertEquals(80, h.get(key(16, 5)), "outside the band");
        assertTrue(plan.stream().noneMatch(EdgeSmoother.Column::raise));
    }

    @Test
    void slopeChangesAtMostOneBlockPerBlock() {
        // uneven terrain: a valley in the west, a hill in the east
        final EdgeSmoother.HeightMap terrain = (x, z) -> x < 5 ? 50 + Math.floorMod(z, 3) : 85 - Math.floorMod(z, 2);
        final int w = 6;
        final Map<Long, Integer> h = apply(EdgeSmoother.plan(FOOT, EDGE, w, terrain), terrain, w);
        for (int z = 0; z <= 9; z++) { // rows along the west / east wall: "away from the wall" is ±x
            int previous = EDGE;
            for (int x = -1; x >= -w; x--) {
                final int now = h.get(key(x, z));
                assertTrue(Math.abs(now - previous) <= 1, "west x=" + x + " z=" + z + ": " + previous + "→" + now);
                previous = now;
            }
            previous = EDGE;
            for (int x = 10; x <= 9 + w; x++) {
                final int now = h.get(key(x, z));
                assertTrue(Math.abs(now - previous) <= 1, "east x=" + x + " z=" + z + ": " + previous + "→" + now);
                previous = now;
            }
        }
    }

    @Test
    void gentleTerrainIsLeftAlone() {
        // within the cone edge ± distance → nothing to do
        final EdgeSmoother.HeightMap terrain = (x, z) -> EDGE + (x < 0 ? -1 : 1);
        final List<EdgeSmoother.Column> plan = EdgeSmoother.plan(FOOT, EDGE, 5, terrain);
        assertTrue(plan.isEmpty(), plan.toString());
    }

    @Test
    void cornersUseChebyshevDistance() {
        final List<EdgeSmoother.Column> plan = EdgeSmoother.plan(FOOT, EDGE, 4, (x, z) -> 40);
        final Map<Long, Integer> h = apply(plan, (x, z) -> 40, 4);
        assertEquals(63, h.get(key(-1, -1)));
        assertEquals(60, h.get(key(-4, -2)), "distance max(4, 2) = 4");
        assertEquals(40, h.get(key(-5, -5)), "outside the band");
        // band of 4 around a 10×10 footprint: 18×18 − 10×10 columns, all raised
        assertEquals(18 * 18 - 100, plan.size());
    }

    @Test
    void widthIsClampedToFourToSix() {
        assertEquals(4, EdgeSmoother.clampWidth(1));
        assertEquals(5, EdgeSmoother.clampWidth(5));
        assertEquals(6, EdgeSmoother.clampWidth(20));
        assertEquals(EDGE - 2, EdgeSmoother.target(0, EDGE, 2));
        assertEquals(EDGE + 3, EdgeSmoother.target(200, EDGE, 3));
        assertEquals(EDGE + 1, EdgeSmoother.target(EDGE + 1, EDGE, 3));
    }
}
