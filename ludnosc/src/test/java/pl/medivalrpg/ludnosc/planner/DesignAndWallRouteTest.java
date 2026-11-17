// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;

/** The design plan from the panel and walls that follow the town. */
class DesignAndWallRouteTest {

    private static final String PLAN = """
        {"version":1,
         "zones":[{"type":"mieszkalna","polygon":[[0,0],[40,0],[40,40],[0,40]]},
                  {"type":"nie_ruszac","polygon":[[100,0],[140,0],[140,40],[100,40]]},
                  {"type":"zabytki","polygon":[[-40,0],[-1,0],[-1,40],[-40,40]]},
                  {"type":"las","polygon":[[0,-60],[40,-60],[40,-20],[0,-20]]}],
         "roads":[{"kind":"glowna","points":[[0,50],[60,50]],"width":2}],
         "walls":[{"points":[[-50,-70],[150,-70],[150,60],[-50,60]],"closed":true,"gates":[[20,60]]},
                  {"mode":"follow-border","inset":2}],
         "buildings":[{"type":"palac","x":120,"z":-340,"rotation":90,"priority":5},
                      {"type":"studnia","x":20,"z":20,"rotation":0,"priority":1},
                      {"type":"nieznany","x":0,"z":0,"rotation":45,"priority":3}],
         "options":{"allowDemolish":true,"keepExisting":true,"autoFill":true}}
        """;

    @Test
    void parsesAndValidates() {
        final DesignPlan p = DesignPlan.parse(PLAN);
        assertEquals(4, p.zones.size());
        assertEquals(2, p.walls.size());
        final List<String> w = p.check(Set.of("palac", "studnia", "dom"));
        assertTrue(w.stream().anyMatch(s -> s.contains("nieznany typ nieznany")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("wielokrotnością 90")), w.toString());
        assertEquals(2, w.size(), w.toString());
        assertThrows(IllegalArgumentException.class, () -> DesignPlan.parse("{zones: ["));
        assertThrows(IllegalArgumentException.class, () -> DesignPlan.parse(" "));
        final DesignPlan bad = DesignPlan.parse("{\"version\":2,\"zones\":[{\"type\":\"bagno\",\"polygon\":[[0,0],[1,1]]}],"
            + "\"walls\":[{\"mode\":\"spirala\"}],\"roads\":[{\"points\":[[0,0]]}]}");
        final List<String> bw = bad.check(Set.of());
        assertTrue(bw.stream().anyMatch(s -> s.contains("wersja")));
        assertTrue(bw.stream().anyMatch(s -> s.contains("nieznany typ bagno")));
        assertTrue(bw.stream().anyMatch(s -> s.contains("3 punkty")));
        assertTrue(bw.stream().anyMatch(s -> s.contains("nieznany tryb spirala")));
        assertTrue(bw.stream().anyMatch(s -> s.contains("2 punktów")));
        // a round trip keeps everything
        assertEquals(p.toJson(), DesignPlan.parse(p.toJson()).toJson());
    }

    @Test
    void zonesRestrictTheAutomaticPlanner() {
        final DesignPlan p = DesignPlan.parse(PLAN);
        assertTrue(p.allows(20, 20, "housing", "inner", "dom"), "a house in the residential zone");
        assertFalse(p.allows(20, 20, "economy", "outer", "kuznia"), "no smithy there");
        assertFalse(p.allows(20, -40, "housing", "inner", "dom"), "forest: no building");
        assertTrue(p.allows(20, -40, "economy", "forest", "tartak"), "forest: forestry");
        assertTrue(p.allows(20, -40, "economy", "forest", "tartak_kryty"), "forest: look variant of the sawmill");
        assertFalse(p.allows(20, -40, "economy", "outer", "kuznia_kamienna"), "forest: no smithy of any look");
        assertFalse(p.allows(120, 20, "housing", "inner", "dom"), "untouchable");
        assertTrue(p.untouchable(120, 20));
        assertTrue(p.noDemolition(-20, 20), "heritage zone");
        assertFalse(p.noDemolition(20, 20));
        assertTrue(p.allows(300, 300, "economy", "outer", "kuznia"), "outside the zones with autoFill");
        p.options.autoFill = false;
        assertFalse(p.allows(300, 300, "economy", "outer", "kuznia"), "nothing outside the plan without autoFill");
        p.options.allowDemolish = false;
        assertTrue(p.noDemolition(20, 20), "demolitions off");
    }

    @Test
    void buildingsGoInPriorityOrderAndLinesAreRasterised() {
        final DesignPlan p = DesignPlan.parse(PLAN);
        final List<DesignPlan.Building> order = p.buildingsByPriority();
        assertEquals("studnia", order.get(0).type);
        assertEquals("nieznany", order.get(1).type);
        assertEquals("palac", order.get(2).type);
        final Set<Long> road = p.roadCells();
        assertEquals(61 * 2 + 2, road.size(), "61 cells long, 2 wide (+ the far corner)");
        assertTrue(road.contains(RoadPlanner.key(30, 51)));
        final List<int[]> wall = DesignPlan.cells(p.walls.get(0).points, 1, true);
        for (int i = 1; i < wall.size(); i++) {
            assertEquals(1, Math.abs(wall.get(i)[0] - wall.get(i - 1)[0]) + Math.abs(wall.get(i)[1] - wall.get(i - 1)[1]), "no gap");
        }
    }

