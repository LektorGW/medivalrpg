// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.Backoff;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;

/** Town redesign fixes: one level per quarter with gentle slopes, the well rule, the planner back-off. */
class TownGradeTest {

    /** A 3×3 grid of 19×19 quarters with 1-wide streets every 20 blocks, the middle one is the square. */
    private static List<int[]> grid() {
        final List<int[]> out = new ArrayList<>();
        for (int qx = -1; qx <= 1; qx++) {
            for (int qz = -1; qz <= 1; qz++) {
                out.add(new int[] {qx * 20 + 1, qz * 20 + 1, qx * 20 + 19, qz * 20 + 19});
            }
        }
        return out;
    }

    @Test
    void occupiedQuarterGradesTheYardBetweenRealFloorLevelsWithoutTouchingBuildings() {
        final List<TownGrade.Area> buildings = List.of(
            new TownGrade.Area(0, 3, 4, 9, 64), new TownGrade.Area(12, 3, 16, 9, 66));
        final List<TownGrade.Area> flats = TownGrade.flats(List.of(new int[] {0, 0, 16, 12, 74}), null, buildings);
        // The old median of the occupied quarter (74) must not become a blanket plateau over its homes.
        assertEquals(2, flats.size());
        final java.util.function.BiPredicate<Integer, Integer> free = (x, z) -> x >= 0 && x <= 16 && z >= 0 && z <= 12
            && buildings.stream().noneMatch(a -> a.contains(x, z));
        final Map<Long, Integer> targets = TownGrade.targets(0, 0, 16, 12, flats, free, free,
            (x, z) -> false, (x, z) -> x == 8 ? 57 : 74);
        assertEquals(64, targets.get(RoadPlanner.key(5, 6)), "yard flush with first floor");
        assertEquals(66, targets.get(RoadPlanner.key(11, 6)), "yard flush with second floor");
        assertTrue(targets.get(RoadPlanner.key(8, 6)) >= 64, "no natural hollow left in the middle of the yard");
        for (int x = 5; x < 11; x++) {
            assertTrue(Math.abs(targets.get(RoadPlanner.key(x, 6)) - targets.get(RoadPlanner.key(x + 1, 6))) <= 1);
        }
        assertFalse(targets.containsKey(RoadPlanner.key(2, 6)), "existing foundations untouched");
        assertFalse(targets.containsKey(RoadPlanner.key(14, 6)), "existing foundations untouched");
    }

    @Test
    void emptyQuarterStillGetsItsPlannedPlateauAndTheSquareStaysFlat() {
        final List<TownGrade.Area> flats = TownGrade.flats(List.of(new int[] {0, 0, 18, 18, 64},
            new int[] {20, 0, 24, 4, 65}), new int[] {20, 0, 24, 4}, List.of());
        assertEquals(2, flats.size());
        assertEquals(new TownGrade.Area(2, 2, 16, 16, 64), flats.get(0));
        assertEquals(new TownGrade.Area(20, 0, 24, 4, 65), flats.get(1));
    }

    @Test
    void isolatedDeepHolesAndPeaksAreNotFlattenedIntoHugeEarthworks() {
        final List<int[]> quarter = List.of(new int[] {0, 0, 18, 18});
        assertEquals(TerrainView.UNKNOWN, TownGrade.levels(quarter, -1,
            (x, z) -> x == 9 && z == 9 ? 30 : 64)[0]);
        assertEquals(TerrainView.UNKNOWN, TownGrade.levels(quarter, 0,
            (x, z) -> x == 9 && z == 9 ? 90 : 64)[0]);
    }

    @Test
    void flatQuarterDoesNotOverrideProtectedColumns() {
        final Map<Long, Integer> targets = TownGrade.targets(0, 0, 4, 4,
            List.of(new TownGrade.Area(0, 0, 4, 4, 68)),
            (x, z) -> x != 2 || z != 2, (x, z) -> true, (x, z) -> false, (x, z) -> 64);
        assertFalse(targets.containsKey(RoadPlanner.key(2, 2)));
        assertEquals(68, targets.get(RoadPlanner.key(1, 1)));
    }

