// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.planner.MineDigger;
import pl.medivalrpg.ludnosc.planner.MineTrace;

/**
 * Mines taken over with the land (Watykan, 2026-10-29): the gallery record follows the owner, an orphaned mine gets
 * its record back from the gallery in the world, and the chunk tickets no longer wait for a player near the store.
 */
class MineAdoptTest {

    /** Stone below y=64, air above. */
    private static final class Rock implements MineDigger.Blocks {
        final Map<String, String> blocks = new java.util.HashMap<>();

        @Override
        public String at(final int x, final int y, final int z) {
            return this.blocks.getOrDefault(x + "," + y + "," + z, y >= 64 ? "minecraft:air" : "minecraft:stone");
        }
    }

    @Test
    void watykanSecondMineGetsItsOldGalleryBack() {
        // ad18d33f: kopalnia 9x9, rotation 2, box min 394 55 -411; the founder (country f00b1800) dug 16 steps to
        // 380 44 -407 heading west; its record was lost when the land passed to Watykan.
        final int[] start = MineService.start(9, 9, 2, 394, 55, -411, 16, 12);
        assertArrayEquals(new int[] {396, 56, -407, 3}, new int[] {start[0], start[1], start[2], start[3]});
        assertEquals(16, start[MineService.REMAINING]);
        assertEquals(44, start[MineService.BOTTOM]);
        final Rock w = new Rock();
        int[] h = {start[0], start[1], start[2], start[3]};
        for (int i = 0; i < 16; i++) {
            final MineDigger.Result r = MineDigger.dig(w, h[0], h[1], h[2], h[3], 1, 44, 0, 4, i);
            for (final MineDigger.Op op : r.ops()) {
                w.blocks.put(op.x() + "," + op.y() + "," + op.z(), op.state().replaceAll("\\[.*", ""));
            }
            h = new int[] {r.head()[0], r.head()[1], r.head()[2], r.dir()};
        }
        final MineTrace.Trace t = MineTrace.trace(w, start[0], start[1], start[2], start[3], 400);
        final int[] rec = MineService.resume(start, t, 16, 2);
        // the founder's own record (backup-wave17): 380 44 -407, west, length 16, bottom 44
        assertArrayEquals(new int[] {380, 44, -407, 3, 16, 0, 44}, java.util.Arrays.copyOf(rec, 7));
        assertEquals(1, rec[MineService.FLOORS]);
        assertEquals(3, rec[MineService.MAIN_DIR]);
        assertEquals(MineService.SIZE, rec.length);
    }

    @Test
    void shortOldGalleryKeepsTheRestOfTheFreeSectionAndDeepFloorsCount() {
        final int[] start = MineService.start(9, 9, 0, 0, 55, 0, 16, 12);
        final int[] shortOne = MineService.resume(start, new MineTrace.Trace(new int[] {1, 50, 2}, 1, 6, 50), 16, 1);
        assertEquals(10, shortOne[MineService.REMAINING]);
        assertEquals(6, shortOne[MineService.LENGTH]);
        final int[] deep = MineService.resume(start, new MineTrace.Trace(new int[] {9, 30, 9}, 2, 90, 30), 16, 1);
        assertEquals(0, deep[MineService.REMAINING]);
        assertEquals(3, deep[MineService.FLOORS], "44 → 34 → 24 reaches the walking cell at 30");
        assertEquals(24, deep[MineService.BOTTOM]);
        assertEquals(90, deep[MineService.FLOOR_START]);
        assertEquals(2, deep[MineService.MAIN_DIR]);
        assertArrayEquals(start, MineService.resume(start, new MineTrace.Trace(new int[] {0, 0, 0}, 0, 0, 56), 16, 1),
            "nothing dug: the fresh record with its free section");
    }

    @Test
    void recordFollowsTheNewOwnerAndNeverOverwrites() {
        final Map<String, int[]> old = new LinkedHashMap<>();
        final Map<String, int[]> fresh = new LinkedHashMap<>();
        old.put("m", new int[] {1, 2, 3, 2, 40, 0, -10, 7});
        assertTrue(MineService.moveRecord(old, fresh, "m"));
        assertTrue(old.isEmpty());
        assertEquals(MineService.SIZE, fresh.get("m").length);
        assertEquals(40, fresh.get("m")[MineService.LENGTH]);
        final int[] kept = fresh.get("m");
        final Map<String, int[]> back = new LinkedHashMap<>(Map.of("m", new int[MineService.SIZE]));
        assertTrue(MineService.moveRecord(fresh, back, "m"));
        assertFalse(back.get("m") == kept, "an existing record of the new owner stays");
        final Map<String, int[]> ownerless = new LinkedHashMap<>(Map.of("x", new int[MineService.SIZE]));
        assertTrue(MineService.moveRecord(ownerless, null, "x"), "disbanded: the record goes, adoption restores it");
        assertTrue(ownerless.isEmpty());
        assertFalse(MineService.moveRecord(null, fresh, "y"));
    }

    @Test
    void ticketsDoNotWaitForAPlayerNearTheStore() {
        final long now = 1_000_000L;
        // the old order skipped the ticket whenever the store was not ready — an unloaded store is never ready
        assertTrue(MineService.wantsTickets(false, false, false, 0L, now), "store unloaded: load it");
        assertTrue(MineService.wantsTickets(true, false, true, 0L, now), "working");
        assertTrue(MineService.wantsTickets(false, true, true, now + 99, now), "commit running");
        assertFalse(MineService.wantsTickets(false, false, true, 0L, now), "loaded and refusing (full): no ticket");
        assertFalse(MineService.wantsTickets(false, false, false, now + 1, now), "idle pause after a refusal");
        assertTrue(MineService.wantsTickets(false, false, false, now, now), "pause over: look again");
    }
}
