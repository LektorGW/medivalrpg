// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TemplatesTest {

    /** Templates as stored (material slots unresolved). */
    private static List<BuildingTemplate> raw;
    /** Templates at the classic look (level 1, Wieś) — what most geometry tests check. */
    private static List<BuildingTemplate> templates;
    private static final MaterialPalette PALETTE = MaterialPalette.defaults();

    @BeforeAll
    static void load() throws IOException {
        raw = BuiltinTemplates.loadAll();
        templates = raw.stream().map(t -> t.atLevel(PALETTE, 1)).toList();
    }

    private static BuildingTemplate rawOf(final String id) {
        return raw.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private static BuildingTemplate get(final String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyBuiltinParsesAndIsConsistent() {
        assertEquals(BuiltinTemplates.IDS.size(), templates.size());
        for (final BuildingTemplate t : raw) {
            assertEquals(List.of(), TemplateParser.check(t), t.id());
            assertTrue(t.solidCount() > 20, t.id());
            assertTrue(t.meta().cost() > 0, t.id());
        }
    }

    @Test
    void declaredCapacitiesMatchBeds() {
        assertEquals(2, get("chata").bedCount());
        assertEquals(BuildingFunction.HOUSING, get("chata").meta().function());
        assertEquals(4, get("dom").bedCount());
        assertEquals(BuildingFunction.HOUSING, get("dom").meta().function());
        assertEquals(6, get("koszary").bedCount());
        assertFalse(get("koszary").meta().function().housing(), "barracks beds are not citizen housing");
        assertEquals(BuildingFunction.GRANARY, get("spichlerz").meta().function());
        assertTrue(get("spichlerz").containers().size() >= 4, "granary has chests");
        for (final BuildingTemplate t : templates) {
            assertEquals(t.meta().declaredBeds(), t.bedCount(), t.id());
        }
    }

    @Test
    void everyBlockIdIsARealMaterial() {
        for (final BuildingTemplate base : raw) {
            for (int level = 0; level < PALETTE.levels(); level++) {
                final BuildingTemplate t = base.atLevel(PALETTE, level);
                for (final String s : t.distinctStates()) {
                    final String path = BlockStates.parse(BlockStates.colorize(s, "blue")).path();
                    assertNotNull(Material.matchMaterial(path), t.id() + " level " + level + ": " + s);
                }
            }
            assertNotNull(Material.matchMaterial(BlockStates.parse(base.meta().foundationBlock()).path()), base.id());
        }
    }

    /** Every state (after rotation and connection computing) is valid for the MC 26.3 block-state registry report. */
    @Test
    void everyStateMatchesTheVanillaRegistry() throws IOException {
        final com.google.gson.JsonObject reg;
        try (java.io.Reader r = new java.io.InputStreamReader(
            TemplatesTest.class.getResourceAsStream("/mc-block-states.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            reg = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
        }
        for (final BuildingTemplate stored : raw) {
            for (int level = 0; level < PALETTE.levels(); level++) {
                final BuildingTemplate base = stored.atLevel(PALETTE, level);
                assertFalse(base.usesMaterials(), stored.id() + ": every slot resolved at level " + level);
                for (int q = 0; q < 4; q++) {
                    for (final String s : base.rotated(q).colored("green").distinctStates()) {
                        final BlockStates.Parsed p = BlockStates.parse(s);
                        final String where = base.id() + " level " + level + " rot " + q;
                        assertTrue(reg.has(p.id()), where + ": unknown block " + p.id());
                        final com.google.gson.JsonObject props = reg.getAsJsonObject(p.id());
                        for (final var e : p.properties().entrySet()) {
                            assertTrue(props.has(e.getKey()), where + ": " + p.id() + " has no " + e.getKey());
                            boolean ok = false;
                            for (final var v : props.getAsJsonArray(e.getKey())) {
                                ok |= v.getAsString().equals(e.getValue());
                            }
                            assertTrue(ok, where + ": " + s);
                        }
                    }
                }
            }
        }
    }

    @Test
    void countryColourPlaceholder() {
        final BuildingTemplate palac = get("palac");
        assertTrue(palac.usesColor());
        final BuildingTemplate blue = palac.colored("blue");
        assertFalse(blue.usesColor());
        assertTrue(blue.distinctStates().contains("minecraft:blue_wool"));
        assertSame(get("chata"), get("chata").colored("blue"), "nothing to colour");
    }

    @Test
    void exteriorDoorsLeadOutside() {
        for (final String id : List.of("chata", "dom", "koszary", "wieza", "karczma", "kamienica", "ratusz", "kosciol", "palac")) {
            for (int q = 0; q < 4; q++) {
                final BuildingTemplate r = get(id).rotated(q);
                final List<int[]> doors = r.exteriorDoors();
                assertFalse(doors.isEmpty(), id + " rot " + q);
                for (final int[] d : doors) {
                    assertEquals(BlockStates.vector(r.front())[0], d[3], id + " outward = front");
                    assertEquals(BlockStates.vector(r.front())[1], d[4], id + " outward = front");
                }
            }
        }
        // a tower door inside the castle courtyard is not an exterior door
        assertTrue(get("zamek").exteriorDoors().size() < 6);
    }

    @Test
    void buildingsHaveLightDoorsAndRoof() {
        for (final BuildingTemplate t : templates) {
            final List<String> states = t.distinctStates();
            assertTrue(states.stream().anyMatch(s -> s.contains("torch") || s.contains("lantern") || s.contains("lava")
                || s.contains("campfire")), t.id() + " has light");
            // the top two layers hold the roof / battlements (stairs, slabs or solid blocks), never only air
            final int top = t.height() - 1;
            boolean roof = false;
            for (int z = 0; z < t.depth(); z++) {
                for (int x = 0; x < t.width(); x++) {
                    for (int y = top - 1; y <= top; y++) {
                        final String s = t.state(x, y, z);
                        roof |= s != null && !BlockStates.isAir(s);
                    }
                }
            }
            assertTrue(roof, t.id() + " has a roof");
        }
        for (final String id : List.of("chata", "dom", "spichlerz", "koszary", "wieza")) {
            assertTrue(get(id).distinctStates().stream().anyMatch(s -> s.contains("_door")), id + " has a door");
            assertTrue(get(id).distinctStates().stream().anyMatch(s -> s.contains("stairs")), id + " has stairs");
        }
        for (final String id : List.of("chata", "dom", "spichlerz", "koszary")) {
            assertTrue(get(id).distinctStates().stream().anyMatch(s -> s.contains("glass") || s.contains("bars") || s.contains("fence")),
                id + " has windows");
        }
    }

    @Test
    void housesHaveSpawnCellNextToBeds() {
        for (final String id : List.of("chata", "dom", "koszary")) {
            final BuildingTemplate t = get(id);
            final int[] c = t.spawnCell();
            assertNotNull(c, id);
            assertTrue(BlockStates.isAir(t.state(c[0], c[1], c[2])), id);
            assertTrue(BlockStates.isAir(t.state(c[0], c[1] + 1, c[2])), id);
            assertFalse(BlockStates.isAir(t.state(c[0], c[1] - 1, c[2])), id);
        }
    }

    @Test
    void windowsConnectToWalls() {
        // chata: glass pane in the west wall between planks (north/south neighbours) → north=true, south=true
        final BuildingTemplate chata = get("chata");
        boolean found = false;
        for (final String s : chata.distinctStates()) {
            if (s.startsWith("minecraft:glass_pane")) {
                final BlockStates.Parsed p = BlockStates.parse(s);
                assertTrue(p.properties().keySet().containsAll(Set.of("north", "east", "south", "west")), s);
                found |= "true".equals(p.property("north")) && "true".equals(p.property("south"));
            }
        }
        assertTrue(found);
    }

    // ------------------------------------------------------------------ rotation

    @Test
    void rotationKeepsBedsDoorsAndCapacity() {
        for (final BuildingTemplate t : templates) {
            for (int q = 1; q < 4; q++) {
                final BuildingTemplate r = t.rotated(q);
                assertEquals(q, r.rotation());
                assertEquals(List.of(), r.validate(), t.id() + " rot " + q);
                assertEquals(t.bedCount(), r.bedCount(), t.id() + " rot " + q);
                assertEquals(t.solidCount(), r.solidCount());
                assertEquals(t.height(), r.height());
                if (q % 2 == 1) {
                    assertEquals(t.width(), r.depth());
                    assertEquals(t.depth(), r.width());
                } else {
                    assertEquals(t.width(), r.width());
                    assertEquals(t.depth(), r.depth());
                }
            }
        }
    }

    @Test
    void fourQuarterTurnsGiveTheSameTemplate() {
        for (final BuildingTemplate t : templates) {
            final BuildingTemplate r = t.rotated(1).rotated(1).rotated(1).rotated(1);
            assertEquals(t.width(), r.width());
            assertEquals(t.depth(), r.depth());
            for (int i = 0; i < t.volume(); i++) {
                final String a = t.state(i);
                final String b = r.state(i);
                if (a == null) {
                    assertEquals(null, b);
                } else {
                    assertEquals(BlockStates.parse(a).properties(), BlockStates.parse(b).properties(), t.id() + " @" + i);
                    assertEquals(BlockStates.parse(a).id(), BlockStates.parse(b).id());
                }
            }
            assertSame(t, t.rotated(0));
            assertSame(t, t.rotated(4));
        }
    }

    @Test
    void doorStaysInTheFrontWallAndOpensToTheOutside() {
        for (final String id : List.of("chata", "dom", "koszary", "wieza", "spichlerz")) {
            for (int q = 0; q < 4; q++) {
                final BuildingTemplate r = get(id).rotated(q);
                final String front = r.front();
                assertEquals(BlockStates.rotateDirection("south", q), front);
                final int[] out = BlockStates.vector(front);
                int doors = 0;
                for (int i = 0; i < r.volume(); i++) {
                    final String s = r.state(i);
                    if (s == null || !s.contains("_door") || !s.contains("half=lower")) {
                        continue;
                    }
                    doors++;
                    final int x = r.xOf(i);
                    final int y = r.yOf(i);
                    final int z = r.zOf(i);
                    // the door was placed by someone standing outside → it faces into the building
                    assertEquals(BlockStates.rotateDirection("north", q), BlockStates.parse(s).property("facing"), id + " rot " + q);
                    // in front of the door: free air (outside); behind it: air (inside)
                    assertTrue(BlockStates.isAir(r.state(x + out[0], y, z + out[1])), id + " rot " + q + " outside");
                    assertTrue(BlockStates.isAir(r.state(x - out[0], y, z - out[1])), id + " rot " + q + " inside");
                    // the door is on the front edge of the walls: nothing solid further out on this layer
                    for (int k = 2; ; k++) {
                        final int ox = x + k * out[0];
                        final int oz = z + k * out[1];
                        if (!r.inside(ox, y, oz)) {
                            break;
                        }
                        final String o = r.state(ox, y, oz);
                        assertTrue(o == null || BlockStates.isAir(o) || o.contains("torch"), id + " rot " + q + " front edge");
                    }
                }
                assertTrue(doors >= 1, id);
            }
        }
    }

    @Test
    void roofStairsFaceTheRidgeInEveryRotation() {
        // chata: the eave row in front of the door (rot 0: z = depth-1, y = 4) are stairs facing north = away from the front
        final BuildingTemplate chata = get("chata");
        for (int q = 0; q < 4; q++) {
            final BuildingTemplate r = chata.rotated(q);
            final int[] p = BuildingTemplate.rotatePoint(0, chata.depth() - 1, chata.width(), chata.depth(), q);
            final BlockStates.Parsed stair = BlockStates.parse(r.state(p[0], 4, p[1]));
            assertTrue(stair.path().endsWith("_stairs"));
            assertEquals(BlockStates.rotateDirection(r.front(), 2), stair.property("facing"), "rot " + q);
        }
    }

    @Test
    void bedHeadsAgainstTheBackWallAfterRotation() {
        final BuildingTemplate chata = get("chata");
        for (int q = 0; q < 4; q++) {
            final BuildingTemplate r = chata.rotated(q);
            final String back = BlockStates.rotateDirection(r.front(), 2);
            final int[] v = BlockStates.vector(back);
            for (final int[] head : r.bedHeads()) {
                final BlockStates.Parsed bed = BlockStates.parse(r.state(head[0], head[1], head[2]));
                assertEquals(back, bed.property("facing"));
                final String wall = r.state(head[0] + v[0], head[1], head[2] + v[1]);
                assertTrue(wall != null && !BlockStates.isAir(wall), "head touches the back wall, rot " + q);
            }
        }
    }

    @Test
    void logsAndPanesRotateWithTheGrid() {
        final BuildingTemplate chata = get("chata");
        final BuildingTemplate r = chata.rotated(1);
        // a beam along x (front wall, y=4) becomes a beam along z
        final int[] p = BuildingTemplate.rotatePoint(3, 1, chata.width(), chata.depth(), 1);
        assertEquals("x", BlockStates.parse(chata.state(3, 4, 1)).property("axis"));
        assertEquals("z", BlockStates.parse(r.state(p[0], 4, p[1])).property("axis"));
        // a pane between north/south neighbours connects east/west after a quarter turn
        final int[] w = BuildingTemplate.rotatePoint(1, 3, chata.width(), chata.depth(), 1);
        final BlockStates.Parsed pane = BlockStates.parse(r.state(w[0], 2, w[1]));
        assertEquals("minecraft:glass_pane", pane.id());
        assertEquals("true", pane.property("east"));
        assertEquals("true", pane.property("west"));
    }

    @Test
    void buildOrderFollowsTheRealisticPhases() {
        for (final BuildingTemplate t : templates) {
            final BuildPhases.Phase[] phase = BuildPhases.classify(t);
            final int[] order = t.buildOrder();
            int lastRank = -1;
            int lastY = Integer.MIN_VALUE;
            for (final int i : order) {
                final int rank = BuildPhases.rank(phase[i]);
                assertTrue(rank >= lastRank, t.id() + ": phases in order");
                if (rank != lastRank) {
                    lastY = Integer.MIN_VALUE;
                }
                final int y = t.yOf(i);
                if (phase[i] == BuildPhases.Phase.EXCAVATE) {
                    assertTrue(lastY == Integer.MIN_VALUE || y <= lastY, t.id() + ": excavation top-down");
                } else {
                    assertTrue(y >= lastY, t.id() + ": bottom-up within " + phase[i]);
                }
                lastY = y;
                lastRank = rank;
            }
            long nonNull = 0;
            for (int i = 0; i < t.volume(); i++) {
                if (t.state(i) != null) {
                    nonNull++;
                }
            }
            assertEquals(nonNull, order.length, t.id());
        }
    }

    @Test
    void phasesOfTypicalBlocks() {
        final BuildingTemplate dom = get("dom");
        final BuildPhases.Phase[] ph = BuildPhases.classify(dom);
        assertEquals(BuildPhases.Phase.FOUNDATION, ph[dom.index(1, 0, 1)], "floor ring");
        assertEquals(BuildPhases.Phase.FRAME, ph[dom.index(1, 8, 1)], "corner log");
        assertEquals(BuildPhases.Phase.WALL, ph[dom.index(2, 2, 1)], "stone wall");
        assertEquals(BuildPhases.Phase.FLOOR, ph[dom.index(4, 4, 4)], "upper floor planks");
        assertEquals(BuildPhases.Phase.ROOF, ph[dom.index(0, 8, 0)], "eave stair");
        assertEquals(BuildPhases.Phase.OPENING, ph[dom.index(5, 1, 7)], "door");
        assertEquals(BuildPhases.Phase.INTERIOR, ph[dom.index(2, 5, 2)], "bed");
        assertEquals(BuildPhases.Phase.DETAIL, ph[dom.index(4, 2, 8)], "torch");
        assertEquals(BuildPhases.Phase.EXCAVATE, ph[dom.index(5, 1, 4)], "inside air");
        // roof from the eaves to the ridge: the eave is placed before the ridge
        final int[] order = dom.buildOrder();
        int eave = -1;
        int ridge = -1;
        for (int k = 0; k < order.length; k++) {
            if (order[k] == dom.index(0, 8, 0)) {
                eave = k;
            }
            if (order[k] == dom.index(5, 12, 4)) {
                ridge = k;
            }
        }
        assertTrue(eave >= 0 && ridge > eave);
    }

    @Test
    void foundationColumnsCoverTheFloor() {
        final BuildingTemplate chata = get("chata");
        assertEquals(7 * 6, chata.foundationColumns().size());
        final BuildingTemplate pillars = new BuildingTemplate(new BuildingTemplate.Meta("p", "p", "", BuildingFunction.OTHER, 0, 1,
            FoundationMode.PILLARS, "spruce_log", 0, "test"), chata.width(), chata.height(), chata.depth(),
            allCells(chata));
        assertTrue(pillars.foundationColumns().size() < chata.foundationColumns().size());
        assertTrue(pillars.foundationColumns().stream().anyMatch(c -> c[0] == 1 && c[1] == 1), "corner post");
    }

    private static String[] allCells(final BuildingTemplate t) {
        final String[] out = new String[t.volume()];
        for (int i = 0; i < out.length; i++) {
            out[i] = t.state(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ material levels

    /**
     * Buildings already standing on the server were built from the templates before material slots existed; at
     * level 1 (and for stored level 0 = legacy data) the slotted templates must give exactly the same blocks, or the
     * repair scan would see every old building as damaged.
     */
    @Test
    void levelOneIsTheClassicLookOfDeployedTemplates() throws IOException {
        for (final String id : List.of("studnia", "chata", "dom", "spichlerz", "kuznia", "koszary", "wieza")) {
            final BuildingTemplate legacy;
            try (java.io.Reader r = new java.io.InputStreamReader(TemplatesTest.class.getResourceAsStream("/legacy/" + id + ".yml"),
                java.nio.charset.StandardCharsets.UTF_8)) {
                legacy = TemplateParser.parse(YamlConfiguration.loadConfiguration(r), id, "legacy");
            }
            final BuildingTemplate now = rawOf(id).atLevel(PALETTE, MaterialPalette.decode(0));
            assertEquals(legacy.width(), now.width(), id);
            assertEquals(legacy.height(), now.height(), id);
            assertEquals(legacy.depth(), now.depth(), id);
            for (int i = 0; i < legacy.volume(); i++) {
                assertEquals(legacy.state(i), now.state(i), id + " cell " + now.xOf(i) + "," + now.yOf(i) + "," + now.zOf(i));
            }
            assertEquals(legacy.contentHash(), now.contentHash(), id);
        }
    }

    @Test
    void materialsGrowWithTheSettlement() {
        final BuildingTemplate chata = rawOf("chata");
        assertTrue(chata.usesMaterials());
        final List<String> osada = chata.atLevel(PALETTE, 0).distinctStates();
        final List<String> stolica = chata.atLevel(PALETTE, 4).distinctStates();
        assertTrue(osada.stream().anyMatch(s -> s.startsWith("minecraft:hay_block") || s.startsWith("minecraft:oak_stairs")),
            "thatch / oak roof in a hamlet: " + osada);
        assertTrue(stolica.stream().anyMatch(s -> s.startsWith("minecraft:calcite")), "calcite walls in the capital");
        assertTrue(stolica.stream().anyMatch(s -> s.startsWith("minecraft:waxed_cut_copper_stairs")), "copper roof");
        assertTrue(stolica.stream().noneMatch(s -> s.startsWith("minecraft:hay_block")));
        // the same geometry and beds at every level
        for (int level = 0; level < PALETTE.levels(); level++) {
            final BuildingTemplate t = chata.atLevel(PALETTE, level);
            assertEquals(chata.bedCount(), t.bedCount());
            assertEquals(chata.solidCount(), t.solidCount(), "level " + level);
            assertEquals(List.of(), t.validate(), "level " + level);
        }
    }

    @Test
    void slotStatesKeepOrDropProperties() {
        assertEquals("minecraft:oak_log[axis=x]", PALETTE.resolve("minecraft:@frame[axis=x]", 0));
        assertEquals("minecraft:bricks", PALETTE.resolve("minecraft:@wall", 2));
        assertEquals("minecraft:dark_oak_stairs[facing=north,half=bottom]",
            PALETTE.resolve("minecraft:@roof_stairs[facing=north,half=bottom]", 2));
        assertEquals("minecraft:waxed_cut_copper_slab[type=bottom]", PALETTE.resolve("minecraft:@roof_slab[type=bottom]", 4));
        assertEquals("minecraft:stone", PALETTE.resolve("minecraft:stone", 3), "plain states unchanged");
        assertEquals("cobblestone", PALETTE.block("base", 3, java.util.Map.of("base", "cobblestone")), "template override");
        assertThrows(IllegalArgumentException.class, () -> PALETTE.block("nope", 0));
        // stored levels: 0 = data from before material levels = classic look
        assertEquals(1, MaterialPalette.decode(0));
        for (int level = 0; level < 5; level++) {
            assertEquals(level, MaterialPalette.decode(MaterialPalette.encode(level)));
        }
    }

    @Test
    void modernisationCostsAShareOfTheBuilding() {
        final BuildingTemplate dom = rawOf("dom");
        final int slots = dom.slotCount();
        assertTrue(slots > 0 && slots < dom.solidCount(), slots + " / " + dom.solidCount());
        final long cost = pl.medivalrpg.ludnosc.planner.RepairRules.upgradeCost(4000, slots, dom.solidCount(), 0.4);
        assertTrue(cost > 0 && cost < 4000 * 0.4 + 1, "cheaper than a new building: " + cost);
        assertEquals(0, pl.medivalrpg.ludnosc.planner.RepairRules.upgradeCost(4000, 0, 100, 0.4));
        assertTrue(pl.medivalrpg.ludnosc.planner.RepairRules.outdated(true, 0, 2), "legacy (level 1) in a town (level 2)");
        assertFalse(pl.medivalrpg.ludnosc.planner.RepairRules.outdated(true, MaterialPalette.encode(3), 3));
        assertFalse(pl.medivalrpg.ludnosc.planner.RepairRules.outdated(false, 0, 4), "no slots, nothing to modernise");
    }

    // ------------------------------------------------------------------ parser errors

    private static BuildingTemplate parse(final String yaml) {
        final YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(new StringReader(yaml));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return TemplateParser.parse(cfg, "test", "test");
    }

    @Test
    void parserRejectsBrokenTemplates() {
        assertThrows(IllegalArgumentException.class, () -> parse("palette:\n  'A': stone\nlayers:\n  - |\n    AA\n    A\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("palette:\n  'A': stone\nlayers:\n  - |\n    AB\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("palette:\n  '.': stone\nlayers:\n  - |\n    ..\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("layers:\n  - |\n    ..\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("palette:\n  'A': stone\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("function: zamek\npalette:\n  'A': stone\nlayers:\n  - |\n    A\n"));
    }

    @Test
    void parserReportsBrokenBedAndWrongDeclaredBeds() {
        final BuildingTemplate t = parse("beds: 2\npalette:\n  'A': stone\n  'H': red_bed[facing=north,part=head]\n"
            + "layers:\n  - |\n    AAA\n  - |\n    _H_\n");
        final List<String> problems = TemplateParser.check(t);
        assertEquals(2, problems.size(), problems.toString());
        final BuildingTemplate ok = parse("beds: 1\npalette:\n  'A': stone\n  'H': red_bed[facing=north,part=head]\n"
            + "  'F': red_bed[facing=north,part=foot]\nlayers:\n  - |\n    A\n    A\n  - |\n    H\n    F\n");
        assertEquals(List.of(), TemplateParser.check(ok));
    }
}
