// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.planner.TerrainView;

class QuarryAndBridgeTest {
    private static TerrainView river(final int width, final boolean owned, final int farBank) {
        return new TerrainView() {
            public int height(final int x, final int z) { return liquid(x, z) ? 62 : x > width ? farBank : 64; }
            public boolean liquid(final int x, final int z) { return x > 0 && x <= width; }
            public boolean obstacle(final int x, final int z) { return false; }
            public boolean own(final int x, final int z) {
                return x >= -2 && x <= width + 3 && z >= -2 && z <= 2 && (owned || x <= width);
            }
        };
    }

    @Test void riverIsCrossedStraightBetweenBanks() {
        final List<int[]> path = BridgeRoute.path(river(5, true, 64), -1, 0, Set.of(), 7, 0, List.of(), 1000);
        assertFalse(path.isEmpty());
        assertEquals(9, path.size());
        for (int i = 1; i < path.size(); i++) {
            assertEquals(1, Math.abs(path.get(i)[0] - path.get(i - 1)[0]) + Math.abs(path.get(i)[1] - path.get(i - 1)[1]));
        }
    }

    @Test void wideRiverForeignBankAndCliffHaveNoCrossing() {
        assertTrue(BridgeRoute.path(river(30, true, 64), -1, 0, Set.of(), 32, 0, List.of(), 1000).isEmpty());
        assertTrue(BridgeRoute.path(river(5, false, 64), -1, 0, Set.of(), 7, 0, List.of(), 1000).isEmpty());
        assertTrue(BridgeRoute.path(river(5, true, 70), -1, 0, Set.of(), 7, 0, List.of(), 1000).isEmpty());
    }

    @Test void aBuildingOnTheBankPreventsTheCrossing() {
        final BlockBox blocked = new BlockBox("world", 6, 60, -2, 6, 70, 2);
        assertTrue(BridgeRoute.path(river(5, true, 64), -1, 0, Set.of(), 7, 0, List.of(blocked), 1000).isEmpty());
    }

    @Test void deckPrecedesRailingsAndPreservesEverythingBelow() {
        final List<BlockPos> cells = java.util.stream.IntStream.range(0, 7)
            .mapToObj(x -> new BlockPos("world", x, x == 0 || x == 6 ? 64 : 62, 0)).toList();
        final var blocks = BridgePlan.blocks(cells, 1);
        assertEquals(31, blocks.size());
        assertTrue(blocks.stream().allMatch(b -> b.y() >= 64));
        assertTrue(blocks.subList(0, 21).stream().allMatch(b -> b.y() == 64 && b.state().contains("planks")));
        assertTrue(blocks.subList(21, blocks.size()).stream().allMatch(b -> b.y() == 65 && Math.abs(b.z()) == 1));
    }

    @Test void quarryHasFiniteTopDownCutsAndAnAccessBenchAtEachDepth() {
        final BlockBox box = new BlockBox("world", 0, 60, 0, 14, 72, 14);
        final var plan = QuarryPlan.cells(box, -64);
        assertFalse(plan.isEmpty());
        int previousY = Integer.MAX_VALUE;
        for (final var c : plan) {
            assertTrue(c.x() >= 3 && c.x() <= 11);
            assertTrue(c.z() >= 3 + (64 - c.y()) && c.z() <= 11);
            assertTrue(c.y() <= previousY);
            previousY = c.y();
        }
        assertTrue(plan.size() < 600);
        assertEquals(plan, QuarryPlan.cells(box, -64)); // same cursor target after a restart
        assertTrue(QuarryPlan.cells(box, 62).stream().allMatch(c -> c.y() > 62));
    }

    @Test void miningCursorAndDailyProductionSurviveSaveAndReload() {
        final var country = java.util.UUID.randomUUID();
        final var data = new pl.medivalrpg.ludnosc.service.LudnoscData();
        data.state(country).quarries().put("pit", new int[] {123, 20752, 17});
        final var loaded = new pl.medivalrpg.ludnosc.service.LudnoscData();
        pl.medivalrpg.ludnosc.storage.DataCodec.apply(pl.medivalrpg.ludnosc.storage.DataCodec.decode(
            pl.medivalrpg.ludnosc.storage.DataCodec.encode(data)), loaded);
        assertArrayEquals(new int[] {123, 20752, 17}, loaded.state(country).quarries().get("pit"));
    }

    @Test void realQuarryFoundationAndAllExplicitTemplateCellsStayIntactAtEveryRotation() throws Exception {
        final var raw = pl.medivalrpg.ludnosc.template.BuiltinTemplates.load("kamieniolom");
        for (int rotation = 0; rotation < 4; rotation++) {
            final var template = raw.rotated(rotation);
            final var box = new BlockBox("world", 100, 60, 200, 100 + template.width() - 1,
                60 + template.height() - 1, 200 + template.depth() - 1);
            final var cells = QuarryPlan.cells(box, -64, template);
            assertFalse(cells.isEmpty());
            assertTrue(cells.size() < QuarryPlan.cells(box, -64).size());
            for (final var c : cells) {
                assertNull(template.state(c.x() - box.minX(), c.y() - box.minY(), c.z() - box.minZ()),
                    "Excavation must not touch an explicit foundation, decoration or template air cell");
            }
        }
    }

    @Test void bridgeBoundsIncludeSideChunkAndReserveRailings() {
        final var path = java.util.stream.IntStream.range(0, 7).mapToObj(x -> new BlockPos("world", x, 64, 15)).toList();
        final var bounds = BridgePlan.bounds(path);
        assertNotNull(bounds);
        assertTrue(bounds.contains("world", 3, 65, 16));
        assertEquals(2, bounds.chunks().size(), "Side deck crosses z=15→16 into a second chunk");
        assertTrue(bounds.chunks().contains(new pl.medivalrpg.ludnosc.model.ChunkRef("world", 0, 1)));
    }

    @Test void aForeignOrObstructedSideOfEitherBankPreventsAdmissionToTheRoute() {
        final var base = river(5, true, 64);
        final TerrainView narrowBanks = new TerrainView() {
            public int height(final int x, final int z) { return base.height(x, z); }
            public boolean liquid(final int x, final int z) { return base.liquid(x, z); }
            public boolean obstacle(final int x, final int z) { return base.obstacle(x, z); }
            public boolean own(final int x, final int z) { return base.own(x, z) && (x != 0 && x != 6 || z == 0); }
        };
        assertTrue(BridgeRoute.path(narrowBanks, -1, 0, Set.of(), 7, 0, List.of(), 1000).isEmpty());
    }
}
