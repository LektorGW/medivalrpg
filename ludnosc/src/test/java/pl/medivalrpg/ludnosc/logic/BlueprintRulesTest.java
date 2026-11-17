// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.FoundationMode;

class BlueprintRulesTest {

    private static final UUID COUNTRY = UUID.fromString("1234abcd-0000-4000-8000-000000000001");

    @Test
    void forbiddenBlocksBecomeAir() {
        for (final String s : List.of("minecraft:spawner", "minecraft:command_block[conditional=false,facing=north]",
            "minecraft:chain_command_block", "minecraft:structure_block[mode=save]", "minecraft:jigsaw", "minecraft:barrier",
            "minecraft:bedrock", "minecraft:end_portal_frame[eye=true,facing=north]", "minecraft:nether_portal[axis=x]",
            "minecraft:shulker_box", "minecraft:red_shulker_box[facing=up]", "minecraft:beacon", "minecraft:dragon_egg",
            "minecraft:reinforced_deepslate", "minecraft:light[level=15,waterlogged=false]", "minecraft:budding_amethyst",
            "minecraft:diamond_ore", "minecraft:deepslate_iron_ore", "minecraft:diamond_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:infested_stone", "minecraft:tnt[unstable=false]", "minecraft:trial_spawner",
            "minecraft:enchanting_table", "minecraft:anvil[facing=north]", "modded:magic_block")) {
            final BlueprintRules.Cell c = BlueprintRules.sanitize(s);
            assertEquals(BlockStates.AIR, c.state(), s);
            assertEquals(BlueprintRules.Change.FORBIDDEN, c.change(), s);
        }
        assertTrue(BlueprintRules.forbidden("spawner"));
        assertFalse(BlueprintRules.forbidden("minecraft:oak_planks"));
        assertFalse(BlueprintRules.forbidden("minecraft:chest[facing=north,type=single,waterlogged=false]"));
        assertFalse(BlueprintRules.forbidden("minecraft:rail[shape=north_south,waterlogged=false]"));
    }

