// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockStatesTest {

    private static String prop(final String state, final String key) {
        return BlockStates.parse(state).property(key);
    }

    @Test
    void parseNormalisesNamespaceAndCase() {
        final BlockStates.Parsed p = BlockStates.parse("Oak_Stairs[facing=NORTH, half=bottom]");
        assertEquals("minecraft:oak_stairs", p.id());
        assertEquals("oak_stairs", p.path());
        assertEquals("north", p.property("facing"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", p.format());
        assertEquals("minecraft:stone", BlockStates.normalize("stone"));
        assertThrows(IllegalArgumentException.class, () -> BlockStates.parse("stone[facing"));
        assertThrows(IllegalArgumentException.class, () -> BlockStates.parse("sto ne"));
        assertThrows(IllegalArgumentException.class, () -> BlockStates.parse(" "));
    }

    @Test
    void directionsRotateClockwise() {
        assertEquals("east", BlockStates.rotateDirection("north", 1));
        assertEquals("south", BlockStates.rotateDirection("east", 1));
        assertEquals("west", BlockStates.rotateDirection("south", 1));
        assertEquals("north", BlockStates.rotateDirection("west", 1));
        assertEquals("south", BlockStates.rotateDirection("north", 2));
        assertEquals("west", BlockStates.rotateDirection("north", 3));
        assertEquals("west", BlockStates.rotateDirection("north", -1));
        assertEquals("up", BlockStates.rotateDirection("up", 1));
        assertEquals(1, BlockStates.quarterTurns(90));
        assertEquals(3, BlockStates.quarterTurns(-90));
        assertEquals(2, BlockStates.quarterTurns(540));
        assertEquals(0, BlockStates.quarterTurns(360));
    }

    @Test
    void bareRailRotatesLikeTheServerDefaultNorthSouth() {
        // vanilla: minecraft:rail == rail[shape=north_south]; BlockData#rotate(CLOCKWISE_90) gives east_west
        assertEquals("east_west", prop(BlockStates.rotate("minecraft:rail", 1), "shape"));
        assertEquals("east_west", prop(BlockStates.rotate("rail", 3), "shape"));
        assertEquals("minecraft:rail", BlockStates.rotate("minecraft:rail", 2));
        assertEquals("minecraft:rail", BlockStates.rotate("minecraft:rail", 0));
        assertEquals("east_west", prop(BlockStates.rotate("minecraft:powered_rail[powered=false]", 1), "shape"));
        assertEquals("false", prop(BlockStates.rotate("minecraft:powered_rail[powered=false]", 1), "powered"));
        assertEquals("minecraft:rail", BlockStates.rotate("minecraft:rail[shape=east_west]", 1)); // default shape stays implicit
        String r = "minecraft:rail";
        for (int i = 0; i < 4; i++) {
            r = BlockStates.rotate(r, 1);
        }
        assertEquals("minecraft:rail", r);
    }

    @Test
    void stairsDoorsBedsAndTorchesRotateFacing() {
        assertEquals("east", prop(BlockStates.rotate("oak_stairs[facing=north,half=top,shape=inner_left]", 1), "facing"));
        // stair shape and half are relative → unchanged
        assertEquals("inner_left", prop(BlockStates.rotate("oak_stairs[facing=north,half=top,shape=inner_left]", 1), "shape"));
        assertEquals("top", prop(BlockStates.rotate("oak_stairs[facing=north,half=top,shape=inner_left]", 3), "half"));

        final String door = BlockStates.rotate("spruce_door[facing=north,half=lower,hinge=left,open=false]", 1);
        assertEquals("east", prop(door, "facing"));
        assertEquals("left", prop(door, "hinge"));
        assertEquals("lower", prop(door, "half"));

        final String bed = BlockStates.rotate("red_bed[facing=north,part=head]", 2);
        assertEquals("south", prop(bed, "facing"));
        assertEquals("head", prop(bed, "part"));

        assertEquals("north", prop(BlockStates.rotate("wall_torch[facing=west]", 1), "facing"));
        assertEquals("up", prop(BlockStates.rotate("barrel[facing=up]", 1), "facing"));
    }

    @Test
    void axisRotationSwapsXAndZOnOddTurns() {
        assertEquals("z", prop(BlockStates.rotate("spruce_log[axis=x]", 1), "axis"));
        assertEquals("x", prop(BlockStates.rotate("spruce_log[axis=x]", 2), "axis"));
        assertEquals("x", prop(BlockStates.rotate("spruce_log[axis=z]", 3), "axis"));
        assertEquals("y", prop(BlockStates.rotate("spruce_log[axis=y]", 1), "axis"));
    }

    @Test
    void sixteenStepRotationOfBannersAndSigns() {
        assertEquals("12", prop(BlockStates.rotate("red_banner[rotation=8]", 1), "rotation"));
        assertEquals("0", prop(BlockStates.rotate("red_banner[rotation=8]", 2), "rotation"));
        assertEquals("3", prop(BlockStates.rotate("oak_sign[rotation=15]", 1), "rotation"));
    }

    @Test
    void sidePropertiesArePermuted() {
        final String pane = BlockStates.rotate("glass_pane[north=true,east=false,south=true,west=false,waterlogged=false]", 1);
        assertEquals("false", prop(pane, "north"));
        assertEquals("true", prop(pane, "east"));
        assertEquals("false", prop(pane, "south"));
        assertEquals("true", prop(pane, "west"));
        assertEquals("false", prop(pane, "waterlogged"));

        final String wall = BlockStates.rotate("cobblestone_wall[up=true,north=low,east=none,south=none,west=tall]", 1);
        assertEquals("tall", prop(wall, "north"));
        assertEquals("low", prop(wall, "east"));
        assertEquals("none", prop(wall, "south"));

        // partial side sets survive too
        final String vine = BlockStates.rotate("vine[north=true]", 2);
        assertEquals("true", prop(vine, "south"));
        assertEquals(null, prop(vine, "north"));
    }

    @Test
    void railShapes() {
        assertEquals("east_west", BlockStates.rotateRailShape("north_south", 1));
        assertEquals("north_south", BlockStates.rotateRailShape("east_west", 1));
        assertEquals("ascending_east", BlockStates.rotateRailShape("ascending_north", 1));
        assertEquals("south_west", BlockStates.rotateRailShape("south_east", 1));
        assertEquals("north_west", BlockStates.rotateRailShape("south_west", 1));
        assertEquals("north_east", BlockStates.rotateRailShape("north_west", 1));
        assertEquals("south_east", BlockStates.rotateRailShape("north_east", 1));
        assertEquals("straight", BlockStates.rotateRailShape("straight", 1));
    }

    @Test
    void fourQuarterTurnsAreIdentity() {
        for (final String s : new String[] {
            "minecraft:oak_stairs[facing=west,half=bottom,shape=outer_right]",
            "minecraft:glass_pane[north=true,east=true,south=false,west=false]",
            "minecraft:rail[shape=south_east]", "minecraft:red_banner[rotation=5]", "minecraft:oak_log[axis=z]"}) {
            String r = s;
            for (int i = 0; i < 4; i++) {
                r = BlockStates.rotate(r, 1);
            }
            assertEquals(BlockStates.parse(s).properties(), BlockStates.parse(r).properties(), s);
        }
    }

    @Test
    void pointRotationMatchesDirectionRotation() {
        // a 5 (x) × 3 (z) grid: north edge middle (2, 0) goes to the east edge after one clockwise turn
        assertArrayEquals(new int[] {2, 2}, BuildingTemplate.rotatePoint(2, 0, 5, 3, 1));
        // west edge middle (0, 1) → north edge
        assertArrayEquals(new int[] {1, 0}, BuildingTemplate.rotatePoint(0, 1, 5, 3, 1));
        assertArrayEquals(new int[] {4, 2}, BuildingTemplate.rotatePoint(0, 0, 5, 3, 2));
        assertArrayEquals(new int[] {0, 4}, BuildingTemplate.rotatePoint(0, 0, 5, 3, 3));
    }

    @Test
    void airDetection() {
        assertTrue(BlockStates.isAir(BlockStates.AIR));
        assertTrue(BlockStates.isAir("minecraft:cave_air"));
        assertFalse(BlockStates.isAir("minecraft:stone"));
        assertFalse(BlockStates.isAir(null));
    }
}
