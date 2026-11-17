// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.planner.Underground.Kind;
import pl.medivalrpg.ludnosc.planner.Underground.Op;
import pl.medivalrpg.ludnosc.planner.Underground.Type;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;
import pl.medivalrpg.ludnosc.template.MaterialPalette;

/** Cellars, dungeons, tunnels and catacombs on synthetic ground. */
class UndergroundTest {

    /** Stone below y=64, air above; special blocks by position. */
    static final class Ground {
        final Map<Long, String> blocks = new HashMap<>();

        String at(final int x, final int y, final int z) {
            final String s = this.blocks.get(MineDigger.key(x, y, z));
            return s != null ? s : y < 64 ? "minecraft:stone" : "minecraft:air";
        }

        void put(final int x, final int y, final int z, final String s) {
            this.blocks.put(MineDigger.key(x, y, z), s);
        }

        static boolean open(final String s) {
            return s.endsWith(":air") || s.startsWith("minecraft:water") || s.startsWith("minecraft:lava") || s.contains("gravel");
        }

        /** Applies the plan like the server does (seal only open/liquid/falling cells). */
        void apply(final List<Op> ops) {
            for (final Op op : ops) {
                final String cur = this.at(op.x(), op.y(), op.z());
                switch (op.type()) {
                    case SEAL -> {
                        if (open(cur)) {
                            this.put(op.x(), op.y(), op.z(), Underground.WALL);
                        }
                    }
                    case DIG -> this.put(op.x(), op.y(), op.z(), "minecraft:air");
                    case PLACE -> this.put(op.x(), op.y(), op.z(), op.state());
                }
            }
        }

