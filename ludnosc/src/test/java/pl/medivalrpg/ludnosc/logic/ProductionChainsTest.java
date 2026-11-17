// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.config.WorkshopSettings;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/** Production chains of the villages: material groups of blocks, workshop recipes, fuel, hauler wants, digging rules. */
class ProductionChainsTest {

    private static final Workshops.Params P = Workshops.Params.DEFAULTS;

    @Test
    void blocksCostTheirChainMaterials() {
        assertEquals(Map.of(MaterialNeeds.GLASS, 8), MaterialNeeds.costs("GLASS"));
        assertEquals(Map.of(MaterialNeeds.GLASS, 3), MaterialNeeds.costs("minecraft:glass_pane[north=true]"));
        assertEquals(Map.of(MaterialNeeds.GLASS, 3), MaterialNeeds.costs("LIGHT_BLUE_STAINED_GLASS_PANE"));
        assertEquals(Map.of(MaterialNeeds.BRICK, 4), MaterialNeeds.costs("BRICKS"));
        assertEquals(Map.of(MaterialNeeds.BRICK, 6), MaterialNeeds.costs("BRICK_STAIRS"));
        assertEquals(Map.of(MaterialNeeds.TERRACOTTA, 1), MaterialNeeds.costs("WHITE_TERRACOTTA"));
        assertEquals(Map.of(MaterialNeeds.WOOL, 3), MaterialNeeds.costs("RED_WOOL"));
        assertEquals(Map.of(MaterialNeeds.WOOL, 2), MaterialNeeds.costs("BROWN_CARPET"));
        assertEquals(Map.of(MaterialNeeds.HAY, 9), MaterialNeeds.costs("HAY_BLOCK"));
        assertEquals(Map.of(MaterialNeeds.IRON, 3), MaterialNeeds.costs("IRON_BARS"));
        assertEquals(Map.of(MaterialNeeds.TORCH, 1), MaterialNeeds.costs("WALL_TORCH"));
        assertEquals(Map.of(MaterialNeeds.SAND, 4), MaterialNeeds.costs("SMOOTH_SANDSTONE"));
        assertEquals(Map.of(MaterialNeeds.SAND, 2), MaterialNeeds.costs("SMOOTH_SANDSTONE_SLAB"));
        final Map<String, Integer> bed = MaterialNeeds.costs("RED_BED");
        assertEquals(9, bed.get(MaterialNeeds.WOOL));
        assertEquals(3, bed.get(MaterialNeeds.WOOD));
        assertEquals(MaterialNeeds.WOOL, MaterialNeeds.group("RED_BED"));
        final Map<String, Integer> lantern = MaterialNeeds.costs("LANTERN");
        assertEquals(8, lantern.get(MaterialNeeds.IRON));
        assertEquals(1, lantern.get(MaterialNeeds.TORCH));
        // wood and stone unchanged
        assertEquals(Map.of(MaterialNeeds.STONE, 1), MaterialNeeds.costs("STONE_BRICKS"));
        assertEquals(Map.of(MaterialNeeds.WOOD, 4), MaterialNeeds.costs("OAK_LOG"));
        assertTrue(MaterialNeeds.costs("MUD_BRICKS").isEmpty());
        assertTrue(MaterialNeeds.costs("RESIN_BRICKS").isEmpty());
    }

    @Test
    void storedGoodsSupplyTheirGroupExactly() {
        assertEquals(8, MaterialNeeds.supply("GLASS", MaterialNeeds.GLASS));
        assertEquals(0, MaterialNeeds.supply("SAND", MaterialNeeds.GLASS)); // sand is melted in the glassworks, not on site
        assertEquals(1, MaterialNeeds.supply("SAND", MaterialNeeds.SAND));
        assertEquals(1, MaterialNeeds.supply("BRICK", MaterialNeeds.BRICK));
        assertEquals(0, MaterialNeeds.supply("CLAY_BALL", MaterialNeeds.BRICK));
        assertEquals(3, MaterialNeeds.supply("WHITE_WOOL", MaterialNeeds.WOOL));
        assertEquals(9, MaterialNeeds.supply("HAY_BLOCK", MaterialNeeds.HAY));
        assertEquals(0, MaterialNeeds.supply("WHEAT", MaterialNeeds.HAY));   // baled in the sheepfold's barn
        assertEquals(8, MaterialNeeds.supply("IRON_INGOT", MaterialNeeds.IRON));
        assertEquals(0, MaterialNeeds.supply("IRON_NUGGET", MaterialNeeds.IRON)); // never coins
        assertEquals(0, MaterialNeeds.supply("RAW_IRON", MaterialNeeds.IRON));
        assertEquals(1, MaterialNeeds.supply("TORCH", MaterialNeeds.TORCH));
        assertEquals(1, MaterialNeeds.supply("TERRACOTTA", MaterialNeeds.TERRACOTTA));
        // six glass → sixteen panes: 6 × 8 = 48 units = 16 panes of 3
        assertEquals(16, 6 * MaterialNeeds.supply("GLASS", MaterialNeeds.GLASS) / MaterialNeeds.units("GLASS_PANE"));
        assertEquals("szkło 2 bl. szkła", MaterialNeeds.readable(MaterialNeeds.GLASS, 12));
    }

