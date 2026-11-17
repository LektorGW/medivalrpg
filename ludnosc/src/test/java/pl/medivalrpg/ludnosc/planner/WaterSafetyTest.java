// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;

/** A lake next to the plot: the site finder keeps away; a forced site gets a dam and is drained; no flow inside. */
class WaterSafetyTest {

    /** Ground at y=64 (top block), a lake east of x >= 8 with its surface at y=64 (bed at 60). */
    static class Lake implements TerrainView {
        final int shore;

        Lake(final int shore) {
            this.shore = shore;
        }

        @Override
        public int height(final int x, final int z) {
            return 64;
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return x >= this.shore;
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return Math.abs(x) <= 60 && Math.abs(z) <= 60;
        }
    }

    private static final SiteFinder.Settings S = new SiteFinder.Settings(24, 2, 2, 3);

    @Test
    void siteFinderKeepsAwayFromTheLake() {
        final Lake lake = new Lake(8);
        // a well (4 layers below the floor) wanted right at the shore
        final SiteFinder.Spot s = SiteFinder.find(lake, new int[] {5, 5}, 6, 0, "center", List.of(), Set.of(), S, 4);
        assertNotNull(s);
        final int maxX = s.x() + 2;
        assertTrue(maxX + SiteFinder.WATER_MARGIN < 8, "plot + margin dry: " + s);
        // a harbour is allowed (and wants) to stand at the water
        final SiteFinder.Spot h = SiteFinder.find(lake, new int[] {5, 5}, 6, 0, "water", List.of(), Set.of(), S, 0);
        assertNotNull(h);
        assertTrue(h.x() + 2 + 8 >= 8, "next to the water: " + h);
    }

    @Test
    void waterBelowTheFloorFarEnoughIsAcceptedButPenalised() {
        // the lake surface is 6 below a plateau: building on the plateau next to it is fine (water lower than the dig)
        final TerrainView cliff = new Lake(8) {
            @Override
            public int height(final int x, final int z) {
                return x >= 8 ? 58 : 64;
            }
        };
        final SiteFinder.Spot near = SiteFinder.evaluate(cliff, 5, 5, 3, 0, 0, 0, "inner", List.of(), Set.of(), 2, 3, 0);
        assertNotNull(near, "water 6 below the floor does not flood an ordinary house");
        assertNull(SiteFinder.evaluate(cliff, 5, 5, 3, 0, 0, 0, "inner", List.of(), Set.of(), 2, 3, 6), "a deep shaft would reach it");
        final TerrainView noLake = new Lake(1000);
        final SiteFinder.Spot same = SiteFinder.evaluate(noLake, 5, 5, 3, 0, 0, 0, "inner", List.of(), Set.of(), 2, 3, 0);
        assertTrue(near.score() > same.score(), "water nearby costs points");
    }

    // ------------------------------------------------------------------ forced site: dam + drain

    /** Blocks: ground below y=64, lake (water) for x >= 6 up to y=64 (bed at 59), water plant, waterlogged stairs. */
    static final class World implements Drainage.Blocks {
        final Map<Long, Integer> set = new HashMap<>();

        @Override
        public int at(final int x, final int y, final int z) {
            final Integer v = this.set.get(MineDigger.key(x, y, z));
            if (v != null) {
                return v;
            }
            if (x >= 6 && y >= 60 && y <= 64) {
                return Drainage.LIQUID;
            }
            return y <= 64 ? Drainage.SOLID : Drainage.OPEN;
        }

        void apply(final List<Drainage.Op> ops) {
            for (final Drainage.Op op : ops) {
                this.set.put(MineDigger.key(op.x(), op.y(), op.z()), op.type() == Drainage.Type.DRY ? Drainage.OPEN : Drainage.SOLID);
            }
        }
    }

    @Test
    void forcedSiteAtTheLakeGetsADamAndIsDrained() {
        final World w = new World();
        // the site: footprint x -2..2, z -2..2, a well shaft 4 deep (y 60..64 dug), margin 3 reaches x = 5 (lake at x >= 6)
        // water already flowed into the shaft and into the margin
        for (int y = 61; y <= 64; y++) {
            w.set.put(MineDigger.key(0, y, 0), Drainage.LIQUID);
            w.set.put(MineDigger.key(4, y, 1), Drainage.LIQUID);
        }
        w.set.put(MineDigger.key(1, 65, -1), Drainage.WATERLOGGED); // flooded stairs
        w.set.put(MineDigger.key(0, 60, 0), Drainage.LIQUID);       // template water at the bottom of the well
        final Drainage.Region r = Drainage.Region.of(-2, 60, -2, 2, 70, 2, 3, 64);
        final List<Drainage.Op> ops = Drainage.plan(w, r, (x, y, z) -> x == 0 && y == 60 && z == 0);
        // dams first
        boolean drying = false;
        for (final Drainage.Op op : ops) {
            if (op.type() != Drainage.Type.DAM) {
                drying = true;
            } else {
                assertFalse(drying, "every dam before any drying");
                assertFalse(r.contains(op.x(), op.y(), op.z()), "dams stand outside the region");
            }
        }
        assertTrue(ops.stream().anyMatch(o -> o.type() == Drainage.Type.DAM && o.x() == 6), "dam along the shore");
        w.apply(ops);
        for (int y = 60; y <= 70; y++) {
            for (int z = -5; z <= 5; z++) {
                for (int x = -5; x <= 5; x++) {
                    if (x == 0 && y == 60 && z == 0) {
                        assertEquals(Drainage.LIQUID, w.at(x, y, z), "the well keeps its water");
                        continue;
                    }
                    final int v = w.at(x, y, z);
                    assertTrue(v != Drainage.LIQUID && v != Drainage.WATERLOGGED, "dry at " + x + "," + y + "," + z);
                }
            }
        }
        // the lake no longer touches the region: the shore column x = 6 is dammed from 60 to 64
        for (int y = 60; y <= 64; y++) {
            for (int z = -5; z <= 5; z++) {
                assertEquals(Drainage.SOLID, w.at(6, y, z), "dam at " + y + "," + z);
            }
        }
        // the shaft is open again, the flooded margin column is earth (no pit next to the house)
        assertEquals(Drainage.OPEN, w.at(0, 62, 0));
        assertEquals(Drainage.SOLID, w.at(4, 62, 1));
        // a second pass finds nothing to do
        assertEquals(List.of(), Drainage.plan(w, r, (x, y, z) -> x == 0 && y == 60 && z == 0));
    }

    @Test
    void liquidsCannotFlowIntoGuardedAreas() {
        final FluidGuard g = new FluidGuard();
        g.add(new BlockBox("world", -5, 60, -5, 5, 72, 5));
        g.add(new BlockBox("world", 30, 10, 30, 40, 20, 40));
        assertTrue(g.guarded("world", 0, 64, 0));
        assertTrue(g.guarded("world", 5, 60, -5), "edge");
        assertFalse(g.guarded("world", 6, 64, 0), "outside: the lake itself may flow");
        assertFalse(g.guarded("world", 0, 59, 0));
        assertFalse(g.guarded("nether", 0, 64, 0));
        assertTrue(g.guarded("world", 35, 15, 35), "mine gallery");
        assertFalse(new FluidGuard().guarded("world", 0, 64, 0));
        final Set<Long> unused = new HashSet<>();
        assertTrue(unused.isEmpty());
    }
}