        boolean solid(final int x, final int y, final int z) {
            final String s = this.at(x, y, z);
            return !open(s) && !s.contains("torch") && !s.contains("ladder") && !s.contains("candle") && !s.contains("skull");
        }
    }

    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    @Test
    void everyStateIsARealBlockState() throws IOException {
        final com.google.gson.JsonObject reg;
        try (java.io.Reader r = new java.io.InputStreamReader(UndergroundTest.class.getResourceAsStream("/mc-block-states.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
            reg = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
        }
        for (final Kind k : Kind.values()) {
            for (int dir = 0; dir < 4; dir++) {
                for (final Op op : Underground.plan(k, 0, 63, 0, dir, 64)) {
                    final BlockStates.Parsed p = BlockStates.parse(op.state());
                    assertTrue(reg.has(p.id()), k + ": " + op.state());
                    final var props = reg.getAsJsonObject(p.id());
                    for (final var e : p.properties().entrySet()) {
                        assertTrue(props.has(e.getKey()), k + ": " + op.state());
                        boolean ok = false;
                        for (final var v : props.getAsJsonArray(e.getKey())) {
                            ok |= v.getAsString().equals(e.getValue());
                        }
                        assertTrue(ok, k + ": " + op.state());
                    }
                }
            }
        }
    }

    @Test
    void sealsComeFirstThenDiggingThenFurniture() {
        for (final Kind k : Kind.values()) {
            int phase = 0;
            for (final Op op : Underground.plan(k, 0, 63, 0, 1, 64)) {
                final int p = op.type().ordinal();
                assertTrue(p >= phase, k + ": order " + op);
                phase = p;
            }
            final List<Op> ops = Underground.plan(k, 0, 63, 0, 1, 64);
            assertTrue(ops.get(ops.size() - 1).state().contains("trapdoor"), k + ": the hatch is the last step");
        }
    }

    @Test
    void cellarUnderAHouseIsReachableLitAndDry() {
        final Ground g = new Ground();
        g.put(0, 63, 0, "minecraft:spruce_planks"); // house floor with the hatch
        // a flooded cave right next to the cellar (east side) and a gravel pocket over its ceiling
        for (int y = 58; y <= 62; y++) {
            for (int z = -3; z <= 6; z++) {
                g.put(3, y, z, "minecraft:water");
                g.put(4, y, z, "minecraft:water");
            }
        }
        g.put(0, 62, 2, "minecraft:gravel");
        final List<Op> ops = Underground.plan(Kind.CELLAR, 0, 63, 0, 2, 0);
        g.apply(ops);
        final Set<Long> dug = new HashSet<>();
        for (final Op op : ops) {
            if (op.type() == Type.DIG) {
                dug.add(MineDigger.key(op.x(), op.y(), op.z()));
                for (final int[] d : N6) {
                    final String n = g.at(op.x() + d[0], op.y() + d[1], op.z() + d[2]);
                    assertFalse(n.startsWith("minecraft:water") || n.contains("gravel"), "dug cell next to " + n + " at " + op);
                }
            }
        }
        // reachable from below the hatch
        final ArrayDeque<int[]> q = new ArrayDeque<>();
        final Set<Long> seen = new HashSet<>();
        q.add(new int[] {0, 62, 0});
        seen.add(MineDigger.key(0, 62, 0));
        while (!q.isEmpty()) {
            final int[] c = q.poll();
            for (final int[] d : N6) {
                final long k = MineDigger.key(c[0] + d[0], c[1] + d[1], c[2] + d[2]);
                if (dug.contains(k) && seen.add(k)) {
                    q.add(new int[] {c[0] + d[0], c[1] + d[1], c[2] + d[2]});
                }
            }
        }
        assertEquals(dug.size(), seen.size(), "every dug cell is reachable from the hatch");
        assertTrue(g.at(0, 63, 0).contains("trapdoor"));
        assertTrue(ops.stream().anyMatch(o -> o.state().contains("barrel")));
        attachmentsHold(g, ops);
    }

    /** Ladders and wall torches hang on solid blocks. */
    private static void attachmentsHold(final Ground g, final List<Op> ops) {
        for (final Op op : ops) {
            if (op.type() != Type.PLACE || !(op.state().contains("ladder") || op.state().contains("wall_torch"))) {
                continue;
            }
            final String facing = BlockStates.parse(op.state()).property("facing");
            final int[] v = BlockStates.vector(facing);
            assertTrue(g.solid(op.x() - v[0], op.y(), op.z() - v[1]), "attached to a solid block: " + op);
        }
        for (final Op op : ops) {
            if (op.type() == Type.PLACE && (op.state().contains("candle") || op.state().contains("barrel") || op.state().contains("chest"))) {
                assertTrue(g.solid(op.x(), op.y() - 1, op.z()) || g.at(op.x(), op.y() - 1, op.z()).contains("barrel"),
                    "stands on something: " + op);
            }
        }
    }

    @Test
    void dungeonCatacombsAndTunnelHoldTogetherInEveryDirection() {
        for (final Kind k : List.of(Kind.DUNGEON, Kind.CATACOMBS, Kind.TUNNEL)) {
            for (int dir = 0; dir < 4; dir++) {
                final Ground g = new Ground();
                g.put(0, 63, 0, "minecraft:stone_bricks");
                final List<Op> ops = Underground.plan(k, 0, 63, 0, dir, 63);
                g.apply(ops);
                attachmentsHold(g, ops);
                assertTrue(ops.stream().anyMatch(o -> o.state().contains("torch") || o.state().contains("lantern")
                    || o.state().contains("candle")), k + " is lit");
            }
        }
        // the tunnel ends in a hidden trapdoor at the surface over the exit point
        final int[] exit = Underground.tunnelExit(0, 0, 1);
        final List<Op> ops = Underground.plan(Kind.TUNNEL, 0, 63, 0, 1, 66);
        assertTrue(ops.stream().anyMatch(o -> o.x() == exit[0] && o.z() == exit[1] && o.y() == 66 && o.state().contains("trapdoor")),
            "exit trapdoor at " + exit[0] + "," + exit[1]);
        assertTrue(exit[0] > 30, "the exit is far from the castle: " + exit[0]);
    }

    @Test
    void hatchIsInsideTheBuilding() throws IOException {
        for (final String id : List.of("chata", "dom", "kamienica", "karczma", "ratusz", "zamek", "katedra")) {
            for (int q = 0; q < 4; q++) {
                final BuildingTemplate t = BuiltinTemplates.load(id).atLevel(MaterialPalette.defaults(), 1).rotated(q);
                final int g = t.meta().groundLayer();
                final int[] h = Underground.hatch((x, y, z) -> t.inside(x, y, z) ? t.state(x, y, z) : null, t.width(), t.depth(), g);
                assertNotNull(h, id + " rot " + q + " has a place for the hatch");
                assertFalse(BlockStates.isAir(t.state(h[0], g, h[1])), id + ": solid floor");
                assertTrue(BlockStates.isAir(t.state(h[0], g + 1, h[1])), id + ": free above");
                assertTrue(BlockStates.isAir(t.state(h[0], g + 2, h[1])), id + ": head room");
                assertTrue(h[2] >= 0 && h[2] < 4);
            }
        }
    }

    /** With -Dludnosc.preview=true: top-down plans of every underground kind into preview/podziemia-*.png. */
    @Test
    void previews() throws IOException {
        if (!Boolean.getBoolean("ludnosc.preview")) {
            return;
        }
        for (final Kind k : Kind.values()) {
            final List<Op> ops = Underground.plan(k, 0, 63, 0, 2, 64);
            int minX = 0;
            int maxX = 0;
            int minZ = 0;
            int maxZ = 0;
            for (final Op op : ops) {
                minX = Math.min(minX, op.x());
                maxX = Math.max(maxX, op.x());
                minZ = Math.min(minZ, op.z());
                maxZ = Math.max(maxZ, op.z());
            }
            final int cell = 12;
            final int w = (maxX - minX + 3) * cell;
            final int h = (maxZ - minZ + 3) * cell;
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            final java.awt.Graphics2D gr = img.createGraphics();
            gr.setColor(new java.awt.Color(70, 64, 58));
            gr.fillRect(0, 0, w, h);
            final Map<Long, java.awt.Color> top = new HashMap<>();
            for (final Op op : ops) {
                final long key = ((long) op.x() << 32) | (op.z() & 0xFFFFFFFFL);
                final java.awt.Color c = switch (op.type()) {
                    case SEAL -> null;
                    case DIG -> new java.awt.Color(200, 190, 170);
                    case PLACE -> op.state().contains("ladder") || op.state().contains("trapdoor") ? new java.awt.Color(170, 40, 40)
                        : op.state().contains("bars") ? new java.awt.Color(120, 120, 130)
                        : op.state().contains("torch") || op.state().contains("lantern") || op.state().contains("candle")
                        ? new java.awt.Color(255, 210, 60)
                        : op.state().contains("skull") || op.state().contains("bone") ? new java.awt.Color(240, 240, 230)
                        : new java.awt.Color(140, 95, 50);
                };
                if (c != null && (op.type() == Type.PLACE || !top.containsKey(key))) {
                    top.put(key, c);
                }
            }
            for (final var e : top.entrySet()) {
                final int x = (int) (e.getKey() >> 32);
                final int z = (int) (long) e.getKey();
                gr.setColor(e.getValue());
                gr.fillRect((x - minX + 1) * cell, (z - minZ + 1) * cell, cell - 1, cell - 1);
            }
            gr.dispose();
            final java.io.File out = new java.io.File("preview/podziemia-" + k.name().toLowerCase(java.util.Locale.ROOT) + ".png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);
        }
    }
}
