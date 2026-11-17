// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * City walls, failure modes seen on production 2026-10-30/31 (data.json, read only): rings with one-block holes on
 * diagonal stretches, wall / removal sites made of cells scattered around the town (bounding box of up to 35 chunks:
 * never loaded, cursor 0 for a day, blocking every next stretch), gates opening a hole at the far end of a site, and
 * diagonal stretches walled as rows of towers.
 */
final class WallStretchTest {

    /** Own rolling land: gentle ridges everywhere (the shift onto the highest ground changes often, no cliffs). */
    static class Hills implements TerrainView {
        final Set<Long> water = new HashSet<>();

        @Override
        public int height(final int x, final int z) {
            return 64 + (int) Math.round(3 * Math.sin(x / 5.0) + 3 * Math.cos(z / 7.0));
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return this.water.contains(RoadPlanner.key(x, z));
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return Math.abs(x) < 400 && Math.abs(z) < 400;
        }
    }

    private static int holes(final List<int[]> ring, final boolean closed) {
        int holes = 0;
        for (int i = 0; i < (closed ? ring.size() : ring.size() - 1); i++) {
            final int[] a = ring.get(i);
            final int[] b = ring.get((i + 1) % ring.size());
            final int gap = Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
            if (gap >= 2 && gap <= WallRoute.GAP_CLOSE) {
                holes++;
            }
        }
        return holes;
    }