    // ------------------------------------------------------------------ walls following the town

    /** Flat own land in a square, a river at x = 70..72, optional lost chunk. */
    static class Land implements TerrainView {
        final Set<Long> lost = new HashSet<>();

        @Override
        public int height(final int x, final int z) {
            return 64;
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return x >= 70 && x <= 72;
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return Math.abs(x) < 200 && Math.abs(z) < 200 && !this.lost.contains(LandPicker.key(x >> 4, z >> 4));
        }
    }

    private static boolean inAny(final List<BlockBox> town, final int x, final int z) {
        return town.stream().anyMatch(b -> x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1);
    }

    @Test
    void theRingEnclosesTheTownAndGrowsWithIt() {
        final Land land = new Land();
        final List<BlockBox> town = List.of(new BlockBox("w", 0, 64, 0, 10, 70, 10), new BlockBox("w", 20, 64, 20, 30, 70, 30));
        final List<int[]> ring = WallRoute.ring(town, 6, land, (x, z) -> inAny(town, x, z));
        assertFalse(ring.isEmpty());
        for (final int[] c : ring) {
            assertFalse(inAny(town, c[0], c[1]), "the wall never cuts the town");
        }
        assertTrue(ring.stream().anyMatch(c -> c[0] < 0) && ring.stream().anyMatch(c -> c[0] > 30), "around the town");
        // the town grows: a new district in the east → a wider ring, the old western stretch stays usable
        final List<BlockBox> grown = List.of(town.get(0), town.get(1), new BlockBox("w", 45, 64, 0, 55, 70, 10));
        final List<int[]> bigger = WallRoute.ring(grown, 6, land, (x, z) -> inAny(grown, x, z));
        assertTrue(bigger.stream().mapToInt(c -> c[0]).max().orElse(0) > ring.stream().mapToInt(c -> c[0]).max().orElse(0));
        final List<Long> old = ring.stream().map(c -> RoadPlanner.key(c[0], c[1])).toList();
        final List<Long> obsolete = WallRoute.obsolete(old, bigger);
        assertFalse(obsolete.isEmpty(), "the old eastern stretch is taken down");
        assertTrue(obsolete.size() < old.size(), "the stretches the new ring reuses stay");
    }

    @Test
    void aRiverReplacesTheWall() {
        final Land land = new Land();
        final List<BlockBox> town = List.of(new BlockBox("w", 40, 64, 0, 62, 70, 30));
        final List<int[]> ring = WallRoute.ring(town, 9, land, (x, z) -> inAny(town, x, z));
        assertTrue(ring.stream().noneMatch(c -> c[0] >= 70 && c[0] <= 72), "no wall in the river");
        assertTrue(WallRoute.naturalDefence(land, 71, 5, 1, 0));
        // a cliff defends too
        final TerrainView cliff = new Land() {
            @Override
            public int height(final int x, final int z) {
                return x > 50 ? 50 : 64;
            }
        };
        assertTrue(WallRoute.naturalDefence(cliff, 50, 5, 1, 0));
    }

    @Test
    void everyRoadOutOfTownGetsAGate() {
        final Land land = new Land();
        final List<BlockBox> town = List.of(new BlockBox("w", 0, 64, 0, 20, 70, 20));
        final List<int[]> ring = WallRoute.ring(town, 6, land, (x, z) -> inAny(town, x, z));
        final Set<Long> roads = new HashSet<>();
        for (int i = -40; i <= 60; i++) {
            roads.add(RoadPlanner.key(10, i)); // north-south street through the town
            roads.add(RoadPlanner.key(i, 10)); // east-west street
        }
        final Set<Integer> gates = WallPlanner.gates(ring, roads);
        assertTrue(gates.size() >= 4, "a gate on each of the four roads out: " + gates.size());
    }

    @Test
    void aLostChunkPullsTheRingOntoOwnLand() {
        final Land land = new Land();
        final List<BlockBox> town = List.of(new BlockBox("w", 0, 64, 0, 30, 70, 30));
        land.lost.add(LandPicker.key(-1, 0)); // the chunk west of the town is lost
        land.lost.add(LandPicker.key(-1, 1));
        final List<int[]> ring = WallRoute.ring(town, 8, land, (x, z) -> inAny(town, x, z));
        assertFalse(ring.isEmpty());
        assertTrue(ring.stream().allMatch(c -> land.own(c[0], c[1])), "only on own land: the ring closes along the new border");
        // follow-border: the line just inside the border
        final Set<Long> claims = new HashSet<>();
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                claims.add(LandPicker.key(cx, cz));
            }
        }
        final List<int[]> border = WallRoute.border(claims, 2);
        assertTrue(border.stream().allMatch(c -> c[0] == 2 || c[0] == 45 || c[1] == 2 || c[1] == 45), "2 blocks inside the border");
        assertEquals(4 * 48 - 4, border.size(), "every edge cell of the 3×3 territory once (corners shared)");
    }
}
