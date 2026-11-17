// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mine galleries on synthetic underground: solid stone, a cave and a lava pocket. */
class MineDiggerTest {

    /** Stone below y=64, air above; extra blocks by position. */
    static final class Underground implements MineDigger.Blocks {
        final Map<Long, String> special = new HashMap<>();

        void put(final int x, final int y, final int z, final String id) {
            this.special.put(MineDigger.key(x, y, z), id);
        }

        @Override
        public String at(final int x, final int y, final int z) {
            final String s = this.special.get(MineDigger.key(x, y, z));
            if (s != null) {
                return s;
            }
            return y >= 64 ? "minecraft:air" : "minecraft:stone";
        }
    }

    /** Applies the ops to the synthetic world. */
    private static void apply(final Underground w, final MineDigger.Result r) {
        for (final MineDigger.Op op : r.ops()) {
            w.put(op.x(), op.y(), op.z(), op.state().replaceAll("\\[.*", ""));
        }
    }

    @Test
    void staircaseThenLevelGalleryWithTorchesAndFrames() {
        final Underground w = new Underground();
        final MineDigger.Result r = MineDigger.dig(w, 0, 55, 0, 2, 30, 50, 6, 4);
        assertEquals(30, r.dug());
        assertFalse(r.stopped());
        assertEquals(50, r.head()[1], "reached the target depth");
        assertTrue(r.ops().stream().anyMatch(o -> o.state().contains("cobblestone_stairs")), "stairs down");
        assertTrue(r.ops().stream().anyMatch(o -> o.state().equals("minecraft:rail[shape=north_south]")), "rails");
        assertTrue(r.ops().stream().filter(o -> o.state().contains("wall_torch")).count() >= 4, "torches");
        assertTrue(r.ops().stream().anyMatch(o -> o.state().contains("oak_log")), "timber frames");
    }

    @Test
    void neverDigsNextToLava() {
        final Underground w = new Underground();
        // lava pocket straight ahead at z = 10..12 on the corridor's level
        for (int x = -1; x <= 1; x++) {
            for (int z = 10; z <= 12; z++) {
                for (int y = 49; y <= 52; y++) {
                    w.put(x, y, z, "minecraft:lava");
                }
            }
        }
        final MineDigger.Result r = MineDigger.dig(w, 0, 50, 0, 2, 25, 50, 6, 4);
        apply(w, r);
        for (final MineDigger.Op op : r.ops()) {
            if (!op.state().equals("minecraft:air")) {
                continue;
            }
            for (final int[] n : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                assertFalse(w.at(op.x() + n[0], op.y() + n[1], op.z() + n[2]).contains("lava"),
                    "dug cell next to lava at " + op);
            }
        }
        assertTrue(r.dug() > 9, "the gallery turned around the lava instead of stopping at once: " + r.dug());
    }

    @Test
    void stopsWhenSurroundedByWater() {
        final Underground w = new Underground();
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 6; z++) {
                for (int y = 46; y <= 56; y++) {
                    w.put(x, y, z, "minecraft:water");
                }
            }
        }
        final MineDigger.Result r = MineDigger.dig(w, 0, 50, -2, 2, 10, 50, 6, 4);
        assertTrue(r.stopped());
        assertTrue(r.ops().stream().noneMatch(o -> o.z() >= 0 && o.state().equals("minecraft:air")), "no breach towards the water");
    }

    @Test
    void cavesAreWalledOffAndFloorsFilled() {
        final Underground w = new Underground();
        // a cave crossing the corridor's path: air from x -3..3, z 5..8, y 48..53
        for (int x = -3; x <= 3; x++) {
            for (int z = 5; z <= 8; z++) {
                for (int y = 48; y <= 53; y++) {
                    w.put(x, y, z, "minecraft:cave_air");
                }
            }
        }
        final MineDigger.Result r = MineDigger.dig(w, 0, 50, 0, 2, 14, 50, 0, 0);
        apply(w, r);
        for (int z = 1; z <= 14; z++) {
            assertFalse(MineDigger.open(w.at(0, 49, z)), "floor under the corridor at z=" + z);
            for (final int side : new int[] {-1, 1}) {
                for (int h = 0; h <= 1; h++) {
                    assertFalse(MineDigger.open(w.at(side, 50 + h, z)), "wall beside the corridor at z=" + z);
                }
            }
        }
    }

    /** Digging one step at a time (as the server does every few seconds) gives the same gallery as at once. */
    @Test
    void stepByStepEqualsOneGo() {
        final Underground once = new Underground();
        final MineDigger.Result all = MineDigger.dig(once, 0, 60, 0, 1, 24, 52, 6, 4);
        final Underground stepwise = new Underground();
        int[] head = {0, 60, 0};
        int dir = 1;
        int length = 0;
        final java.util.Set<String> ops = new java.util.HashSet<>();
        for (int i = 0; i < 24; i++) {
            final MineDigger.Result r = MineDigger.dig(stepwise, head[0], head[1], head[2], dir, 1, 52, 6, 4, length);
            apply(stepwise, r);
            r.ops().forEach(o -> ops.add(o.x() + "," + o.y() + "," + o.z() + "=" + o.state()));
            head = r.head();
            dir = r.dir();
            length += r.dug();
        }
        assertEquals(all.dug(), length);
        assertEquals(all.head()[0], head[0]);
        assertEquals(all.head()[1], head[1]);
        assertEquals(all.head()[2], head[2]);
        final java.util.Set<String> expected = new java.util.HashSet<>();
        all.ops().forEach(o -> expected.add(o.x() + "," + o.y() + "," + o.z() + "=" + o.state()));
        assertEquals(expected, ops, "same torches, frames, rails and stairs");
    }
}
