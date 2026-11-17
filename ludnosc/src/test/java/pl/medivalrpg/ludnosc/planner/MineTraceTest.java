// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Finding the head of a gallery dug earlier (mine taken over from another country). */
class MineTraceTest {

    /** Ids without block state, as the server reports them for the trace. */
    static void apply(final MineDiggerTest.Underground w, final MineDigger.Result r) {
        for (final MineDigger.Op op : r.ops()) {
            w.put(op.x(), op.y(), op.z(), op.state().replaceAll("\\[.*", ""));
        }
    }

    /** Digs like MineService: one step per run, floors 10 deeper after {@code floorLength} level steps, wall torches. */
    static int[] dig(final MineDiggerTest.Underground w, final int x, final int y, final int z, final int dir,
                     final int steps, final int bottom, final int floorLength) {
        int cx = x;
        int cy = y;
        int cz = z;
        int d = dir;
        int target = bottom;
        int level = 0;
        for (int i = 0; i < steps; i++) {
            if (cy <= target && level >= floorLength) {
                target -= MinePlanner.FLOOR_DEPTH;
                level = 0;
            }
            final MineDigger.Result r = MineDigger.dig(w, cx, cy, cz, d, 1, target, 0, 4, i);
            apply(w, r);
            if (i % 7 == 0) { // MineService lighting: a wall torch above the previous head
                w.put(cx, cy + 1, cz, "minecraft:wall_torch");
            }
            if (cy <= target) {
                level++;
            }
            cx = r.head()[0];
            cy = r.head()[1];
            cz = r.head()[2];
            d = r.dir();
        }
        return new int[] {cx, cy, cz, d};
    }

    @Test
    void followsStairsAndLevelGalleryToTheRealHead() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        final int[] head = dig(w, 0, 56, 0, 3, 30, 44, 1000);
        final MineTrace.Trace t = MineTrace.trace(w, 0, 56, 0, 3, 400);
        assertArrayEquals(new int[] {head[0], head[1], head[2]}, t.head());
        assertEquals(head[3], t.dir());
        assertEquals(30, t.length());
        assertEquals(44, t.lowestY());
    }

    @Test
    void followsASecondFloor() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        final int[] head = dig(w, 0, 56, 0, 2, 12 + 20 + 10 + 6, 44, 20);
        final MineTrace.Trace t = MineTrace.trace(w, 0, 56, 0, 2, 400);
        assertArrayEquals(new int[] {head[0], head[1], head[2]}, t.head());
        assertEquals(48, t.length());
        assertEquals(34, t.lowestY(), "one floor deeper");
    }

    @Test
    void followsATurnAroundLavaAndAVeinDetour() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        int[] h = dig(w, 0, 56, 0, 2, 15, 44, 1000);
        // a detour east for 5 steps (vein), then south again
        h = dig(w, h[0], h[1], h[2], 1, 5, 44, 1000);
        h = dig(w, h[0], h[1], h[2], 2, 6, 44, 1000);
        final MineTrace.Trace t = MineTrace.trace(w, 0, 56, 0, 2, 400);
        assertArrayEquals(new int[] {h[0], h[1], h[2]}, t.head());
        assertEquals(26, t.length());
    }

    @Test
    void neverWalksIntoANaturalCaveBeyondTheHead() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        final int[] head = dig(w, 0, 56, 0, 2, 20, 44, 1000);
        for (int z = head[2] + 1; z <= head[2] + 8; z++) { // open cave straight ahead, no rails, no stairs
            for (int y = head[1] - 1; y <= head[1] + 2; y++) {
                w.put(head[0], y, z, "minecraft:cave_air");
            }
        }
        final MineTrace.Trace t = MineTrace.trace(w, 0, 56, 0, 2, 400);
        assertArrayEquals(new int[] {head[0], head[1], head[2]}, t.head());
        assertEquals(20, t.length());
    }

    @Test
    void noGalleryKeepsTheShaftExitAndMaxStepsCaps() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        final MineTrace.Trace none = MineTrace.trace(w, 5, 56, 5, 1, 400);
        assertArrayEquals(new int[] {5, 56, 5}, none.head());
        assertEquals(0, none.length());
        assertEquals(1, none.dir());
        dig(w, 0, 56, 0, 2, 30, 44, 1000);
        assertEquals(10, MineTrace.trace(w, 0, 56, 0, 2, 10).length());
    }

    @Test
    void unreadableBlocksGiveNoResult() {
        final MineDiggerTest.Underground w = new MineDiggerTest.Underground();
        dig(w, 0, 56, 0, 2, 20, 44, 1000);
        final MineDigger.Blocks partly = (x, y, z) -> z > 10 ? null : w.at(x, y, z);
        assertNull(MineTrace.trace(partly, 0, 56, 0, 2, 400), "unloaded chunk: retry later");
    }
}
