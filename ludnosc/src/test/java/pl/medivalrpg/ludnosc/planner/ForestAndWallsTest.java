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

/** Lumberjacks fell whole trees on their own land only; city walls close the town with gates on the roads. */
class ForestAndWallsTest {

    /** Synthetic forest: trees = trunk of height 5 at (x, 64..68, z) with a 5×5×2 crown + a top leaf. */
    static final class Forest implements TreeFeller.Blocks {
        final Map<Long, Integer> blocks = new HashMap<>();

        void tree(final int x, final int z) {
            for (int y = 64; y <= 68; y++) {
                this.blocks.put(MineDigger.key(x, y, z), TreeFeller.LOG);
            }
            for (int y = 67; y <= 68; y++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        this.blocks.putIfAbsent(MineDigger.key(x + dx, y, z + dz), TreeFeller.LEAF);
                    }
                }
            }
            this.blocks.put(MineDigger.key(x, 69, z), TreeFeller.LEAF);
        }

        @Override
        public int at(final int x, final int y, final int z) {
            return this.blocks.getOrDefault(MineDigger.key(x, y, z), TreeFeller.OTHER);
        }

        void apply(final TreeFeller.Plan p) {
            for (final int[] c : p.remove()) {
                this.blocks.remove(MineDigger.key(c[0], c[1], c[2]));
            }
        }
    }

    @Test
    void wholeTreeIsFelledWithItsCrownAndReplanted() {
        final Forest f = new Forest();
        f.tree(0, 0);
        f.tree(12, 0);
        final TreeFeller.Plan p = TreeFeller.plan(f, (x, z) -> true, 0, 66, 0, 100);
        assertNotNull(p);
        assertEquals(5, p.logs());
        assertTrue(p.leaves() >= 40, "crown: " + p.leaves());
        assertEquals(64, p.sapling()[1], "sapling on the trunk base");
        // crown first (top-down), then the logs
        assertEquals(TreeFeller.LEAF, f.at(p.remove().get(0)[0], p.remove().get(0)[1], p.remove().get(0)[2]));
        f.apply(p);
        for (int x = -3; x <= 3; x++) {
            for (int y = 60; y <= 72; y++) {
                for (int z = -3; z <= 3; z++) {
                    assertEquals(TreeFeller.OTHER, f.at(x, y, z), "nothing floating at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(TreeFeller.LOG, f.at(12, 64, 0), "the neighbouring tree stands");
        assertEquals("minecraft:spruce_sapling", TreeFeller.sapling("minecraft:spruce_log"));
        assertEquals("minecraft:oak_sapling", TreeFeller.sapling("minecraft:dark_oak_log"), "one sapling of a 2×2 tree would not grow");
    }

    @Test
    void neverOnForeignLandAndNeverALogHouse() {
        final Forest f = new Forest();
        f.tree(0, 0);
        assertNull(TreeFeller.plan(f, (x, z) -> x < 0, 0, 65, 0, 100), "the trunk stands on foreign land");
        // a crown reaching over the border: the foreign leaves stay
        final TreeFeller.Plan p = TreeFeller.plan(f, (x, z) -> x <= 1, 0, 65, 0, 100);
        assertNotNull(p);
        assertTrue(p.remove().stream().noneMatch(c -> c[0] > 1), "nothing removed beyond the border");
        // logs without leaves are a building, not a tree
        final Forest house = new Forest();
        for (int y = 64; y <= 67; y++) {
            house.blocks.put(MineDigger.key(5, y, 5), TreeFeller.LOG);
        }
        assertNull(TreeFeller.plan(house, (x, z) -> true, 5, 64, 5, 100));
    }

    @Test
    void leavesTouchingAnotherTreeStay() {
        final Forest f = new Forest();
        f.tree(0, 0);
        f.tree(4, 0); // crowns overlap at x = 2
        final TreeFeller.Plan p = TreeFeller.plan(f, (x, z) -> true, 0, 64, 0, 100);
        f.apply(p);
        assertEquals(TreeFeller.LEAF, f.at(3, 67, 0), "the neighbour's crown is intact");
        assertEquals(TreeFeller.LOG, f.at(4, 66, 0));
    }

    // ------------------------------------------------------------------ city walls

    @Test
    void wallsCloseTheTownWithGatesOnRoadsAndTowers() {
        final List<BlockBox> town = List.of(new BlockBox("w", 0, 64, 0, 10, 70, 10), new BlockBox("w", 20, 64, 4, 30, 70, 14));
        final int[] rect = WallPlanner.bounds(town, 6);
        assertEquals(-6, rect[0]);
        assertEquals(36, rect[2]);
        final List<int[]> ring = WallPlanner.ring(rect);
        // closed loop: consecutive cells are neighbours, also the last and the first
        for (int i = 0; i < ring.size(); i++) {
            final int[] a = ring.get(i);
            final int[] b = ring.get((i + 1) % ring.size());
            assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]), "gap after cell " + i);
        }
        assertEquals(2 * (rect[2] - rect[0] + rect[3] - rect[1]), ring.size(), "each cell once");
        // a road leaving the town to the south crosses the ring
        final Set<Long> roads = new HashSet<>();
        for (int z = 0; z <= 40; z++) {
            roads.add(RoadPlanner.key(15, z));
        }
        final Set<Integer> gates = WallPlanner.gates(ring, roads);
        assertEquals(1, gates.size(), "one gate where the road crosses");
        final List<WallPlanner.Piece> blocks = WallPlanner.blocks(ring, 0, ring.size(), (x, z) -> 64 + (x > 20 ? 2 : 0), roads,
            WallPlanner.Style.of(1), 16, (x, z) -> true);
        final int gate = gates.iterator().next();
        final int[] g = ring.get(gate);
        assertTrue(blocks.stream().noneMatch(p -> p.x() == g[0] && p.z() == g[1] && p.y() <= 64 + 4), "the gate is open");
        assertTrue(blocks.stream().anyMatch(p -> p.x() == g[0] && p.z() == g[1] && p.y() == 64 + 5), "with an arch above");
        // every other cell has its wall standing on its own ground (stepped on the slope, no gaps)
        for (int i = 0; i < ring.size(); i++) {
            if (Math.abs(i - gate) <= 1) {
                continue;
            }
            final int[] c = ring.get(i);
            final int ground = 64 + (c[0] > 20 ? 2 : 0);
            assertTrue(blocks.stream().anyMatch(p -> p.x() == c[0] && p.z() == c[1] && p.y() == ground + 1), "wall at cell " + i);
        }
        // towers on the corners are higher
        final int[] corner = ring.get(0);
        assertTrue(blocks.stream().anyMatch(p -> p.x() == corner[0] && p.z() == corner[1] && p.y() == 64 + 5 + 3), "corner tower");
        // foreign land: the ring skips those cells
        final List<WallPlanner.Piece> clipped = WallPlanner.blocks(ring, 0, ring.size(), (x, z) -> 64, roads, WallPlanner.Style.of(1), 16,
            (x, z) -> x < 30);
        assertFalse(clipped.stream().anyMatch(p -> p.x() >= 30));
    }

    @Test
    void borderIsFortifiedTowardsTheEnemy() {
        final Set<Long> claims = new HashSet<>();
        for (int cx = 0; cx <= 3; cx++) {
            for (int cz = 0; cz <= 3; cz++) {
                claims.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            }
        }
        // enemy capital far to the east
        final List<int[]> edge = WallPlanner.threatenedEdge(claims, 500, 30, Set.of());
        assertEquals(16, edge.size());
        assertTrue(edge.stream().allMatch(c -> c[0] == 3 * 16 + 15), "the eastern edge of the eastern chunks");
        // that stretch done: the next one
        final List<int[]> next = WallPlanner.threatenedEdge(claims, 500, 30, Set.of(WallPlanner.edgeChunk(edge)));
        assertTrue(next != null && WallPlanner.edgeChunk(next) != WallPlanner.edgeChunk(edge));
        assertTrue(next.stream().allMatch(c -> c[0] == 3 * 16 + 15), "still facing the enemy");
        // epochs: palisade, stone, bastions, posts
        assertEquals("minecraft:spruce_log[axis=y]", WallPlanner.Style.palisade().wall());
        assertEquals("minecraft:stone_bricks", WallPlanner.Style.of(1).wall());
        assertEquals("minecraft:gray_concrete", WallPlanner.Style.of(4).wall());
        assertTrue(WallPlanner.Style.of(4).height() < WallPlanner.Style.of(1).height(), "modern posts are low barriers");
    }
}
