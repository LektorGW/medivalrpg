// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

/**
 * How mines develop (pure, unit tested): the level 1..5 grows with the gallery length, the number of floors and the
 * epoch; a new floor (10 blocks deeper, reached by a staircase) starts when the current one is long enough; miners
 * turn towards ore veins seen next to the gallery; the look of the galleries follows the epoch (torches → lanterns →
 * ceiling lamps, timber → steel → concrete).
 */
public final class MinePlanner {

    /** Blocks between two floors. */
    public static final int FLOOR_DEPTH = 10;

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private MinePlanner() {
    }

    /** Level 1..5 from the gallery length, the floors and the epoch. */
    public static int level(final int length, final int floors, final int era) {
        return Math.max(1, Math.min(5, 1 + length / 48 + Math.max(0, floors - 1) + Math.max(0, era - 1) / 2));
    }

    /** Gallery length of a floor before the miners go one floor deeper. */
    public static int floorLength(final int floor) {
        return 40 + 12 * Math.max(0, floor);
    }

    /** Income multiplier: +25 % per level above 1, ×1.5 from the Industrial epoch. */
    public static double incomeFactor(final int level, final int era) {
        return (1 + 0.25 * (Math.max(1, level) - 1)) * (era >= 3 ? 1.5 : 1.0);
    }

    /** Ore found by the miners per day (on top of the ore dug out of the galleries). */
    public static int dailyOre(final int level) {
        return 2 * Math.max(1, level);
    }

    static boolean ore(final String id) {
        return id != null && id.endsWith("_ore");
    }

    /**
     * Direction (0..3) of the nearest ore within {@code reach} blocks to the left or right of the head (at the head's
     * or the next height), or -1. Only sideways: the miners follow a vein, then go back to the main direction.
     */
    public static int oreDirection(final MineDigger.Blocks world, final int x, final int y, final int z, final int dir,
                                   final int reach) {
        int best = -1;
        int bestD = Integer.MAX_VALUE;
        for (final int side : new int[] {Math.floorMod(dir + 1, 4), Math.floorMod(dir - 1, 4)}) {
            for (int d = 2; d <= reach; d++) {
                for (int h = 0; h <= 1; h++) {
                    final String id = world.at(x + DIRS[side][0] * d, y + h, z + DIRS[side][1] * d);
                    if (ore(id) && d < bestD) {
                        bestD = d;
                        best = side;
                    }
                }
            }
        }
        return best;
    }

    /** The block of a gallery op in an epoch (the digger plans medieval timber and torches). */
    public static String style(final String state, final int era) {
        if (era <= 1) {
            return state;
        }
        if (state.startsWith("minecraft:wall_torch")) {
            return era >= 4 ? "minecraft:sea_lantern" : "minecraft:lantern[hanging=true]";
        }
        if (state.startsWith("minecraft:oak_log")) {
            return era >= 4 ? "minecraft:light_gray_concrete" : era >= 3 ? "minecraft:polished_deepslate" : "minecraft:spruce_log[axis=y]";
        }
        if (state.equals("minecraft:oak_planks")) {
            return era >= 4 ? "minecraft:gray_concrete" : era >= 3 ? "minecraft:iron_block" : "minecraft:spruce_planks";
        }
        if (era >= 4 && state.equals("minecraft:cobblestone")) {
            return "minecraft:gray_concrete";
        }
        if (era >= 4 && state.startsWith("minecraft:cobblestone_stairs")) {
            return state.replace("cobblestone_stairs", "andesite_stairs");
        }
        return state;
    }

    /**
     * Where a restyled light goes: a medieval torch hangs on the side wall; a lantern of later epochs stands in a niche
     * of that wall (the corridor stays free to walk); a modern lamp replaces the ceiling block above the cell.
     */
    public static int[] lightPos(final String original, final int x, final int y, final int z, final int era) {
        if (!original.startsWith("minecraft:wall_torch") || era <= 1) {
            return new int[] {x, y, z};
        }
        if (era >= 4) {
            return new int[] {x, y + 1, z};
        }
        final String facing = original.replaceAll(".*facing=([a-z]+).*", "$1");
        final int[] v = switch (facing) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "east" -> new int[] {1, 0};
            default -> new int[] {-1, 0};
        };
        return new int[] {x - v[0], y, z - v[1]};
    }

    /** Lantern of the Renaissance / Industrial galleries stands in its niche (not hanging). */
    static String niche(final String styled) {
        return styled.equals("minecraft:lantern[hanging=true]") ? "minecraft:lantern[hanging=false]" : styled;
    }

    public static String light(final String original, final int era) {
        return niche(style(original, era));
    }
}
