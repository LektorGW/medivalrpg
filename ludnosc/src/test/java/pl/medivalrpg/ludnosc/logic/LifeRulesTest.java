// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.SiteFinder;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/** Day rhythm, growth, huts and workplaces of the citizens' life. */
class LifeRulesTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    // ------------------------------------------------------------------ day rhythm

    @Test
    void dayRhythm() {
        final DailyRoutine d = DailyRoutine.defaults();
        assertTrue(d.valid());
        assertEquals(DailyRoutine.Phase.MORNING, d.phase(1000));
        assertEquals(DailyRoutine.Phase.WORK, d.phase(3000));
        assertEquals(DailyRoutine.Phase.NOON, d.phase(7000));
        assertEquals(DailyRoutine.Phase.WORK, d.phase(9000));
        assertEquals(DailyRoutine.Phase.EVENING, d.phase(11000));
        assertEquals(DailyRoutine.Phase.NIGHT, d.phase(20000));
        assertEquals(DailyRoutine.Phase.NIGHT, d.phase(24000L * 5 + 13000), "full world time with days");
        assertEquals(DailyRoutine.Place.BED, d.place(18000, true, true, true), "night: home, in bed");
        assertEquals(DailyRoutine.Place.COMMUTE, d.place(500, true, true, true), "morning: to work");
        assertEquals(DailyRoutine.Place.WORK, d.place(4000, true, true, true));
        assertEquals(DailyRoutine.Place.MARKET, d.place(7000, true, true, true), "noon: break at the market");
        assertEquals(DailyRoutine.Place.HOME, d.place(11000, true, true, true), "evening: home");
        assertEquals(DailyRoutine.Place.IDLE, d.place(4000, true, false, true), "jobless look for work");
        assertEquals(DailyRoutine.Place.PLAY, d.place(4000, false, false, true), "children do not work");
        assertEquals("06:00", DailyRoutine.clock(0));
        assertEquals("12:00", DailyRoutine.clock(6000));
        assertEquals("00:00", DailyRoutine.clock(18000));
        assertTrue(!new DailyRoutine(5000, 4000, 8000, 10500, 12500).valid());
    }

    // ------------------------------------------------------------------ growth

    private static final LifeGrowth.Settings GROWTH = new LifeGrowth.Settings(3.0, 40, 0.5, 0.8, 4);

    private static LifeGrowth.Input in(final int pop, final int housing, final long food, final long savings, final int couples) {
        return new LifeGrowth.Input(pop, 0, housing, food, 20, 4, savings, couples, 1.0, 0);
    }

    private static double average(final LifeGrowth.Input input) {
        final Random r = new Random(11);
        double sum = 0;
        for (int i = 0; i < 4000; i++) {
            sum += LifeGrowth.tick(input, GROWTH, r).total();
        }
        return sum / 4000;
    }

    @Test
    void noGrowthWithoutBedsOrFood() {
        final Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertEquals(0, LifeGrowth.tick(in(10, 10, 10_000, 500, 5), GROWTH, r).total(), "no free bed");
            assertEquals(0, LifeGrowth.tick(in(10, 30, 19, 500, 5), GROWTH, r).total(), "food below one settler");
        }
    }

    @Test
    void growthDependsOnFoodAndWealthNotOnlyOnTheTimer() {
        final double hungry = average(in(20, 40, 40, 40, 3));        // half a day of meals
        final double fed = average(in(20, 40, 2000, 40, 3));         // 25 days of meals
        assertTrue(fed > hungry * 2, "a full granary grows faster: " + fed + " vs " + hungry);
        final double poor = average(in(20, 40, 2000, 0, 0));
        final double rich = average(in(20, 40, 2000, 80, 0));
        assertTrue(rich > poor * 2, "wealthy towns attract settlers: " + rich + " vs " + poor);
        final double noCouples = average(in(20, 40, 2000, 40, 0));
        final double couples = average(in(20, 40, 2000, 40, 4));
        assertTrue(couples > noCouples, "couples have children");
    }

    @Test
    void growthNeverExceedsBedsFoodOrCap() {
        final Random r = new Random(9);
        for (int i = 0; i < 2000; i++) {
            final int pop = r.nextInt(30);
            final int housing = r.nextInt(40);
            final long food = r.nextInt(200);
            final LifeGrowth.Outcome o = LifeGrowth.tick(new LifeGrowth.Input(pop, r.nextInt(3), housing, food, 20, 4, r.nextInt(100),
                r.nextInt(6), 0.6 + r.nextDouble() * 0.65, r.nextDouble()), GROWTH, r);
            assertTrue(o.total() <= Math.max(0, housing - pop));
            assertTrue(o.total() <= food / 20);
            assertTrue(o.total() <= GROWTH.maxPerTick());
            assertTrue(o.births() >= 0 && o.settlers() >= 0);
        }
    }

    // ------------------------------------------------------------------ huts

    @Test
    void hutDecision() {
        assertEquals(HutSiting.Decision.ORDER, HutSiting.decide(true, 3, 0, 1, 2000, 1500, true));
        assertEquals(HutSiting.Decision.DISABLED, HutSiting.decide(false, 3, 0, 1, 2000, 1500, true));
        assertEquals(HutSiting.Decision.DISABLED, HutSiting.decide(true, 3, 0, 0, 2000, 1500, true), "limit 0 = off");
        assertEquals(HutSiting.Decision.NO_NEED, HutSiting.decide(true, 0, 0, 1, 2000, 1500, true));
        assertEquals(HutSiting.Decision.LIMIT, HutSiting.decide(true, 3, 1, 1, 2000, 1500, true));
        assertEquals(HutSiting.Decision.NO_MONEY, HutSiting.decide(true, 3, 0, 1, 1499, 1500, true));
        assertEquals(HutSiting.Decision.QUEUE_FULL, HutSiting.decide(true, 3, 0, 1, 2000, 1500, false));
    }

    /** Flat own land in a circle; roads and planned streets given as cells. */
    private record Flat(int radius) implements TerrainView {
        @Override
        public int height(final int x, final int z) {
            return 64;
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return false;
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

    @Test
    void hutPlaceAvoidsRoadsBuildingsPlannedStreetsAndForeignLand() {
        final java.util.Set<Long> roads = new java.util.HashSet<>();
        for (int x = -40; x <= 40; x++) {
            roads.add(RoadPlanner.key(x, 0));                       // a built east-west street
        }
        final java.util.Set<Long> streets = new java.util.HashSet<>();
        for (int z = -40; z <= 40; z++) {
            streets.add(RoadPlanner.key(0, z));                     // a planned north-south street
        }
        final List<BlockBox> taken = List.of(new BlockBox("world", 3, 60, 3, 14, 70, 14));  // a house / field
        final SiteFinder.Settings s = new SiteFinder.Settings(40, 2, 2, 3);
        final SiteFinder.Search search = HutSiting.find(new Flat(36), new int[] {7, 7}, 0, 0, "inner", taken, roads, streets, s, 0);
        final SiteFinder.Spot spot = search.spot();
        assertNotNull(spot, "flat land with room");
        final BlockBox box = pl.medivalrpg.ludnosc.planner.TownPlanner.footprint("world", new int[] {7, 7}, spot);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                assertTrue(!roads.contains(RoadPlanner.key(x, z)), "never on a road");
                assertTrue(!streets.contains(RoadPlanner.key(x, z)), "never on a planned street");
                assertTrue(x * x + z * z <= 36 * 36, "own land only");
            }
        }
        for (final BlockBox t : taken) {
            assertTrue(box.maxX() < t.minX() || box.minX() > t.maxX() || box.maxZ() < t.minZ() || box.minZ() > t.maxZ(),
                "never on another building or field");
        }
        // no own land at all: no place
        assertNull(HutSiting.find(new Flat(2), new int[] {7, 7}, 0, 0, "inner", List.of(), Set.of(), Set.of(), s, 0).spot());
    }

    // ------------------------------------------------------------------ winter, felling

    @Test
    void farmersStayIndoorsInWinterOnly() {
        assertTrue(!Workplaces.fieldsOpen(Season.ZIMA, true));
        assertTrue(Workplaces.fieldsOpen(Season.ZIMA, false), "switch off");
        for (final Season s : new Season[] {Season.WIOSNA, Season.LATO, Season.JESIEN}) {
            assertTrue(Workplaces.fieldsOpen(s, true));
        }
    }

    @Test
    void hutLumberRules() {
        assertNull(HutLumber.refuse(true, false, false, 500, 150, false));
        assertEquals("poza ziemią kraju", HutLumber.refuse(false, false, false, 500, 150, false));
        assertEquals("strefa planu gracza", HutLumber.refuse(true, true, false, 500, 150, false));
        assertEquals("obszar chroniony", HutLumber.refuse(true, false, true, 500, 150, false));
        assertEquals("zbyt blisko spawnu", HutLumber.refuse(true, false, false, 100, 150, false));
        assertEquals("przy budynku lub drodze", HutLumber.refuse(true, false, false, 500, 150, true));
        assertTrue(HutLumber.built("OAK_PLANKS") && HutLumber.built("SPRUCE_STAIRS") && HutLumber.built("GLASS_PANE")
            && HutLumber.built("RED_BED") && HutLumber.built("STRIPPED_OAK_LOG") && HutLumber.built("COBBLESTONE"));
        assertTrue(!HutLumber.built("OAK_LOG") && !HutLumber.built("OAK_LEAVES") && !HutLumber.built("GRASS_BLOCK")
            && !HutLumber.built("DIRT") && !HutLumber.built("STONE") && !HutLumber.built("SHORT_GRASS"));
    }

    @Test
    void woodOnlyWhenTheChestHasRoom() {
        final List<HutLumber.Item> tree = List.of(new HutLumber.Item("OAK_LOG", 70, 64), new HutLumber.Item("STICK", 3, 64));
        final java.util.List<HutLumber.Slot> chest = new java.util.ArrayList<>();
        for (int i = 0; i < 27; i++) {
            chest.add(new HutLumber.Slot("COBBLESTONE", 64, 64));
        }
        assertTrue(!HutLumber.fits(chest, tree), "a full chest: the tree stays");
        chest.set(0, new HutLumber.Slot(null, 0, 0));
        chest.set(1, new HutLumber.Slot("OAK_LOG", 60, 64));
        assertTrue(!HutLumber.fits(chest, tree), "4 + 64 logs fit, 2 logs and the sticks do not");
        chest.set(2, new HutLumber.Slot(null, 0, 0));
        assertTrue(!HutLumber.fits(chest, tree), "still no slot for the sticks");
        chest.set(3, new HutLumber.Slot("#meta", 1, 64));
        chest.set(4, new HutLumber.Slot(null, 0, 0));
        assertTrue(HutLumber.fits(chest, tree));
        assertTrue(!HutLumber.fits(List.of(new HutLumber.Slot("#1", 1, 64)), List.of(new HutLumber.Item("STICK", 1, 64))),
            "an item with metadata never takes a drop");
    }

    // ------------------------------------------------------------------ workplaces

    @Test
    void workplacesByTradeWithSlotsAndProductionJobsKept() {
        final Workplaces.Place smithy = new Workplaces.Place(id(100), Workplaces.Kind.WARSZTAT, "kuznia", 1, "world", 0, 0);
        final Workplaces.Place site = new Workplaces.Place(id(101), Workplaces.Kind.BUDOWA, "dom", 2, "world", 10, 0);
        final Workplaces.Place granary = new Workplaces.Place(id(102), Workplaces.Kind.SPICHLERZ, "spichlerz", 1, "world", 20, 0);
        final Workplaces.Place field = new Workplaces.Place(id(200), Workplaces.Kind.POLE, "", 0, "world", 30, 0);
        final List<Workplaces.Worker> workers = List.of(
            new Workplaces.Worker(id(1), "minecraft:farmer", "world", 0, 0, null),
            new Workplaces.Worker(id(2), "minecraft:armorer", "world", 0, 0, null),
            new Workplaces.Worker(id(3), "minecraft:weaponsmith", "world", 0, 0, null),
            new Workplaces.Worker(id(4), "minecraft:none", "world", 0, 0, null),
            new Workplaces.Worker(id(5), "minecraft:none", "world", 0, 0, null),
            new Workplaces.Worker(id(6), "minecraft:nitwit", "world", 0, 0, null),
            new Workplaces.Worker(id(7), "minecraft:none", "world_nether", 0, 0, null));
        final Map<UUID, Workplaces.Place> board = new HashMap<>(Map.of(id(1), field));
        final Workplaces.Result r = Workplaces.assign(workers, board, List.of(smithy, site, granary));
        assertEquals(field, r.placeOf().get(id(1)), "production jobs come from the job board");
        assertEquals(smithy, r.placeOf().get(id(2)), "a smith works in the smithy");
        assertEquals(Workplaces.Kind.BUDOWA, r.placeOf().get(id(3)).kind(), "the smithy has one slot: the other smith helps building");
        int site2 = 0;
        int granary1 = 0;
        for (final Workplaces.Place p : r.placeOf().values()) {
            site2 += p.equals(site) ? 1 : 0;
            granary1 += p.equals(granary) ? 1 : 0;
        }
        assertEquals(2, site2);
        assertEquals(1, granary1);
        assertEquals(5, r.employed(), "one unemployed in this world, one in another world without places");
        assertNull(r.placeOf().get(id(7)));
        // sticky: a valid current place is kept even if another one is nearer
        final Workplaces.Result again = Workplaces.assign(List.of(new Workplaces.Worker(id(4), "minecraft:none", "world", 20, 0,
            id(101))), Map.of(), List.of(site, granary));
        assertEquals(site, again.placeOf().get(id(4)));
    }
}