    @Test
    void neighbouringQuartersGetLevelsTheSlopeCanJoin() {
        // a slope rising 1 block per 2 blocks to the east: medians differ by 10 between neighbouring quarters
        final IntBinaryOperator slope = (x, z) -> 64 + Math.floorDiv(x, 2);
        final List<int[]> rects = grid();
        final int square = 4; // qx = 0, qz = 0
        final int[] levels = TownGrade.levels(rects, square, slope);
        assertEquals(64 + 5, levels[square], "the square keeps its median");
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (levels[i] == TerrainView.UNKNOWN || levels[j] == TerrainView.UNKNOWN) {
                    continue;
                }
                final TownGrade.Area a = new TownGrade.Area(rects.get(i)[0], rects.get(i)[1], rects.get(i)[2], rects.get(i)[3], 0);
                final TownGrade.Area b = new TownGrade.Area(rects.get(j)[0], rects.get(j)[1], rects.get(j)[2], rects.get(j)[3], 0);
                if (a.gap(b) <= 1) {
                    final int bands = i == square || j == square ? TownGrade.BAND : 2 * TownGrade.BAND;
                    assertTrue(Math.abs(levels[i] - levels[j]) <= (a.gap(b) + bands) / TownGrade.RUN,
                        "neighbours " + i + "/" + j + ": " + levels[i] + " vs " + levels[j]);
                }
            }
        }
        // on such a steep hill the outer quarters would need too much earthwork: they stay natural
        assertTrue(java.util.Arrays.stream(levels).anyMatch(l -> l == TerrainView.UNKNOWN), "rough quarters stay natural");
        // gentle terrain: every quarter is levelled, close to its own median
        final int[] gentle = TownGrade.levels(rects, square, (x, z) -> 64 + Math.floorDiv(x, 10));
        assertTrue(java.util.Arrays.stream(gentle).noneMatch(l -> l == TerrainView.UNKNOWN));
    }

    @Test
    void gradingHasFlatQuartersAndGentleStreetsNoTerraces() {
        final List<int[]> rects = grid();
        final IntBinaryOperator hilly = (x, z) -> 64 + Math.floorDiv(x, 8) + Math.floorDiv(z, 12) + ((x * 7 + z * 3) % 3 == 0 ? 1 : 0);
        final int[] levels = TownGrade.levels(rects, 4, hilly);
        final List<TownGrade.Area> flats = new ArrayList<>();
        final List<int[]> domain = new ArrayList<>();
        for (int i = 0; i < rects.size(); i++) {
            final int[] r = rects.get(i);
            assertNotEquals(TerrainView.UNKNOWN, levels[i]);
            final int[] in = TownGrade.interior(r[0], r[1], r[2], r[3]);
            flats.add(new TownGrade.Area(in[0], in[1], in[2], in[3], levels[i]));
            domain.add(new int[] {r[0] - 6, r[1] - 6, r[2] + 6, r[3] + 6});
        }
        final java.util.function.BiPredicate<Integer, Integer> graded = (x, z) -> domain.stream()
            .anyMatch(d -> x >= d[0] && x <= d[2] && z >= d[1] && z <= d[3]);
        final java.util.function.BiPredicate<Integer, Integer> core = (x, z) -> rects.stream()
            .anyMatch(d -> x >= d[0] - 2 && x <= d[2] + 2 && z >= d[1] - 2 && z <= d[3] + 2);
        // the whole town at once (as the quarters are graded one after another: the same function everywhere)
        final Map<Long, Integer> all = new HashMap<>();
        for (final int[] r : rects) {
            final Map<Long, Integer> t = TownGrade.targets(r[0] - 6, r[1] - 6, r[2] + 6, r[3] + 6, flats, graded, core, (x, z) -> false, hilly);
            for (final Map.Entry<Long, Integer> e : t.entrySet()) {
                final Integer before = all.put(e.getKey(), e.getValue());
                if (before != null) {
                    assertEquals(before, e.getValue(), "overlapping regions agree at " + RoadPlanner.keyX(e.getKey()) + ","
                        + RoadPlanner.keyZ(e.getKey()));
                }
            }
        }
        // interiors are flat at their level
        for (final TownGrade.Area a : flats) {
            for (int x = a.minX(); x <= a.maxX(); x++) {
                for (int z = a.minZ(); z <= a.maxZ(); z++) {
                    assertEquals(a.level(), all.get(RoadPlanner.key(x, z)), "flat quarter");
                }
            }
        }
        // no step of more than one block anywhere, and in the town (quarters and streets) no rise of two within two blocks
        for (final Map.Entry<Long, Integer> e : all.entrySet()) {
            final int x = RoadPlanner.keyX(e.getKey());
            final int z = RoadPlanner.keyZ(e.getKey());
            if (!core.test(x, z) || !core.test(x + 2, z) || !core.test(x, z + 2)) {
                continue; // the ramps at the edge of the town meet the wild terrain
            }
            for (final int[] d : new int[][] {{1, 0}, {0, 1}}) {
                final Integer n = all.get(RoadPlanner.key(x + d[0], z + d[1]));
                if (n != null) {
                    assertTrue(Math.abs(n - e.getValue()) <= 1, "step at " + x + "," + z);
                }
                final Integer n2 = all.get(RoadPlanner.key(x + 2 * d[0], z + 2 * d[1]));
                if (n2 != null) {
                    assertTrue(Math.abs(n2 - e.getValue()) <= 1, "slope steeper than 1:2 at " + x + "," + z);
                }
            }
        }
        // columns outside the graded domain are not returned (untouched)
        assertFalse(all.containsKey(RoadPlanner.key(-60, -60)));
    }

    @Test
    void naturalTerrainOutsideTheTownIsTheAnchor() {
        // one quarter at level 70 surrounded by natural ground at 64: the band and street slope down to it
        final List<TownGrade.Area> flats = List.of(new TownGrade.Area(3, 3, 17, 17, 70));
        final java.util.function.BiPredicate<Integer, Integer> graded = (x, z) -> x >= -1 && x <= 21 && z >= -1 && z <= 21;
        final Map<Long, Integer> t = TownGrade.targets(-1, -1, 21, 21, flats, graded, (x, z) -> false, (x, z) -> false, (x, z) -> 64);
        assertEquals(70, t.get(RoadPlanner.key(10, 10)));
        assertTrue(t.get(RoadPlanner.key(-1, 10)) < 70, "the edge comes down towards the natural ground");
        for (int x = -1; x < 21; x++) {
            assertTrue(Math.abs(t.get(RoadPlanner.key(x, 10)) - t.get(RoadPlanner.key(x + 1, 10))) <= 1, "gentle at " + x);
        }
    }

    @Test
    void theWellRuleNeverPicksABridge() throws IOException {
        final List<BuildingTemplate.Meta> catalog = new ArrayList<>();
        // bridges first: the catalogue order must not matter
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            if (!"water".equals(t.meta().zone())) {
                catalog.add(t.meta());
            } else {
                catalog.add(0, t.meta());
            }
        }
        final BuildingChooser.Input in = new BuildingChooser.Input(3, 40, 60, 500, 1000, 5, 0, 0, false, 1_000_000, 1_000_000,
            Map.of("kamienica", 7, "katedra", 1), catalog, 0.2, 0.85, 4, d -> true);
        final BuildingChooser.Choice c = BuildingChooser.choose(in);
        assertEquals("studnia", c.type(), "a town without a well builds the well, never a bridge");
        assertEquals(BuildingChooser.Reason.WELL, c.reason());
        // with a well the bridge is not a "missing well" either
        final BuildingChooser.Choice withWell = BuildingChooser.choose(new BuildingChooser.Input(3, 40, 60, 500, 1000, 5, 0, 0, false,
            1_000_000, 1_000_000, Map.of("kamienica", 7, "studnia", 1), catalog, 0.2, 0.85, 4, d -> true));
        assertNotEquals("most", withWell.type());
        assertNotEquals("most_stalowy", withWell.type());
        // a backed-off type is skipped: the planner moves on
        final BuildingChooser.Choice skipped = BuildingChooser.choose(in.without(Set.of("studnia")));
        assertNotEquals("studnia", skipped.type());
        assertTrue(skipped.type() == null || !skipped.type().startsWith("most"));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertEquals(Backoff.FIRST, Backoff.delay(1));
        assertEquals(2 * Backoff.FIRST, Backoff.delay(2));
        assertEquals(4 * Backoff.FIRST, Backoff.delay(3));
        assertEquals(Backoff.MAX, Backoff.delay(30));
    }
}
