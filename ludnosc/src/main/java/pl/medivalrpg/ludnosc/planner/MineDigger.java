// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mine galleries (pure, unit tested): a corridor two blocks high dug step by step from the mine shaft — first a
 * staircase down to the target depth, then level with rails. Every few blocks a timber frame (log posts in the
 * walls, plank beam overhead) and a torch. Safety: a step whose surroundings touch water or lava is not dug (the
 * corridor turns left/right or stops), caves next to the corridor are walled off with cobblestone and holes in the
 * floor are filled, so nobody falls or drowns.
 */
public final class MineDigger {

    /** Read access to the world: namespaced block id at a position ({@code minecraft:air}...). */
    @FunctionalInterface
    public interface Blocks {
        String at(int x, int y, int z);
    }

    /** One block to place. */
    public record Op(int x, int y, int z, String state) {
    }

    /**
     * Result.
     *
     * @param ops     blocks to place, in order
     * @param head    walking cell where the next extension continues {x, y, z}
     * @param dir     direction index of the head (0 north, 1 east, 2 south, 3 west)
     * @param dug     corridor steps dug
     * @param ores    ore blocks dug out
     * @param stopped true when the gallery had to stop (water/lava all around)
     */
    public record Result(List<Op> ops, int[] head, int dir, int dug, int ores, boolean stopped) {
    }

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final String[] FACING = {"north", "east", "south", "west"};

    private MineDigger() {
    }

    static boolean liquid(final String id) {
        return id != null && (id.contains("water") || id.contains("lava") || id.contains("bubble_column"));
    }

    static boolean open(final String id) {
        return id == null || id.endsWith(":air") || id.endsWith("cave_air") || id.endsWith("void_air");
    }

    static boolean ore(final String id) {
        return id != null && id.endsWith("_ore");
    }

    /**
     * Digs {@code steps} corridor steps from walking cell (x, y, z) in direction {@code dir}, descending one block per
     * step until {@code targetY}.
     */
    public static Result dig(final Blocks world, final int x, final int y, final int z, final int dir, final int steps,
                             final int targetY, final int torchEvery, final int frameEvery) {
        return dig(world, x, y, z, dir, steps, targetY, torchEvery, frameEvery, 0);
    }

    /**
     * Like {@link #dig(Blocks, int, int, int, int, int, int, int, int)} continuing a gallery that already has
     * {@code already} steps (keeps the spacing of torches and frames when digging one step at a time).
     */
    public static Result dig(final Blocks world, final int x, final int y, final int z, final int dir, final int steps,
                             final int targetY, final int torchEvery, final int frameEvery, final int already) {
        final List<Op> ops = new ArrayList<>();
        final Set<Long> corridor = new HashSet<>();
        int cx = x;
        int cy = y;
        int cz = z;
        int d = Math.floorMod(dir, 4);
        int dug = 0;
        int ores = 0;
        boolean stopped = false;
        corridor.add(key(cx, cy, cz));
        corridor.add(key(cx, cy + 1, cz));
        for (int i = 0; i < steps; i++) {
            final boolean down = cy > targetY;
            int chosen = -1;
            for (final int t : new int[] {d, Math.floorMod(d + 1, 4), Math.floorMod(d - 1, 4)}) {
                final int nx = cx + DIRS[t][0];
                final int nz = cz + DIRS[t][1];
                final int ny = down ? cy - 1 : cy;
                if (safe(world, corridor, nx, ny, nz)) {
                    chosen = t;
                    break;
                }
            }
            if (chosen < 0) {
                stopped = true;
                break;
            }
            final int back = Math.floorMod(chosen + 2, 4);
            d = chosen;
            cx += DIRS[d][0];
            cz += DIRS[d][1];
            if (down) {
                cy--;
            }
            // clear the walking cell, the head cell and (on stairs) one more for headroom
            for (int h = 0; h <= (down ? 2 : 1); h++) {
                final String id = world.at(cx, cy + h, cz);
                if (ore(id)) {
                    ores++;
                }
                if (!open(id)) {
                    ops.add(new Op(cx, cy + h, cz, "minecraft:air"));
                }
                corridor.add(key(cx, cy + h, cz));
            }
            // floor: stairs going down (rising towards the shaft), cobblestone patches, rails when level
            final String below = world.at(cx, cy - 1, cz);
            if (down) {
                ops.add(new Op(cx, cy - 1, cz, "minecraft:cobblestone_stairs[facing=" + FACING[back] + ",half=bottom]"));
            } else if (open(below) || liquid(below)) {
                ops.add(new Op(cx, cy - 1, cz, "minecraft:cobblestone"));
            }
            // seal caves next to the corridor (sides and ceiling)
            final int[] side = DIRS[Math.floorMod(d + 1, 4)];
            for (final int s : new int[] {1, -1}) {
                for (int h = 0; h <= 1; h++) {
                    final int sx = cx + side[0] * s;
                    final int sz = cz + side[1] * s;
                    if (!corridor.contains(key(sx, cy + h, sz)) && open(world.at(sx, cy + h, sz))) {
                        ops.add(new Op(sx, cy + h, sz, "minecraft:cobblestone"));
                    }
                }
            }
            final int top = cy + (down ? 3 : 2);
            if (!corridor.contains(key(cx, top, cz)) && open(world.at(cx, top, cz))) {
                ops.add(new Op(cx, top, cz, "minecraft:cobblestone"));
            }
            dug++;
            if (!down) {
                ops.add(new Op(cx, cy, cz, "minecraft:rail[shape=" + (d % 2 == 0 ? "north_south" : "east_west") + "]"));
                if (frameEvery > 0 && (already + dug) % frameEvery == 0) {
                    for (final int s : new int[] {1, -1}) {
                        final int sx = cx + side[0] * s;
                        final int sz = cz + side[1] * s;
                        ops.add(new Op(sx, cy, sz, "minecraft:oak_log[axis=y]"));
                        ops.add(new Op(sx, cy + 1, sz, "minecraft:oak_log[axis=y]"));
                    }
                    ops.add(new Op(cx, cy + 2, cz, "minecraft:oak_planks"));
                }
            }
            if (torchEvery > 0 && (already + dug) % torchEvery == 0) {
                // torch on the left wall, facing into the corridor
                ops.add(new Op(cx, cy + 1, cz, "minecraft:wall_torch[facing=" + FACING[Math.floorMod(d + 1, 4)] + "]"));
            }
        }
        return new Result(List.copyOf(ops), new int[] {cx, cy, cz}, d, dug, ores, stopped);
    }

    /** The step cells and their neighbours (except the corridor itself) contain no water or lava. */
    static boolean safe(final Blocks world, final Set<Long> corridor, final int x, final int y, final int z) {
        for (int h = -1; h <= 3; h++) {
            for (final int[] n : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int px = x + n[0];
                final int pz = z + n[1];
                if (corridor.contains(key(px, y + h, pz))) {
                    continue;
                }
                if (liquid(world.at(px, y + h, pz))) {
                    return false;
                }
            }
        }
        return true;
    }

    static long key(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
