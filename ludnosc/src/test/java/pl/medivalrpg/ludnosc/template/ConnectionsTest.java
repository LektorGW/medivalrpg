// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Connection states recomputed from real neighbours (the "connection pass" after building). */
class ConnectionsTest {

    private static BlockStates.Parsed resolve(final String self, final Map<String, String> around) {
        final Map<String, BlockStates.Parsed> n = new HashMap<>();
        around.forEach((k, v) -> n.put(k, BlockStates.parse(v)));
        return Connections.resolve(BlockStates.parse(self), n::get, Connections::fullBlockSturdy);
    }

    @Test
    void paneBetweenTwoFullBlocks() {
        final BlockStates.Parsed p = resolve("glass_pane[north=false,east=false,south=false,west=false]",
            Map.of("east", "stone_bricks", "west", "cobblestone", "north", "air", "south", "air"));
        assertEquals("true", p.property("east"));
        assertEquals("true", p.property("west"));
        assertEquals("false", p.property("north"));
        assertEquals("false", p.property("south"));
    }

    @Test
    void paneBetweenPanesAndBars() {
        final BlockStates.Parsed p = resolve("glass_pane", Map.of("north", "glass_pane", "south", "iron_bars"));
        assertEquals("true", p.property("north"));
        assertEquals("true", p.property("south"));
        assertEquals("false", p.property("east"));
    }

    @Test
    void paneInACorner() {
        final BlockStates.Parsed p = resolve("light_blue_stained_glass_pane", Map.of("north", "quartz_bricks", "east", "glass_pane"));
        assertEquals("true", p.property("north"));
        assertEquals("true", p.property("east"));
        assertEquals("false", p.property("south"));
        assertEquals("false", p.property("west"));
    }

    @Test
    void paneDoesNotConnectToDecoration() {
        final BlockStates.Parsed p = resolve("glass_pane", Map.of("east", "wall_torch[facing=east]", "west", "oak_door[half=lower]",
            "north", "chest"));
        assertEquals("false", p.property("east"));
        assertEquals("false", p.property("west"));
        assertEquals("false", p.property("north"));
    }

    @Test
    void fenceAtAGate() {
        // gate facing south lies across the east-west line: fences east/west of it connect, north/south do not
        final String gate = "spruce_fence_gate[facing=south,open=false]";
        final BlockStates.Parsed west = resolve("spruce_fence", Map.of("east", gate, "west", "spruce_fence"));
        assertEquals("true", west.property("east"));
        assertEquals("true", west.property("west"));
        final BlockStates.Parsed north = resolve("spruce_fence", Map.of("south", gate));
        assertEquals("false", north.property("south"));
        // nether brick fences don't join wooden ones
        assertEquals("false", resolve("spruce_fence", Map.of("east", "nether_brick_fence")).property("east"));
    }

    @Test
    void gateInWallAndWallPosts() {
        final BlockStates.Parsed gate = resolve("oak_fence_gate[facing=south]", Map.of("east", "cobblestone_wall", "west", "cobblestone_wall"));
        assertEquals("true", gate.property("in_wall"));
        final BlockStates.Parsed straight = resolve("cobblestone_wall", Map.of("north", "cobblestone_wall", "south", "cobblestone_wall"));
        assertEquals("low", straight.property("north"));
        assertEquals("none", straight.property("east"));
        assertEquals("false", straight.property("up"), "straight wall has no post");
        final BlockStates.Parsed corner = resolve("cobblestone_wall", Map.of("north", "cobblestone_wall", "east", "stone"));
        assertEquals("true", corner.property("up"));
        final BlockStates.Parsed covered = resolve("cobblestone_wall",
            Map.of("north", "cobblestone_wall", "south", "cobblestone_wall", "up", "lantern[hanging=false]"));
        assertEquals("false", covered.property("up"), "a lantern is not sturdy");
        final BlockStates.Parsed under = resolve("cobblestone_wall",
            Map.of("north", "cobblestone_wall", "south", "cobblestone_wall", "up", "cobblestone_wall"));
        assertEquals("true", under.property("up"));
    }

    @Test
    void stairShapes() {
        // a stair facing north with a stair facing east in front → outer_right; facing west → outer_left
        assertEquals("outer_right", resolve("oak_stairs[facing=north,half=bottom]",
            Map.of("north", "oak_stairs[facing=east,half=bottom]")).property("shape"));
        assertEquals("outer_left", resolve("oak_stairs[facing=north,half=bottom]",
            Map.of("north", "oak_stairs[facing=west,half=bottom]")).property("shape"));
        // behind it → inner
        assertEquals("inner_left", resolve("oak_stairs[facing=north,half=bottom]",
            Map.of("south", "oak_stairs[facing=west,half=bottom]")).property("shape"));
        assertEquals("straight", resolve("oak_stairs[facing=north,half=bottom]",
            Map.of("north", "oak_stairs[facing=north,half=bottom]")).property("shape"));
        // other half never joins
        assertEquals("straight", resolve("oak_stairs[facing=north,half=bottom]",
            Map.of("north", "oak_stairs[facing=east,half=top]")).property("shape"));
    }
}
