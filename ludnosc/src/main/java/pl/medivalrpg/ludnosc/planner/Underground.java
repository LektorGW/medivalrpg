// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Underground parts of buildings (pure, unit tested): cellars under houses, dungeons under a castle or town hall,
 * an escape tunnel from the castle dungeon, catacombs under the cathedral. Each is entered through a trapdoor in the
 * building's floor (the {@code hatch}) and a ladder shaft.
 *
 * <p>The plan is an ordered list of operations: first {@link Type#SEAL} every cell around the dug volume (applied
 * only where the world is open, liquid or falling — caves are walled off and water/lava can never flow in), then
 * {@link Type#DIG} the volume top-down, then {@link Type#PLACE} ladders, lights and furniture. Deterministic: the
 * same building always gets the same plan, so progress is just an index.</p>
 */
public final class Underground {

    /** Feature kinds. */
    public enum Kind { CELLAR, DUNGEON, TUNNEL, CATACOMBS }

    /** Operation types. */
    public enum Type { SEAL, DIG, PLACE }

    /** One operation. */
    public record Op(Type type, int x, int y, int z, String state) {
    }

    static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    static final String[] FACING = {"north", "east", "south", "west"};
    /** Wall material of sealed cells. */
    public static final String WALL = "minecraft:cobblestone";

    private Underground() {
    }

    /** Builder in local coordinates: s = steps along {@code dir}, l = steps to the right, y = absolute. */
    private static final class Plan {
        final int hx;
        final int hy;
        final int hz;
        final int dir;
        final Map<Long, int[]> dig = new LinkedHashMap<>();
        final Map<Long, Op> place = new LinkedHashMap<>();
        final Set<Long> noSeal = new LinkedHashSet<>();

        Plan(final int hx, final int hy, final int hz, final int dir) {
            this.hx = hx;
            this.hy = hy;
            this.hz = hz;
            this.dir = Math.floorMod(dir, 4);
            // the hatch and the floor around it are the building's: never sealed or dug
            this.noSeal.add(MineDigger.key(hx, hy, hz));
        }

        int x(final int s, final int l) {
            return this.hx + DIRS[this.dir][0] * s + DIRS[Math.floorMod(this.dir + 1, 4)][0] * l;
        }

        int z(final int s, final int l) {
            return this.hz + DIRS[this.dir][1] * s + DIRS[Math.floorMod(this.dir + 1, 4)][1] * l;
        }

        /** Facing name of a local direction: 0 forward, 1 right, 2 back, 3 left. */
        String face(final int local) {
            return FACING[Math.floorMod(this.dir + local, 4)];
        }

        void dig(final int s, final int l, final int y) {
            final int x = this.x(s, l);
            final int z = this.z(s, l);
            this.dig.put(MineDigger.key(x, y, z), new int[] {x, y, z});
        }

        void box(final int s1, final int s2, final int l1, final int l2, final int y1, final int y2) {
            for (int s = Math.min(s1, s2); s <= Math.max(s1, s2); s++) {
                for (int l = Math.min(l1, l2); l <= Math.max(l1, l2); l++) {
                    for (int y = y1; y <= y2; y++) {
                        this.dig(s, l, y);
                    }
                }
            }
        }

        void put(final int s, final int l, final int y, final String state) {
            final int x = this.x(s, l);
            final int z = this.z(s, l);
            this.place.put(MineDigger.key(x, y, z), new Op(Type.PLACE, x, y, z, "minecraft:" + state));
        }

        /** Ladder shaft below the hatch from {@code hy-1} down to {@code bottom}, ladder against the back wall. */
        void shaft(final int bottom) {
            for (int y = this.hy - 1; y >= bottom; y--) {
                this.dig(0, 0, y);
                this.put(0, 0, y, "ladder[facing=" + this.face(0) + ",waterlogged=false]");
            }
            this.place.put(MineDigger.key(this.hx, this.hy, this.hz), new Op(Type.PLACE, this.hx, this.hy, this.hz,
                "minecraft:spruce_trapdoor[facing=" + this.face(0) + ",half=top,open=false,powered=false,waterlogged=false]"));
        }

        List<Op> ops() {
            final List<Op> out = new ArrayList<>();
            // 1. seal around the volume (6-neighbourhood), top-down
            final Set<Long> seals = new LinkedHashSet<>();
            final List<int[]> sealCells = new ArrayList<>();
            for (final int[] c : this.dig.values()) {
                for (final int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    final int x = c[0] + d[0];
                    final int y = c[1] + d[1];
                    final int z = c[2] + d[2];
                    final long k = MineDigger.key(x, y, z);
                    if (!this.dig.containsKey(k) && !this.noSeal.contains(k) && seals.add(k)) {
                        sealCells.add(new int[] {x, y, z});
                    }
                }
            }
            sealCells.sort(Comparator.<int[]>comparingInt(c -> -c[1]).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[2]));
            for (final int[] c : sealCells) {
                out.add(new Op(Type.SEAL, c[0], c[1], c[2], WALL));
            }
            // 2. dig top-down
            final List<int[]> cells = new ArrayList<>(this.dig.values());
            cells.sort(Comparator.<int[]>comparingInt(c -> -c[1]).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[2]));
            for (final int[] c : cells) {
                out.add(new Op(Type.DIG, c[0], c[1], c[2], "minecraft:air"));
            }
            // 3. furniture, ladders bottom-up, lights; the hatch last
            final List<Op> placed = new ArrayList<>(this.place.values());
            final Op hatch = this.place.get(MineDigger.key(this.hx, this.hy, this.hz));
            placed.remove(hatch);
            placed.sort(Comparator.comparingInt(Op::y));
            out.addAll(placed);
            if (hatch != null) {
                out.add(hatch);
            }
            return out;
        }
    }

    /**
     * The plan of a feature.
     *
     * @param kind  what to dig
     * @param hx    hatch (floor cell inside the building) x
     * @param hy    hatch y (the floor level)
     * @param hz    hatch z
     * @param dir   direction the rooms extend from the shaft (0 north, 1 east, 2 south, 3 west)
     * @param param tunnel: y of the surface block at the exit; otherwise unused
     */
    public static List<Op> plan(final Kind kind, final int hx, final int hy, final int hz, final int dir, final int param) {
        final Plan p = new Plan(hx, hy, hz, dir);
        switch (kind) {
            case CELLAR -> cellar(p);
            case DUNGEON -> dungeon(p, false, 0);
            case TUNNEL -> dungeon(p, true, param);
            case CATACOMBS -> catacombs(p);
        }
        return p.ops();
    }

    /** Cellar: shaft 4 deep, room 4 × 5 × 3 with barrels, a chest and torches. */
    private static void cellar(final Plan p) {
        final int top = p.hy - 2;
        final int bottom = p.hy - 4;
        p.box(0, 3, -2, 2, bottom, top);
        p.shaft(bottom);
        for (int s = 1; s <= 3; s++) {
            p.put(s, -2, bottom, "barrel[facing=up,open=false]");
            p.put(s, 2, bottom, "barrel[facing=up,open=false]");
        }
        p.put(1, -2, bottom + 1, "barrel[facing=up,open=false]");
        p.put(3, 0, bottom, "chest[facing=" + p.face(2) + ",type=single,waterlogged=false]");
        p.put(3, -1, bottom, "hay_block[axis=y]");
        p.put(3, 1, bottom + 1, "wall_torch[facing=" + p.face(2) + "]");
        p.put(3, -1, bottom + 1 + 1, "wall_torch[facing=" + p.face(2) + "]");
    }

    /** Dungeon: shaft 6 deep, corridor with barred cells on both sides, guard room; optionally the escape tunnel. */
    private static void dungeon(final Plan p, final boolean tunnel, final int exitY) {
        final int floor = p.hy - 6;
        p.shaft(floor);
        p.box(0, 10, 0, 0, floor, floor + 2);
        for (final int s : new int[] {2, 5, 8}) {
            for (final int side : new int[] {1, -1}) {
                p.box(s, s + 1, 2 * side, 3 * side, floor, floor + 1);
                for (int k = 0; k <= 1; k++) {
                    p.dig(s + k, side, floor);
                    p.dig(s + k, side, floor + 1);
                    p.put(s + k, side, floor, "iron_bars[east=false,north=false,south=false,west=false,waterlogged=false]");
                    p.put(s + k, side, floor + 1, "iron_bars[east=false,north=false,south=false,west=false,waterlogged=false]");
                }
                p.put(s, 3 * side, floor, "hay_block[axis=y]");
            }
        }
        p.put(8, 3, floor, "chest[facing=" + p.face(3) + ",type=single,waterlogged=false]");
        for (final int s : new int[] {1, 4, 7, 10}) {
            p.put(s, 0, floor + 1, "wall_torch[facing=" + p.face(3) + "]");
        }
        // guard room
        p.box(11, 13, -2, 2, floor, floor + 2);
        p.put(13, -2, floor, "barrel[facing=up,open=false]");
        p.put(13, 2, floor, "barrel[facing=up,open=false]");
        p.put(12, 2, floor, "crafting_table");
        p.put(13, 0, floor + 1, "wall_torch[facing=" + p.face(2) + "]");
        p.put(11, -2, floor + 1, "wall_torch[facing=" + p.face(1) + "]");
        if (!tunnel) {
            return;
        }
        // escape tunnel through the far wall of the guard room, then a ladder up to a hidden trapdoor
        final int length = TUNNEL_LENGTH;
        final int end = 14 + length;
        for (int s = 14; s < end; s++) {
            p.dig(s, 0, floor);
            p.dig(s, 0, floor + 1);
            if ((s - 14) % 7 == 6) {
                p.put(s, 0, floor + 1, "wall_torch[facing=" + p.face(3) + "]");
            }
        }
        p.put(13, 0, floor + 1, "air");
        p.put(13, 1, floor + 1, "wall_torch[facing=" + p.face(2) + "]");
        final int top = Math.max(floor + 2, exitY - 1);
        for (int y = floor; y <= top; y++) {
            p.dig(end, 0, y);
            p.put(end, 0, y, "ladder[facing=" + p.face(2) + ",waterlogged=false]");
        }
        final int ex = p.x(end, 0);
        final int ez = p.z(end, 0);
        p.noSeal.add(MineDigger.key(ex, top + 1, ez));
        p.place.put(MineDigger.key(ex, top + 1, ez), new Op(Type.PLACE, ex, top + 1, ez,
            "minecraft:spruce_trapdoor[facing=" + p.face(2) + ",half=top,open=false,powered=false,waterlogged=false]"));
    }

    /** Length of the escape tunnel (blocks beyond the guard room). */
    public static final int TUNNEL_LENGTH = 28;

    /** Far end of the escape tunnel from the hatch: {x, z}. */
    public static int[] tunnelExit(final int hx, final int hz, final int dir) {
        final Plan p = new Plan(hx, 0, hz, dir);
        return new int[] {p.x(14 + TUNNEL_LENGTH, 0), p.z(14 + TUNNEL_LENGTH, 0)};
    }

    /** Catacombs: shaft 7 deep, a main gallery with three cross galleries, burial niches with candles and skulls. */
    private static void catacombs(final Plan p) {
        final int floor = p.hy - 7;
        p.shaft(floor);
        p.box(0, 14, 0, 0, floor, floor + 2);
        for (final int s : new int[] {4, 9, 14}) {
            p.box(s, s, -6, 6, floor, floor + 2);
            p.put(s, 0, floor + 2, "lantern[hanging=true,waterlogged=false]");
        }
        // niches in the walls of the cross galleries (every other block, middle height)
        int n = 0;
        for (final int s : new int[] {4, 9, 14}) {
            for (int l = -6; l <= 6; l++) {
                if (l == 0 || Math.abs(l) % 2 == 1) {
                    continue;
                }
                for (final int side : new int[] {-1, 1}) {
                    if (s + side >= 15 && Math.abs(l) <= 2) {
                        continue; // that is the ossuary
                    }
                    p.dig(s + side, l, floor + 1);
                    p.put(s + side, l, floor + 1, (n++ % 3 == 0)
                        ? "skeleton_skull[powered=false,rotation=" + (side < 0 ? 0 : 8) + "]"
                        : "candle[candles=" + (1 + n % 3) + ",lit=true,waterlogged=false]");
                }
            }
        }
        for (final int s : new int[] {2, 7, 12}) {
            p.put(s, 0, floor + 1, "wall_torch[facing=" + p.face(3) + "]");
        }
        // ossuary at the far end
        p.box(15, 17, -2, 2, floor, floor + 2);
        p.put(17, -1, floor, "bone_block[axis=y]");
        p.put(17, 1, floor, "bone_block[axis=y]");
        p.put(17, 0, floor, "chiseled_stone_bricks");
        p.put(17, 0, floor + 1, "candle[candles=4,lit=true,waterlogged=false]");
    }

    // ------------------------------------------------------------------ hatch

    /** Read access to a (rotated) template: state id at local cell, {@code null} = outside / keep terrain. */
    @FunctionalInterface
    public interface Cells {
        String at(int x, int y, int z);
    }

    /**
     * A floor cell for the hatch inside a template: solid floor at the ground layer, two free cells above, enclosed on
     * all four sides at head height by the building (not next to a door or the open air), nearest to the centre.
     * Returns {x, z, dir} with dir pointing to the template's centre, or null.
     */
    public static int[] hatch(final Cells t, final int width, final int depth, final int ground) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        final double cx = (width - 1) / 2.0;
        final double cz = (depth - 1) / 2.0;
        for (int z = 1; z < depth - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                final String floor = t.at(x, ground, z);
                if (floor == null || air(floor) || !full(floor)) {
                    continue;
                }
                if (!air(t.at(x, ground + 1, z)) || !air(t.at(x, ground + 2, z))) {
                    continue;
                }
                boolean ok = true;
                for (final int[] d : DIRS) {
                    // walking room around: neighbours free or furniture, never a door, but the building must enclose
                    final String n = t.at(x + d[0], ground + 1, z + d[1]);
                    if (n == null || n.contains("_door")) {
                        ok = false;
                        break;
                    }
                }
                if (!ok || !enclosed(t, x, ground + 1, z, width, depth)) {
                    continue;
                }
                final double dd = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (dd < bestD) {
                    bestD = dd;
                    final int dir = Math.abs(cx - x) >= Math.abs(cz - z) ? (cx >= x ? 1 : 3) : (cz >= z ? 2 : 0);
                    best = new int[] {x, z, dir};
                }
            }
        }
        return best;
    }

    /** Every straight line from the cell to the template edge (4 directions) hits a non-air block (walls). */
    private static boolean enclosed(final Cells t, final int x, final int y, final int z, final int w, final int d) {
        for (final int[] dir : DIRS) {
            int px = x;
            int pz = z;
            boolean wall = false;
            while (px >= 0 && pz >= 0 && px < w && pz < d) {
                final String s = t.at(px, y, pz);
                if (s != null && !air(s)) {
                    wall = true;
                    break;
                }
                px += dir[0];
                pz += dir[1];
            }
            if (!wall) {
                return false;
            }
        }
        return true;
    }

    static boolean air(final String s) {
        return s != null && (s.endsWith(":air") || s.endsWith("cave_air"));
    }

    /** Full cube floor block (not a slab, stair, carpet, bed, water...). */
    static boolean full(final String s) {
        final String id = s.replaceAll("\\[.*", "");
        return !(id.contains("slab") || id.contains("stairs") || id.contains("carpet") || id.contains("bed") || id.contains("water")
            || id.contains("lava") || id.contains("trapdoor") || id.contains("fence") || id.contains("wall") || id.contains("pane")
            || id.contains("door") || id.contains("farmland") || id.contains("path") || id.contains("leaves"));
    }
}