    @Test
    void liquidsAndContentsAreRemoved() {
        assertEquals(new BlueprintRules.Cell(BlockStates.AIR, BlueprintRules.Change.LIQUID), BlueprintRules.sanitize("minecraft:water[level=0]"));
        assertEquals(BlueprintRules.Change.LIQUID, BlueprintRules.sanitize("minecraft:lava[level=0]").change());
        assertEquals(BlueprintRules.Change.LIQUID, BlueprintRules.sanitize("minecraft:kelp_plant").change());
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
            BlueprintRules.sanitize("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]").state());
        assertEquals("minecraft:lectern[facing=east,has_book=false,powered=false]",
            BlueprintRules.sanitize("minecraft:lectern[facing=east,has_book=true,powered=false]").state());
        assertEquals("minecraft:beehive[facing=north,honey_level=0]",
            BlueprintRules.sanitize("minecraft:beehive[facing=north,honey_level=5]").state());
        assertEquals("minecraft:cauldron", BlueprintRules.sanitize("minecraft:lava_cauldron").state());
        assertTrue(BlueprintRules.sanitize("minecraft:chiseled_bookshelf[facing=north,slot_0_occupied=true,slot_1_occupied=false,"
            + "slot_2_occupied=false,slot_3_occupied=false,slot_4_occupied=false,slot_5_occupied=false]").state()
            .contains("slot_0_occupied=false"));
        // ordinary blocks are kept exactly; air stays air
        final BlueprintRules.Cell plank = BlueprintRules.sanitize("minecraft:oak_planks");
        assertEquals(new BlueprintRules.Cell("minecraft:oak_planks", BlueprintRules.Change.NONE), plank);
        assertEquals(BlueprintRules.Change.NONE, BlueprintRules.sanitize("minecraft:cave_air").change());
        assertEquals(BlockStates.AIR, BlueprintRules.sanitize("minecraft:cave_air").state());
        assertEquals(BlueprintRules.Change.FORBIDDEN, BlueprintRules.sanitize("bad[[").change());
    }

    @Test
    void sizeAndBlockLimits() {
        final BlueprintRules.Limits l = new BlueprintRules.Limits(32, 32, 1000, 20);
        assertNull(BlueprintRules.sizeProblem(32, 32, 32, l));
        assertNull(BlueprintRules.sizeProblem(1, 1, 1, l));
        assertNotNull(BlueprintRules.sizeProblem(33, 10, 10, l));
        assertNotNull(BlueprintRules.sizeProblem(10, 10, 33, l));
        assertNotNull(BlueprintRules.sizeProblem(10, 33, 10, l));
        assertNotNull(BlueprintRules.sizeProblem(0, 1, 1, l));
        assertNull(BlueprintRules.blocksProblem(1000, l));
        assertNotNull(BlueprintRules.blocksProblem(1001, l));
        assertNotNull(BlueprintRules.blocksProblem(0, l));
    }

    @Test
    void territoryMustBeOwnInEveryChunk() {
        final UUID other = UUID.randomUUID();
        assertEquals(4, BlueprintRules.chunks(-1, -1, 0, 0).size());
        assertEquals(1, BlueprintRules.chunks(0, 0, 15, 15).size());
        assertTrue(BlueprintRules.onCountryLand(COUNTRY, 0, 0, 31, 15, (cx, cz) -> COUNTRY));
        // one foreign chunk refuses the whole selection
        assertFalse(BlueprintRules.onCountryLand(COUNTRY, 0, 0, 31, 15, (cx, cz) -> cx == 1 ? other : COUNTRY));
        // wilderness refuses too
        assertFalse(BlueprintRules.onCountryLand(COUNTRY, 0, 0, 31, 15, (cx, cz) -> cx == 1 ? null : COUNTRY));
        assertFalse(BlueprintRules.onCountryLand(null, 0, 0, 1, 1, (cx, cz) -> null));
    }

    @Test
    void namesAndCollisions() {
        assertNull(BlueprintRules.nameProblem("Dom_Kupca"));
        assertNull(BlueprintRules.nameProblem("Zamek-Łódź2"));
        assertNotNull(BlueprintRules.nameProblem("a"));
        assertNotNull(BlueprintRules.nameProblem("x".repeat(25)));
        assertNotNull(BlueprintRules.nameProblem("dom kupca"));
        assertNotNull(BlueprintRules.nameProblem("<red>dom"));
        assertNotNull(BlueprintRules.nameProblem("lista"));
        assertNotNull(BlueprintRules.nameProblem(null));
        final List<String> existing = List.of("Dom", "Wieza");
        assertTrue(BlueprintRules.nameTaken("dom", existing, null));
        assertFalse(BlueprintRules.nameTaken("Stajnia", existing, null));
        assertNotNull(BlueprintRules.renameProblem("Dom", "WIEZA", existing));
        assertNull(BlueprintRules.renameProblem("Dom", "DOM", existing), "only the case changes");
        assertNotNull(BlueprintRules.renameProblem("Dom", "Dom", existing));
        assertNull(BlueprintRules.renameProblem("Dom", "Karczma", existing));
        assertNotNull(BlueprintRules.renameProblem("Dom", "zła nazwa", existing));
    }

    @Test
    void idsAndKeys() {
        assertEquals("kopia_1234abcd_abc123", BlueprintRules.templateId(COUNTRY, "abc123"));
        assertTrue(BlueprintRules.isBlueprintId("kopia_1234abcd_abc123"));
        assertFalse(BlueprintRules.isBlueprintId("chata"));
        assertFalse(BlueprintRules.isBlueprintId(null));
        assertThrows(IllegalArgumentException.class, () -> BlueprintRules.templateId(COUNTRY, "../x"));
        final String k = BlueprintRules.newKey(new Random(1), Set.of());
        assertTrue(BlueprintRules.validKey(k));
        assertTrue(BlueprintRules.templateId(COUNTRY, k).matches("[a-z0-9_\\-]+"));
        assertEquals("north", BlueprintRules.direction("północ"));
        assertEquals("west", BlueprintRules.direction("zachod"));
        assertNull(BlueprintRules.direction("gora"));
    }

    @Test
    void costIsWeightedAndBounded() {
        assertEquals(500, BlueprintRules.cost(100, 4, 500, 0));
        assertEquals(4000, BlueprintRules.cost(1000, 4, 500, 0));
        assertEquals(3000, BlueprintRules.cost(1000, 4, 500, 3000));
        assertEquals(0, BlueprintRules.weight("minecraft:air"));
        assertEquals(1, BlueprintRules.weight("minecraft:stone_bricks"));
        assertEquals(10, BlueprintRules.weight("minecraft:bell[attachment=floor,facing=north,powered=false]"));
        assertEquals(2, BlueprintRules.weight("minecraft:lantern[hanging=false,waterlogged=false]"));
        final BuildingTemplate t = template(2, 1, 1, "minecraft:bell[attachment=floor,facing=north,powered=false]", "minecraft:oak_planks");
        assertEquals(11, BlueprintRules.weightedBlocks(t));
    }

    @Test
    void materialListAndStoreNeeds() {
        final BuildingTemplate t = template(4, 2, 1,
            "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:glass",
            "minecraft:oak_log[axis=y]", "minecraft:oak_planks", "minecraft:air", "minecraft:cobblestone");
        final List<Map.Entry<String, Integer>> mats = BlueprintRules.materials(t);
        assertEquals("oak_planks", mats.get(0).getKey());
        assertEquals(3, mats.get(0).getValue());
        assertEquals(4, mats.size());
        final Map<String, Long> store = BlueprintRules.storeNeeds(t);
        assertEquals(3 + 4, store.get(MaterialNeeds.WOOD)); // 3 planks + 1 log (4 units)
        assertEquals(2, store.get(MaterialNeeds.STONE));
        assertEquals(BuildingFunction.HOUSING, BlueprintRules.function(1));
        assertEquals(BuildingFunction.OTHER, BlueprintRules.function(0));
    }

    @Test
    void gridDropsOrphanHalvesAndTrimsAir() {
        // 3 wide, 3 high, 2 deep: a whole bed on the floor row z=0..1 at x=0, a bed foot at x=2 whose head lies outside,
        // a door lower half without its upper half, and an empty top layer
        final int w = 3;
        final int h = 3;
        final int d = 2;
        final String[] raw = new String[w * h * d];
        Arrays.fill(raw, "minecraft:air");
        raw[idx(0, 0, 0, w, d)] = "minecraft:red_bed[facing=north,occupied=false,part=head]";
        raw[idx(0, 0, 1, w, d)] = "minecraft:red_bed[facing=north,occupied=false,part=foot]";
        raw[idx(2, 0, 0, w, d)] = "minecraft:red_bed[facing=north,occupied=false,part=foot]";
        raw[idx(1, 0, 1, w, d)] = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]";
        raw[idx(1, 1, 0, w, d)] = "minecraft:water[level=0]";
        raw[idx(2, 1, 1, w, d)] = "minecraft:spawner";
        final BlueprintRules.Grid g = BlueprintRules.grid(raw, w, h, d, "south");
        assertEquals(2, g.orphans());
        assertEquals(1, g.liquids());
        assertEquals(1, g.forbidden());
        assertEquals(1, g.height(), "empty layers on top are trimmed");
        assertEquals(2, g.blocks(), "only the whole bed stays");
        final BuildingTemplate t = new BuildingTemplate(new BuildingTemplate.Meta("t", "t", "", BuildingFunction.OTHER, 0, 0,
            FoundationMode.FILL, "cobblestone", 0, ""), g.width(), g.height(), g.depth(), g.cells());
        assertEquals(1, t.bedCount());
        assertTrue(t.validate().isEmpty());
    }

    @Test
    void gridTurnsTheDoorSideToTheSouth() {
        // 3×2×3, door in the middle of the NORTH wall leading out to the north (outward = opposite of facing)
        final int w = 3;
        final int h = 2;
        final int d = 3;
        final String[] raw = new String[w * h * d];
        Arrays.fill(raw, "minecraft:stone_bricks");
        for (int y = 0; y < h; y++) {
            raw[idx(1, y, 1, w, d)] = "minecraft:air";
        }
        raw[idx(1, 0, 0, w, d)] = "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]";
        raw[idx(1, 1, 0, w, d)] = "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]";
        raw[idx(0, 0, 1, w, d)] = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
        final BlueprintRules.Grid g = BlueprintRules.grid(raw, w, h, d, null);
        assertEquals("north", g.front(), "detected from the outside door");
        final BuildingTemplate t = new BuildingTemplate(new BuildingTemplate.Meta("t", "t", "", BuildingFunction.OTHER, 0, 0,
            FoundationMode.FILL, "cobblestone", 0, ""), g.width(), g.height(), g.depth(), g.cells());
        // after the half turn the door is in the south wall, facing north, leading out to the south
        assertEquals("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]", t.state(1, 0, 2));
        assertEquals("south", BlueprintRules.detectFront(t));
        // the stairs were in the west column facing north → now east column facing south
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]", t.state(2, 0, 1));
        // explicit front keeps the orientation when it is already south
        assertEquals("south", BlueprintRules.grid(raw, w, h, d, "poludnie").front());
    }

    private static int idx(final int x, final int y, final int z, final int w, final int d) {
        return (y * d + z) * w + x;
    }

    private static BuildingTemplate template(final int w, final int h, final int d, final String... cells) {
        return new BuildingTemplate(new BuildingTemplate.Meta("t", "t", "", BuildingFunction.OTHER, 0, 0, FoundationMode.FILL,
            "cobblestone", 0, ""), w, h, d, cells);
    }
}