    @Test
    void coinsCoverAGroupOnlyWithoutItsWorkshop() {
        final Set<BuildingFunction> none = EnumSet.noneOf(BuildingFunction.class);
        for (final String g : MaterialNeeds.GROUPS) {
            assertFalse(MaterialNeeds.producedBy(g, none::contains), g);
        }
        assertTrue(MaterialNeeds.producedBy(MaterialNeeds.GLASS, Set.of(BuildingFunction.GLASSWORKS)::contains));
        assertTrue(MaterialNeeds.producedBy(MaterialNeeds.TERRACOTTA, Set.of(BuildingFunction.BRICKWORKS)::contains));
        assertTrue(MaterialNeeds.producedBy(MaterialNeeds.HAY, Set.of(BuildingFunction.SHEEPFOLD)::contains));
        assertFalse(MaterialNeeds.producedBy(MaterialNeeds.IRON, Set.of(BuildingFunction.SMITHY)::contains)); // no mine
        assertTrue(MaterialNeeds.producedBy(MaterialNeeds.IRON, Set.of(BuildingFunction.SMITHY, BuildingFunction.MINE)::contains));
        assertTrue(MaterialNeeds.producedBy(MaterialNeeds.TORCH, Set.of(BuildingFunction.SMITHY)::contains));
        assertFalse(MaterialNeeds.producedBy(MaterialNeeds.GLASS, Set.of(BuildingFunction.SAWMILL)::contains));
        // waiting is bounded exactly as for wood and stone
        assertFalse(MaterialNeeds.mustWait(false, true, 0, 3_600_000));
        assertTrue(MaterialNeeds.mustWait(true, true, 0, 3_600_000));
        assertFalse(MaterialNeeds.mustWait(true, true, 3_600_000, 3_600_000));
    }

    @Test
    void fuelIsCountedInSmeltsAndCharcoalFirst() {
        assertArrayEquals(new int[] {8, 1, 0, 0}, Workshops.burn(8, 0, 3, 5, 8));
        assertArrayEquals(new int[] {8, 0, 1, 2}, Workshops.burn(8, 2, 0, 5, 8));
        assertArrayEquals(new int[] {3, 0, 0, 0}, Workshops.burn(8, 3, 0, 0, 8));
        assertArrayEquals(new int[] {0, 0, 0, 0}, Workshops.burn(8, 0, 0, 0, 8));
    }

    private static void assertArrayEquals(final int[] a, final int[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b);
    }

