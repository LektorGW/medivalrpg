// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.planner.VillageScan.Kind;
import pl.medivalrpg.ludnosc.planner.VillageScan.Poi;

/** Village detection on synthetic POIs and blocks. */
class VillageScanTest {

    private static Poi poi(final Kind k, final int x, final int z) {
        return new Poi(k, x, 64, z);
    }

    @Test
    void bellIsTheNearestMeetingPoint() {
        final List<Poi> pois = List.of(poi(Kind.MEETING, 300, 300), poi(Kind.HOME, 5, 5), poi(Kind.MEETING, 20, -10));
        assertEquals(20, VillageScan.bell(pois, 0, 0).x());
        assertNull(VillageScan.bell(List.of(poi(Kind.HOME, 1, 1)), 0, 0), "no bell, no village");
    }

    @Test
    void villageCoversBedsJobsAndFieldsAndIsConnected() {
        final Poi bell = poi(Kind.MEETING, 8, 8);
        final List<Poi> pois = new ArrayList<>(List.of(bell,
            poi(Kind.HOME, 20, 8), poi(Kind.HOME, 22, 10),     // chunk (1,0)
            poi(Kind.HOME, -10, 40),                           // chunk (-1,2): diagonal, not adjacent
            poi(Kind.JOB, 8, -20),                             // chunk (0,-2): one chunk gap
            poi(Kind.HOME, 400, 400)));                        // another village far away
        final Map<Long, Integer> farmland = new HashMap<>();
        farmland.put(VillageScan.key(3, 0), 60);   // field east of the village
        farmland.put(VillageScan.key(-6, -6), 80); // far away: not this village
        farmland.put(VillageScan.key(0, 1), 2);    // a single farmland block does not make a field
        final VillageScan.Result r = VillageScan.scan(bell, pois, farmland, 64, 4);
        final Set<Long> c = r.chunks();
        assertTrue(c.contains(VillageScan.key(0, 0)), "bell chunk");
        assertTrue(c.contains(VillageScan.key(1, 0)));
        assertTrue(c.contains(VillageScan.key(-1, 2)));
        assertTrue(c.contains(VillageScan.key(0, -2)));
        assertTrue(c.contains(VillageScan.key(3, 0)), "field");
        assertFalse(c.contains(VillageScan.key(25, 25)), "other village");
        assertFalse(c.contains(VillageScan.key(-6, -6)));
        assertTrue(VillageScan.connected(c), "one continuous territory: " + c.size());
        assertEquals(3, r.beds());
        assertEquals(1, r.jobSites());
        assertEquals(60, r.farmland(), "farmland counted only in village chunks");
        assertFalse(c.contains(VillageScan.key(0, 1)), "two farmland blocks do not make a field");
        assertTrue(c.size() <= 12, "only the connecting chunks are added: " + c.size());
    }

    @Test
    void connectJoinsSeparatedPartsWithStraightPaths() {
        final Set<Long> parts = new HashSet<>(Set.of(VillageScan.key(0, 0), VillageScan.key(5, 0), VillageScan.key(5, 4)));
        final Set<Long> joined = VillageScan.connect(parts, VillageScan.key(0, 0));
        assertTrue(VillageScan.connected(joined));
        assertTrue(joined.containsAll(parts));
        assertEquals(10, joined.size(), "4 between (0,0) and (5,0), 3 between (5,0) and (5,4): " + joined.size());
    }

    // ------------------------------------------------------------------ houses

    /** Solid ground below y=64, air above; walls by position. */
    static final class World implements VillageScan.Blocks {
        final Set<Long> walls = new HashSet<>();

        void wall(final int x, final int y, final int z) {
            this.walls.add(MineDigger.key(x, y, z));
        }

        /** Closed room: interior x0..x1, z0..z1, y 64..66, walls/roof around (door = wall too). */
        void room(final int x0, final int z0, final int x1, final int z1) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    for (int y = 64; y <= 67; y++) {
                        final boolean edge = x < x0 || x > x1 || z < z0 || z > z1;
                        if (edge || y == 67) {
                            this.wall(x, y, z);
                        }
                    }
                }
            }
        }

        @Override
        public int at(final int x, final int y, final int z) {
            if (y < 64 || this.walls.contains(MineDigger.key(x, y, z))) {
                return VillageScan.WALL;
            }
            return VillageScan.OPEN;
        }
    }

    @Test
    void houseIsTheRoomAroundTheBedWithItsWalls() {
        final World w = new World();
        w.room(0, 0, 4, 3);
        final List<VillageScan.House> h = VillageScan.houses(w, List.of(new int[] {1, 64, 1}), 7);
        assertEquals(1, h.size());
        final VillageScan.House house = h.get(0);
        assertEquals(-1, house.minX());
        assertEquals(5, house.maxX());
        assertEquals(-1, house.minZ());
        assertEquals(4, house.maxZ());
        assertEquals(63, house.minY(), "floor");
        assertEquals(67, house.maxY(), "roof");
        assertEquals(1, house.beds());
    }

    @Test
    void twoBedsInOneHouseAndABedInTheOpen() {
        final World w = new World();
        w.room(0, 0, 4, 3);
        w.room(20, 20, 23, 23);
        final List<VillageScan.House> h = VillageScan.houses(w, List.of(new int[] {1, 64, 1}, new int[] {3, 64, 2},
            new int[] {21, 64, 21}, new int[] {50, 64, 50}), 7);
        assertEquals(3, h.size());
        assertEquals(2, h.get(0).beds(), "beds of one room merge into one house");
        final VillageScan.House open = h.stream().filter(x -> x.minX() > 40).findFirst().orElse(null);
        assertNotNull(open, "a bed in the open gets a small box");
        assertEquals(48, open.minX());
        assertEquals(52, open.maxX());
    }

    @Test
    void unknownBlocksMeanNoRoom() {
        final VillageScan.Blocks unknown = (x, y, z) -> y < 64 ? VillageScan.WALL : x > 2 ? VillageScan.UNKNOWN : VillageScan.OPEN;
        final List<VillageScan.House> h = VillageScan.houses(unknown, List.of(new int[] {0, 64, 0}), 7);
        assertEquals(1, h.size());
        assertEquals(-2, h.get(0).minX(), "fallback box");
    }
}
