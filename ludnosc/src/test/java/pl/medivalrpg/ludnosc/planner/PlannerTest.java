// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;

class PlannerTest {

    static List<BuildingTemplate.Meta> catalog;
    static Map<String, int[]> sizes;

    @BeforeAll
    static void load() throws IOException {
        catalog = new ArrayList<>();
        sizes = new HashMap<>();
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            catalog.add(t.meta());
            sizes.put(t.id(), new int[] {t.width(), t.depth()});
        }
    }

    /** Building type of a choice (look variants such as dom_szachulec count as their base type dom). */
    static String base(final BuildingChooser.Choice c) {
        for (final BuildingTemplate.Meta m : catalog) {
            if (m.id().equals(c.type())) {
                return m.baseType();
            }
        }
        return c.type();
    }

    /** Synthetic terrain: height function, everything within {@code radius} of (0,0) is own land. */
    record Map2D(IntBinaryOperator h, int radius, Set<Long> water) implements TerrainView {
        @Override
        public int height(final int x, final int z) {
            return this.h.applyAsInt(x, z);
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
            return x * x + z * z <= this.radius * this.radius;
        }
    }

    static BuildingChooser.Input input(final int level, final int pop, final int housing, final long food, final long cap,
                                       final long balance, final boolean war, final long treasury, final long budget,
                                       final Map<String, Integer> counts) {
        return new BuildingChooser.Input(level, pop, housing, food, cap, balance, 0, 0, war, treasury, budget, counts, catalog,
            0.2, 0.85);
    }

    // ------------------------------------------------------------------ levels

    @Test
    void settlementLevels() {
        final SettlementLevels l = new SettlementLevels(SettlementLevels.DEFAULT);
        assertEquals(0, l.index(0, 0));
        assertEquals(0, l.index(7, 1_000_000));
        assertEquals(1, l.index(8, 2_000));
        assertEquals(1, l.index(25, 5_000), "treasury holds the town back");
        assertEquals(2, l.index(25, 10_000));
        assertEquals(4, l.index(100, 1_000_000));
        assertEquals("Stolica", l.level(4).name());
        assertEquals(5, l.index(200, 1_000_000));
        assertEquals("Metropolia", l.level(5).name());
        assertNull(l.next(5));
        assertEquals("Wieś", l.next(0).name());
    }

    // ------------------------------------------------------------------ what to build

    @Test
    void firstTheWellThenHousing() {
        assertEquals("studnia", BuildingChooser.choose(input(0, 4, 0, 0, 300, 0, false, 5000, 5000, Map.of())).type());
        final BuildingChooser.Choice c = BuildingChooser.choose(input(0, 4, 0, 0, 300, 0, false, 5000, 5000, Map.of("studnia", 1)));
        assertEquals(BuildingChooser.Reason.HOUSING, c.reason());
        assertTrue(base(c).equals("dom") || base(c).equals("chata"));
    }

    @Test
    void housingWhenFreeBedsBelowTwentyPercent() {
        // a village with its workshops: 10 people, 11 beds → 1 free < 2 → housing
        final Map<String, Integer> village = Map.of("studnia", 1, "koszary", 1, "kuznia", 1, "mlyn", 1, "stajnia", 1, "karczma", 1);
        assertEquals(BuildingChooser.Reason.HOUSING,
            BuildingChooser.choose(input(1, 10, 11, 0, 300, 10, false, 50_000, 50_000, village)).reason());
        // 10 people, 14 beds → enough beds → something else
        assertFalse(BuildingChooser.choose(input(1, 10, 14, 0, 300, 10, false, 50_000, 50_000, village))
            .reason() == BuildingChooser.Reason.HOUSING);
        // nobody has a free bed → housing before anything else
        assertEquals(BuildingChooser.Reason.HOUSING,
            BuildingChooser.choose(input(1, 10, 10, 0, 300, 10, false, 50_000, 50_000, Map.of("studnia", 1))).reason());
        // free beds but few workshops: growth first
        assertEquals(BuildingChooser.Reason.GROWTH,
            BuildingChooser.choose(input(1, 6, 7, 0, 300, 10, false, 50_000, 50_000, Map.of("studnia", 1))).reason());
    }

    @Test
    void housingPrefersTownhousesAtHigherLevel() {
        final Map<String, Integer> c = Map.of("studnia", 1);
        assertEquals("kamienica", base(BuildingChooser.choose(input(2, 30, 30, 0, 300, 10, false, 50_000, 50_000, c))));
        assertEquals("dom", base(BuildingChooser.choose(input(1, 10, 10, 0, 300, 10, false, 50_000, 50_000, c))));
        // can't afford a dom → a chata
        assertEquals("chata", base(BuildingChooser.choose(input(0, 4, 4, 0, 300, 10, false, 2_000, 2_000, c))));
    }

    @Test
    void essentialsWithoutMoneyWait() {
        final BuildingChooser.Choice c = BuildingChooser.choose(input(0, 4, 0, 0, 300, 0, false, 500, 500, Map.of("studnia", 1)));
        assertNull(c.type());
        assertEquals(BuildingChooser.Reason.NO_FUNDS, c.reason());
    }

    @Test
    void farmsWhenEatingMoreThanProduced() {
        final BuildingChooser.Choice c = BuildingChooser.choose(input(1, 10, 14, 50, 300, -8, false, 50_000, 50_000,
            Map.of("studnia", 1)));
        assertEquals("pole", c.type());
        assertEquals(BuildingChooser.Reason.FARM, c.reason());
    }

    @Test
    void granaryWhenNearlyFull() {
        assertEquals("spichlerz", base(BuildingChooser.choose(input(1, 10, 14, 280, 300, 10, false, 50_000, 50_000,
            Map.of("studnia", 1, "pole", 1)))));
    }

    @Test
    void warBuildsDefences() {
        final BuildingChooser.Choice c = BuildingChooser.choose(input(1, 10, 14, 100, 300, 10, true, 50_000, 50_000,
            Map.of("studnia", 1)));
        assertEquals(BuildingChooser.Reason.DEFENSE, c.reason());
        assertTrue(Set.of("wieza", "mur", "brama").contains(base(c)));
    }

    @Test
    void richCapitalBuildsThePalace() {
        final Map<String, Integer> all = new HashMap<>();
        for (final BuildingTemplate.Meta m : catalog) {
            if (!m.id().equals("palac")) {
                all.put(m.id(), 1);
            }
        }
        final BuildingChooser.Choice c = BuildingChooser.choose(input(4, 80, 100, 100, 3000, 10, false, 400_000, 200_000, all));
        assertEquals("palac", c.type());
        // not before Stolica
        final BuildingChooser.Choice c2 = BuildingChooser.choose(input(3, 80, 100, 100, 3000, 10, false, 400_000, 200_000, all));
        assertFalse("palac".equals(c2.type()));
        // and not twice
        all.put("palac", 1);
        assertFalse("palac".equals(BuildingChooser.choose(input(4, 80, 100, 100, 3000, 10, false, 400_000, 200_000, all)).type()));
    }

    // ------------------------------------------------------------------ where

    @Test
    void flatSpotNearTheCentreForTheWell() {
        final Map2D flat = new Map2D((x, z) -> 64, 60, Set.of());
        final SiteFinder.Spot s = SiteFinder.find(flat, sizes.get("studnia"), 0, 0, "center", List.of(), Set.of(),
            new SiteFinder.Settings(40, 2, 2, 3));
        assertNotNull(s);
        assertTrue(Math.hypot(s.x(), s.z()) <= 3, "centre");
        assertEquals(64, s.y());
    }

    @Test
    void steepGroundIsAvoidedWhenFlatExists() {
        // flat plateau in the east, steep slope in the west
        final Map2D terrain = new Map2D((x, z) -> x > 10 ? 70 : 70 + (10 - x), 60, Set.of());
        final SiteFinder.Spot s = SiteFinder.find(terrain, sizes.get("dom"), 0, 0, "inner", List.of(), Set.of(),
            new SiteFinder.Settings(40, 2, 2, 3));
        assertNotNull(s);
        assertTrue(s.slope() <= 3, "slope " + s.slope());
        assertTrue(s.x() > 10, "on the plateau: " + s);
    }

    @Test
    void onASlopeTheBuildingFollowsTheContourAndFacesUphill() {
        // the only land: a slope rising to the south (1 per 4 blocks)
        final Map2D slope = new Map2D((x, z) -> 64 + Math.floorDiv(z, 4), 60, Set.of());
        final SiteFinder.Spot s = SiteFinder.find(slope, sizes.get("koszary"), 0, 0, "inner", List.of(), Set.of(),
            new SiteFinder.Settings(30, 2, 2, 4));
        assertNotNull(s);
        // koszary is wider (13) than deep (9): long side along x = along the contour; door south = uphill
        assertEquals(0, s.rotation());
    }

    @Test
    void waterAndForeignLandAreRejected() {
        final Set<Long> lake = new HashSet<>();
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                lake.add(RoadPlanner.key(x, z));
            }
        }
        final Map2D map = new Map2D((x, z) -> 64, 40, lake);
        final SiteFinder.Spot s = SiteFinder.find(map, sizes.get("chata"), 0, 0, "center", List.of(), Set.of(),
            new SiteFinder.Settings(40, 2, 2, 3));
        assertNotNull(s);
        final BlockBox box = TownPlanner.footprint("w", sizes.get("chata"), s);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                assertFalse(lake.contains(RoadPlanner.key(x, z)), "no water");
                assertTrue(map.own(x, z), "own land");
            }
        }
    }

    @Test
    void spacingToOtherPlots() {
        final Map2D flat = new Map2D((x, z) -> 64, 60, Set.of());
        final BlockBox taken = new BlockBox("w", -10, 64, -10, 10, 70, 10);
        final SiteFinder.Spot s = SiteFinder.find(flat, sizes.get("chata"), 0, 0, "center", List.of(taken), Set.of(),
            new SiteFinder.Settings(40, 2, 2, 3));
        final BlockBox box = TownPlanner.footprint("w", sizes.get("chata"), s);
        assertFalse(box.minX() <= 12 && box.maxX() >= -12 && box.minZ() <= 12 && box.maxZ() >= -12, "gap of 2: " + box);
    }

    // ------------------------------------------------------------------ roads

    @Test
    void roadFindsTheNetworkAndAvoidsBuildings() {
        final Map2D flat = new Map2D((x, z) -> 64, 60, Set.of());
        final Set<Long> roads = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            roads.add(RoadPlanner.key(x, 0));
        }
        final BlockBox wall = new BlockBox("w", -3, 64, 5, 3, 70, 7);
        final List<int[]> path = RoadPlanner.path(flat, 0, 12, roads, 0, 0, List.of(wall), 5000);
        assertFalse(path.isEmpty());
        assertEquals(0, path.get(path.size() - 1)[1], "ends on the road");
        for (int i = 1; i < path.size(); i++) {
            assertEquals(1, Math.abs(path.get(i)[0] - path.get(i - 1)[0]) + Math.abs(path.get(i)[1] - path.get(i - 1)[1]));
            final int[] p = path.get(i);
            assertFalse(p[0] >= -3 && p[0] <= 3 && p[1] >= 5 && p[1] <= 7, "around the building");
        }
    }

    @Test
    void roadsDoNotClimbWalls() {
        // a 3-block cliff at z = 5 with a gentle ramp at x 10..14
        final Map2D map = new Map2D((x, z) -> z >= 5 ? (x >= 10 && x <= 14 ? 64 + Math.min(3, z - 4) : 67) : 64, 60, Set.of());
        final List<int[]> path = RoadPlanner.path(map, 0, 12, Set.of(), 0, 0, List.of(), 20000);
        assertFalse(path.isEmpty());
        for (int i = 1; i < path.size(); i++) {
            assertTrue(Math.abs(map.height(path.get(i)[0], path.get(i)[1]) - map.height(path.get(i - 1)[0], path.get(i - 1)[1])) <= 1);
        }
    }

    @Test
    void roadDecorUsesSlabsOnClimbsAndLamps() {
        final List<int[]> path = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            path.add(new int[] {x, 0});
        }
        final List<RoadDecor.Block> blocks = RoadDecor.blocks(path, (x, z) -> 64 + (x >= 10 ? 1 : 0), "minecraft:dirt_path",
            "minecraft:cobblestone_slab[type=bottom]", 8, (x, z) -> true);
        assertEquals(20, blocks.stream().filter(b -> b.state().equals("minecraft:dirt_path")).count());
        assertTrue(blocks.stream().anyMatch(b -> b.state().contains("slab") && b.x() == 9 && b.y() == 65), "half step");
        assertEquals(2, blocks.stream().filter(b -> b.state().contains("lantern")).count(), "lamps every 8");
    }

    // ------------------------------------------------------------------ entrance

    @Test
    void entranceIsWalkableForLowerGround() {
        final int doorY = 66;
        for (int drop = 0; drop <= 3; drop++) {
            final int ground = doorY - 1 - drop;
            final List<Entrance.Step> plan = Entrance.plan(doorY, d -> ground, 8);
            assertEquals(drop, Entrance.stairs(plan), "stairs for a drop of " + drop);
            // walking surface from the ground to the door: ground top+1, stair halves, door level
            double level = ground + 1;
            for (int d = drop; d >= 1; d--) {
                final double stairTop = doorY - d + 1;
                assertTrue(stairTop - 0.5 - level <= 0.5, "half step onto stair " + d);
                level = stairTop;
            }
            assertTrue(doorY - level <= 0.0001, "reaches the threshold");
            for (final Entrance.Step s : plan) {
                assertTrue(s.y() > ground, "never below the ground");
            }
        }
    }

    @Test
    void entranceDigsThroughHigherGround() {
        final List<Entrance.Step> plan = Entrance.plan(66, d -> 67, 8);
        assertEquals(0, Entrance.stairs(plan));
        assertTrue(plan.stream().anyMatch(s -> s.kind() == Entrance.Kind.CLEAR && s.y() == 66));
    }

    // ------------------------------------------------------------------ tickets / virtual progress

    @Test
    void ticketsWithinTheLimitByPriority() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID c = UUID.randomUUID();
        final List<TicketAllocator.Demand> demands = List.of(
            new TicketAllocator.Demand(a, Set.of(new ChunkRef("w", 0, 0), new ChunkRef("w", 1, 0))),
            new TicketAllocator.Demand(b, Set.of(new ChunkRef("w", 5, 5), new ChunkRef("w", 6, 5), new ChunkRef("w", 7, 5))),
            new TicketAllocator.Demand(c, Set.of(new ChunkRef("w", 1, 0), new ChunkRef("w", 2, 0))));
        final TicketAllocator.Allocation al = TicketAllocator.allocate(demands, 4);
        assertEquals(Set.of(a, c), al.sites(), "b does not fit whole; c shares a chunk with a");
        assertEquals(3, al.chunks().size());
        assertEquals(Set.of(), TicketAllocator.allocate(demands, 1).sites());
    }

    @Test
    void virtualProgressAccumulatesButIsCapped() {
        assertEquals(10.0, TicketAllocator.credit(0, 5, 2.0, 100));
        assertEquals(12.0, TicketAllocator.credit(0, 1000, 2.0, 10), "never more than remaining + one period");
        assertEquals(3.0, TicketAllocator.credit(3, 0, 2.0, 100));
    }
}