    private static List<int[]> cells(final int... xz) {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            out.add(new int[] {xz[i], xz[i + 1]});
        }
        return out;
    }

    @Test
    void aRingOnHillyGroundHasNoOneBlockHoles() {
        final Hills hills = new Hills();
        final List<BlockBox> town = List.of(new BlockBox("w", 0, 64, 0, 30, 70, 30), new BlockBox("w", 40, 64, 10, 70, 70, 40),
            new BlockBox("w", 10, 64, 50, 35, 70, 80));
        final List<int[]> corners = WallRoute.outline(town, 6);
        double cx = 0;
        double cz = 0;
        for (final int[] c : corners) {
            cx += c[0];
            cz += c[1];
        }
        cx /= corners.size();
        cz /= corners.size();
        final List<int[]> raw = WallRoute.route(corners, cx, cz, hills, (x, z) -> false);
        assertTrue(holes(raw, true) > 0, "the old planning leaves holes on diagonal stretches (production: 14–39 per ring)");
        final List<int[]> ring = WallRoute.ring(town, 6, hills, (x, z) -> false);
        assertEquals(0, holes(ring, true), "every small hole is closed");
        final Set<Long> unique = new HashSet<>();
        for (final int[] c : ring) {
            assertTrue(unique.add(RoadPlanner.key(c[0], c[1])), "each cell once");
        }
    }

    @Test
    void riversAndBuildingsStayOpen() {
        final Hills hills = new Hills();
        hills.water.add(RoadPlanner.key(11, 0));
        final Set<Long> seen = new HashSet<>();
        // a one-block river between the two cells: no wall in the water
        assertTrue(WallRoute.joining(new int[] {10, 0}, new int[] {12, 0}, 4, hills, (x, z) -> false, 0, -50, seen).isEmpty());
        // a building in the way
        assertTrue(WallRoute.joining(new int[] {20, 0}, new int[] {22, 0}, 4, hills, (x, z) -> x == 21, 0, -50, seen).isEmpty());
        // a wide break (lake, foreign land) is never bridged
        assertTrue(WallRoute.joining(new int[] {30, 0}, new int[] {40, 0}, 4, hills, (x, z) -> false, 0, -50, seen).isEmpty());
        // a diagonal step of two: joined 4-connected
        final List<int[]> add = WallRoute.joining(new int[] {50, 0}, new int[] {52, 1}, 4, hills, (x, z) -> false, 0, -50, seen);
        assertFalse(add.isEmpty());
        final List<int[]> line = new ArrayList<>();
        line.add(new int[] {50, 0});
        line.addAll(add);
        line.add(new int[] {52, 1});
        for (int i = 1; i < line.size(); i++) {
            assertEquals(1, Math.abs(line.get(i)[0] - line.get(i - 1)[0]) + Math.abs(line.get(i)[1] - line.get(i - 1)[1]));
        }
    }

    @Test
    void productionRemovalSiteIsCutIntoContinuousStretches() {
        // Zakon Krzyżacki, site rozbiorka_muru 2026-10-30 02:08: 48 cells, bounding box 5×7 chunks, cursor 0 for 22 h
        final List<int[]> site = cells(565, -1856, 566, -1856, 567, -1856, 568, -1856, 569, -1856, 570, -1856, 571, -1856, 572, -1856,
            573, -1856, 574, -1856, 576, -1856, 577, -1856, 578, -1856, 579, -1856, 580, -1856, 581, -1856, 582, -1856, 583, -1856,
            584, -1856, 632, -1762, 631, -1762, 630, -1762, 629, -1762, 628, -1762, 627, -1762, 626, -1762, 625, -1762, 624, -1762,
            621, -1761, 620, -1762, 619, -1762, 618, -1762, 616, -1761, 615, -1760, 614, -1760, 613, -1760, 612, -1760, 611, -1760,
            610, -1759, 609, -1760, 608, -1761, 607, -1762, 606, -1762, 605, -1762, 604, -1762, 603, -1761, 602, -1760, 601, -1760);
        final List<Long> keys = new ArrayList<>();
        for (final int[] c : site) {
            keys.add(RoadPlanner.key(c[0], c[1]));
        }
        assertEquals(10, WallRoute.headRun(keys, 48), "a new removal site takes only the first continuous stretch");
        final List<List<int[]>> parts = WallRoute.stretches(site);
        assertEquals(5, parts.size());
        for (final List<int[]> part : parts) {
            final int minCx = part.stream().mapToInt(c -> c[0] >> 4).min().orElseThrow();
            final int maxCx = part.stream().mapToInt(c -> c[0] >> 4).max().orElseThrow();
            final int minCz = part.stream().mapToInt(c -> c[1] >> 4).min().orElseThrow();
            final int maxCz = part.stream().mapToInt(c -> c[1] >> 4).max().orElseThrow();
            assertTrue((maxCx - minCx + 1) * (maxCz - minCz + 1) <= 4, "a stretch needs at most a few chunks");
        }
    }

    @Test
    void modulesNeverSpanABreakOfTheRing() {
        // Królestwo Węgier, site mur_miejski 2026-10-30 23:52: one module across three breaks (jumps of 33, 16 and 4)
        final List<int[]> ring = cells(1010, 1186, 1009, 1186, 1009, 1187, 1009, 1188, 1009, 1189, 1009, 1190, 1009, 1191,
            1009, 1192, 1009, 1193, 1009, 1194, 1009, 1195, 1009, 1196, 1009, 1197, 1009, 1198, 1009, 1199, 1008, 1199, 975, 1199,
            974, 1199, 959, 1213, 958, 1213, 957, 1213, 956, 1213, 955, 1213, 954, 1213, 953, 1213, 952, 1213, 951, 1213, 950, 1213,
            950, 1212, 949, 1212, 945, 1215, 945, 1214);
        final List<int[]> mods = WallRoute.modules(ring, 32, (x, z) -> false, 24, null);
        assertEquals(4, mods.size());
        int total = 0;
        for (final int[] m : mods) {
            total += m[1] - m[0];
            for (int i = m[0] + 1; i < m[1]; i++) {
                assertTrue(WallRoute.touching(ring.get(i - 1), ring.get(i)), "one module = one continuous stretch");
            }
        }
        assertEquals(ring.size(), total, "every cell in exactly one module");
        // a long straight ring is still cut by the module size
        final List<int[]> straight = new ArrayList<>();
        for (int x = 0; x < 100; x++) {
            straight.add(new int[] {x, 0});
        }
        assertEquals(4, WallRoute.modules(straight, 32, (x, z) -> false, 24, null).size());
    }

    @Test
    void anOlderRingGetsItsHolesClosedWithoutRebuildingFinishedStretches() {
        // a ring of 12 cells with two holes: one in a finished stretch, one in a stretch still to build
        final List<int[]> ring = cells(0, 0, 1, 0, 2, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0, 10, 0, 11, 0, 12, 0, 13, 0);
        final List<Long> keys = new ArrayList<>();
        for (final int[] c : ring) {
            keys.add(RoadPlanner.key(c[0], c[1]));
        }
        final boolean[] pending = new boolean[keys.size()];
        for (int i = 6; i < 12; i++) {
            pending[i] = true; // module {6, 12} still to build
        }
        final Hills flat = new Hills() {
            @Override
            public int height(final int x, final int z) {
                return 64;
            }
        };
        final WallRoute.Spliced s = WallRoute.spliceGaps(keys, pending, flat, (x, z) -> false);
        assertEquals(14, s.cells().size(), "both holes closed (the closing pair 13→0 is a wide break and stays)");
        final List<int[]> mods = WallRoute.remapModules(List.of(new int[] {6, 12}), s, keys.size());
        assertEquals(2, mods.size());
        assertEquals(1, mods.get(0)[1] - mods.get(0)[0], "the hole in the finished stretch is a small module of its own");
        assertEquals(RoadPlanner.key(3, 0), s.cells().get(mods.get(0)[0]));
        assertEquals(7, mods.get(1)[1] - mods.get(1)[0], "the pending module takes its own hole with it");
        assertTrue(s.cells().subList(mods.get(1)[0], mods.get(1)[1]).contains(RoadPlanner.key(9, 0)));
    }

    // ------------------------------------------------------------------ blocks of one site

    private static boolean tower(final List<WallPlanner.Piece> pieces, final int x, final int z) {
        return pieces.stream().anyMatch(p -> p.x() == x && p.z() == z && p.state().equals(WallPlanner.Style.of(1).tower()));
    }

    @Test
    void aGateAtOneEndNeverOpensTheOtherEnd() {
        final List<int[]> stretch = new ArrayList<>();
        for (int x = 0; x < 32; x++) {
            stretch.add(new int[] {x, 0});
        }
        final Set<Long> roads = Set.of(RoadPlanner.key(0, 0));
        final List<WallPlanner.Piece> old = WallPlanner.blocks(stretch, 0, stretch.size(), (x, z) -> 64, roads,
            WallPlanner.Style.of(1), 16, (x, z) -> true);
        assertTrue(old.stream().noneMatch(p -> p.x() == 31 && p.y() == 65), "the old ring logic wrapped: a hole at the far end");
        final List<WallPlanner.Piece> now = WallPlanner.section(stretch, (x, z) -> 64, roads, WallPlanner.Style.of(1), 16, (x, z) -> true);
        assertTrue(now.stream().anyMatch(p -> p.x() == 31 && p.y() == 65), "the far end is walled");
        assertTrue(now.stream().noneMatch(p -> p.x() == 1 && p.y() == 65), "the gate is open next to the road");
    }

    @Test
    void aDiagonalStretchIsAWallNotARowOfTowers() {
        final List<int[]> stairs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            stairs.add(new int[] {i, i});
            stairs.add(new int[] {i + 1, i});
        }
        final List<WallPlanner.Piece> old = WallPlanner.blocks(stairs, 0, stairs.size(), (x, z) -> 64, Set.of(),
            WallPlanner.Style.of(1), 16, (x, z) -> true);
        final long oldTowers = stairs.stream().filter(c -> tower(old, c[0], c[1])).count();
        assertTrue(oldTowers > 20, "every step was a 'corner' with a tower and a lantern: " + oldTowers);
        final List<WallPlanner.Piece> now = WallPlanner.section(stairs, (x, z) -> 64, Set.of(), WallPlanner.Style.of(1), 16, (x, z) -> true);
        final long towers = stairs.stream().filter(c -> tower(now, c[0], c[1])).count();
        assertEquals(2, towers, "towers only every 16 cells");
    }

    @Test
    void aRealCornerGetsOneTowerAndAShortRepairNone() {
        final List<int[]> corner = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            corner.add(new int[] {x, 0});
        }
        for (int z = 1; z <= 10; z++) {
            corner.add(new int[] {10, z});
        }
        final List<WallPlanner.Piece> p = WallPlanner.section(corner, (x, z) -> 64, Set.of(), WallPlanner.Style.of(1), 16, (x, z) -> true);
        assertTrue(tower(p, 10, 0), "a tower on the corner itself");
        final long towers = corner.stream().filter(c -> tower(p, c[0], c[1])).count();
        assertEquals(3, towers, "cell 0, cell 16 and one at the corner");
        final List<int[]> repair = cells(3, 0, 3, 1);
        final List<WallPlanner.Piece> r = WallPlanner.section(repair, (x, z) -> 64, Set.of(), WallPlanner.Style.of(1), 16, (x, z) -> true);
        assertFalse(tower(r, 3, 0) || tower(r, 3, 1), "closing a hole adds plain wall");
        assertEquals(64 + 5, r.stream().filter(q -> q.x() == 3 && q.z() == 1).mapToInt(WallPlanner.Piece::y).max().orElse(0));
    }
}