    @Test
    void glassworksMeltsSandWithRealFuel() {
        final Workshops.Decision d = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 20, "COAL", 2), 0, Set.of(), P);
        assertNotNull(d.step());
        assertEquals(Workshops.Kind.GLASS, d.step().kind());
        assertEquals(Map.of("SAND", 8, "COAL", 1), d.step().costs());
        assertEquals(Map.of("GLASS", 8), d.step().outputs());
        assertEquals(0, d.step().fuelAfter());
        // leftover smelts are used before new coal
        final Workshops.Step s2 = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 3, "COAL", 2), 5, Set.of(), P).step();
        assertEquals(Map.of("SAND", 3), s2.costs());
        assertEquals(2, s2.fuelAfter());
        // no sand: idle and starving glass
        final Workshops.Decision idle = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("COAL", 9), 0, Set.of(), P);
        assertNull(idle.step());
        assertEquals(MaterialNeeds.GLASS, idle.starving());
    }

    @Test
    void kilnsBurnLogsIntoCharcoalWhenNothingElseBurns() {
        final Workshops.Step first = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 8, "OAK_LOG", 5), 0, Set.of(), P).step();
        assertEquals(Workshops.Kind.CHARCOAL, first.kind());
        assertEquals(Map.of("OAK_LOG", 2), first.costs());
        assertEquals(Map.of("CHARCOAL", 1), first.outputs());
        final Workshops.Step next = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 8, "OAK_LOG", 3, "CHARCOAL", 1), 0, Set.of(), P).step();
        assertEquals(Workshops.Kind.GLASS, next.kind());
        assertEquals(Map.of("SAND", 8, "CHARCOAL", 1), next.costs());
        final Workshops.Decision cold = Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 8, "OAK_LOG", 1), 0, Set.of(), P);
        assertNull(cold.step());
        assertTrue(cold.reason().contains("paliwa"));
    }

    @Test
    void brickworksFiresWhatTheSitesWait() {
        final Map<String, Integer> clay = Map.of("CLAY_BALL", 16, "COAL", 4);
        assertEquals(Workshops.Kind.BRICK, Workshops.next(BuildingFunction.BRICKWORKS, clay, 0, Set.of(), P).step().kind());
        final Workshops.Step t = Workshops.next(BuildingFunction.BRICKWORKS, clay, 0, Set.of(MaterialNeeds.TERRACOTTA), P).step();
        assertEquals(Workshops.Kind.TERRACOTTA, t.kind());
        assertEquals(16, t.costs().get("CLAY_BALL"));
        assertEquals(Map.of("TERRACOTTA", 4), t.outputs());
        final Map<String, Integer> full = Map.of("CLAY_BALL", 16, "COAL", 4, "BRICK", 64, "TERRACOTTA", 16);
        assertNull(Workshops.next(BuildingFunction.BRICKWORKS, full, 0, Set.of(), P).step());
        assertNotNull(Workshops.next(BuildingFunction.BRICKWORKS, full, 0, Set.of(MaterialNeeds.BRICK), P).step());
    }

    @Test
    void smithySmeltsIronAndMakesTorches() {
        final Workshops.Step iron = Workshops.next(BuildingFunction.SMITHY, Map.of("RAW_IRON", 5, "COAL", 1), 0, Set.of(), P).step();
        assertEquals(Map.of("RAW_IRON", 5, "COAL", 1), iron.costs());
        assertEquals(Map.of("IRON_INGOT", 5), iron.outputs());
        assertEquals(3, iron.fuelAfter());
        final Workshops.Step torches = Workshops.next(BuildingFunction.SMITHY, Map.of("SPRUCE_LOG", 2, "COAL", 6), 0, Set.of(), P).step();
        assertEquals(Workshops.Kind.TORCHES, torches.kind());
        assertEquals(Map.of("TORCH", 16), torches.outputs());
        assertEquals(1, torches.costs().get("SPRUCE_LOG"));
        assertEquals(4, torches.costs().get("COAL"));
        assertNull(Workshops.next(BuildingFunction.SMITHY, Map.of("TORCH", 40), 0, Set.of(), P).step());
        assertNull(Workshops.next(BuildingFunction.SMITHY, Map.of("RAW_IRON", 5), 0, Set.of(),
            new Workshops.Params(8, 8, 64, 16, 32, 4, false)).step());
    }

    @Test
    void sheepfoldBalesNineWheatPerBale() {
        final Workshops.Step hay = Workshops.next(BuildingFunction.SHEEPFOLD, Map.of("WHEAT", 20), 0, Set.of(), P).step();
        assertEquals(Map.of("WHEAT", 18), hay.costs());
        assertEquals(Map.of("HAY_BLOCK", 2), hay.outputs());
        assertNull(Workshops.next(BuildingFunction.SHEEPFOLD, Map.of("WHEAT", 8), 0, Set.of(), P).step());
        assertNull(Workshops.next(BuildingFunction.SHEEPFOLD, Map.of("WHEAT", 90, "HAY_BLOCK", 4), 0, Set.of(), P).step());
        assertNotNull(Workshops.next(BuildingFunction.SHEEPFOLD, Map.of("WHEAT", 90, "HAY_BLOCK", 4), 0, Set.of(MaterialNeeds.HAY), P).step());
    }

    /** Thousands of random stores: a step never takes more than lies there, never makes something from nothing. */
    @Test
    void stepsNeverCreateGoodsWithoutInputs() {
        final Random r = new Random(42);
        final String[] items = {"SAND", "RED_SAND", "CLAY_BALL", "COAL", "CHARCOAL", "OAK_LOG", "RAW_IRON", "WHEAT", "TORCH", "BRICK",
            "TERRACOTTA", "HAY_BLOCK"};
        final BuildingFunction[] shops = {BuildingFunction.GLASSWORKS, BuildingFunction.BRICKWORKS, BuildingFunction.SMITHY,
            BuildingFunction.SHEEPFOLD};
        for (int i = 0; i < 5000; i++) {
            final Map<String, Integer> stock = new HashMap<>();
            for (final String it : items) {
                if (r.nextInt(3) == 0) {
                    stock.put(it, r.nextInt(40));
                }
            }
            final int fuel = r.nextInt(10);
            final BuildingFunction f = shops[r.nextInt(shops.length)];
            final Workshops.Step s = Workshops.next(f, stock, fuel, r.nextBoolean() ? Set.of() : Set.of(MaterialNeeds.HAY,
                MaterialNeeds.TERRACOTTA, MaterialNeeds.TORCH), P).step();
            if (s == null) {
                continue;
            }
            assertFalse(s.costs().isEmpty(), s.toString());
            s.costs().forEach((k, v) -> assertTrue(v > 0 && v <= stock.getOrDefault(k, 0), f + " " + s + " " + stock));
            final int smelts = switch (s.kind()) {
                case GLASS, BRICK, IRON, TERRACOTTA, CHARCOAL -> s.outputs().values().iterator().next();
                default -> 0;
            };
            final int burnt = s.costs().getOrDefault("COAL", 0) + s.costs().getOrDefault("CHARCOAL", 0);
            if (s.kind() != Workshops.Kind.TORCHES && s.kind() != Workshops.Kind.HAY && s.kind() != Workshops.Kind.CHARCOAL) {
                assertEquals(fuel + burnt * P.smeltsPerFuel() - smelts, s.fuelAfter(), s.toString()); // fuel is conserved
                assertTrue(s.fuelAfter() >= 0);
            }
        }
    }

    @Test
    void haulersBringWhatTheWorkshopLacks() {
        assertEquals(List.of(new Workshops.Want(Workshops.Input.FUEL, 16), new Workshops.Want(Workshops.Input.LOG, 9)),
            Workshops.wants(BuildingFunction.GLASSWORKS, Map.of("SAND", 8), 0, Set.of(), P, 0));
        assertTrue(Workshops.wants(BuildingFunction.GLASSWORKS, Map.of("SAND", 8, "COAL", 1), 0, Set.of(), P, 0).isEmpty());
        assertTrue(Workshops.wants(BuildingFunction.GLASSWORKS, Map.of(), 0, Set.of(), P, 0).isEmpty());
        final List<Workshops.Want> smithy = Workshops.wants(BuildingFunction.SMITHY, Map.of(), 0, Set.of(), P, 0);
        assertEquals(Workshops.Input.RAW_IRON, smithy.getFirst().input());
        assertEquals(32, smithy.getFirst().amount());
        final List<Workshops.Want> fold = Workshops.wants(BuildingFunction.SHEEPFOLD, Map.of(), 0, Set.of(MaterialNeeds.HAY), P, 2);
        assertEquals(Workshops.Input.WHEAT, fold.getFirst().input());
        assertTrue(Workshops.Input.FUEL.matches("CHARCOAL"));
        assertFalse(Workshops.Input.FUEL.matches("COAL_ORE"));
        assertTrue(Workshops.Input.LOG.matches("STRIPPED_BIRCH_LOG"));
        assertTrue(Workshops.output(BuildingFunction.GLASSWORKS, "GLASS"));
        assertFalse(Workshops.output(BuildingFunction.GLASSWORKS, "SAND"));
        assertFalse(Workshops.output(BuildingFunction.SMITHY, "COAL"));
        assertTrue(Workshops.output(BuildingFunction.SHEEPFOLD, "BLACK_WOOL"));
    }

    @Test
    void diggingKeepsTheShoreWhole() {
        // dry beach sand above the sea, neighbours level: allowed
        assertTrue(Workshops.diggable("SAND", "SAND", 64, true, "air", false, 64, 63));
        assertFalse(Workshops.diggable("SAND", "SAND", 64, true, "air", true, 64, 63));   // water next to it: no new stream
        assertFalse(Workshops.diggable("SAND", "SAND", 64, false, "air", false, 64, 63)); // hole into a cave
        assertFalse(Workshops.diggable("SAND", "SAND", 63, true, "air", false, 64, 63));  // already lower than its neighbours
        assertFalse(Workshops.diggable("SAND", "SAND", 62, true, "air", false, 62, 63));  // below the sea level
        assertFalse(Workshops.diggable("SAND", "SAND", 64, true, "water", false, 64, 63)); // sand under water stays
        assertFalse(Workshops.diggable("SAND", "SAND", 64, true, "solid", false, 64, 63)); // under a building / plant
        assertFalse(Workshops.diggable("SAND", "GRAVEL", 64, true, "air", false, 64, 63));
        // clay of a riverbed under water: allowed; dry clay next to water: not
        assertTrue(Workshops.diggable("CLAY", "CLAY", 58, true, "water", true, 58, 63));
        assertFalse(Workshops.diggable("CLAY", "CLAY", 64, true, "air", true, 64, 63));
        assertFalse(Workshops.diggable("CLAY", "SAND", 58, true, "water", true, 58, 63));
    }

    @Test
    void stockTargetsCapTorchesAndHayEvenWithDemand() {
        // 112 torches in store and a site waiting: no more torches (the haulers deliver from the stock)
        assertNull(Workshops.next(BuildingFunction.SMITHY, Map.of("TORCH", 112, "OAK_LOG", 4, "COAL", 20), 0,
            Set.of(MaterialNeeds.TORCH), P).step());
        assertNotNull(Workshops.next(BuildingFunction.SMITHY, Map.of("TORCH", 40, "OAK_LOG", 4, "COAL", 20), 0,
            Set.of(MaterialNeeds.TORCH), P).step()); // demand doubles the target (64)
        assertNull(Workshops.next(BuildingFunction.SHEEPFOLD, Map.of("WHEAT", 90, "HAY_BLOCK", 16), 0, Set.of(MaterialNeeds.HAY), P).step());
    }

    @Test
    void kilnsGetTheFuelBeforeTheSmithy() {
        final Map<String, Integer> smithy = Map.of("RAW_IRON", 8, "COAL", 10, "OAK_LOG", 6);
        // normally the smithy smelts iron with coal
        assertEquals(Workshops.Kind.IRON, Workshops.next(BuildingFunction.SMITHY, smithy, 0, Set.of(), P, false).step().kind());
        // a kiln is hungry: no coal burnt in the smithy, its logs become charcoal for the kilns
        final Workshops.Step s = Workshops.next(BuildingFunction.SMITHY, smithy, 0, Set.of(), P, true).step();
        assertEquals(Workshops.Kind.CHARCOAL, s.kind());
        assertFalse(s.costs().containsKey("COAL"));
        // smelts already paid for are finished
        final Workshops.Step paid = Workshops.next(BuildingFunction.SMITHY, smithy, 3, Set.of(), P, true).step();
        assertEquals(Workshops.Kind.IRON, paid.kind());
        assertEquals(Map.of("RAW_IRON", 3), paid.costs());
        // no logs, no paid smelts: idle, coal untouched; torches never while kilns starve
        final Workshops.Decision idle = Workshops.next(BuildingFunction.SMITHY, Map.of("COAL", 30, "TORCH", 0), 0, Set.of(MaterialNeeds.TORCH), P, true);
        assertNull(idle.step());
        assertTrue(idle.reason().contains("pierwszeństwo"));
        // the smithy asks for no coal while kilns starve; charcoal of the smithy is its product (goes to the kilns)
        assertTrue(Workshops.wants(BuildingFunction.SMITHY, Map.of("RAW_IRON", 40), 0, Set.of(MaterialNeeds.TORCH), P, 0, true).isEmpty());
        assertTrue(Workshops.output(BuildingFunction.SMITHY, "CHARCOAL"));
        assertFalse(Workshops.output(BuildingFunction.GLASSWORKS, "CHARCOAL"));
        // kilns themselves are unaffected by the flag
        assertEquals(Workshops.Kind.GLASS, Workshops.next(BuildingFunction.GLASSWORKS, Map.of("SAND", 8, "COAL", 1), 0, Set.of(), P, true)
            .step().kind());
    }

    @Test
    void settingsHaveSafeDefaultsAndBounds() {
        final WorkshopSettings d = new WorkshopSettings((org.bukkit.configuration.ConfigurationSection) null);
        assertTrue(d.enabled);
        assertEquals(48, d.sandPerDay);
        assertEquals(8, d.smeltsPerFuel);
        assertEquals(4, d.flock);
        final YamlConfiguration y = new YamlConfiguration();
        y.set("workshops.dig.radius", 9999);
        y.set("workshops.sheep.flock", -3);
        y.set("workshops.enabled", false);
        final WorkshopSettings c = new WorkshopSettings(y);
        assertEquals(48, c.digRadius);
        assertEquals(0, c.flock);
        assertFalse(c.enabled);
    }
}
