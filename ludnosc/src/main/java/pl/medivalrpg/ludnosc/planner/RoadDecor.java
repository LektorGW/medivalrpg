// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;

/**
 * Blocks of a road (pure, unit tested): surface blocks on the path, half slabs where the path climbs one block
 * (walkable without jumping) and lamp posts beside the road every few blocks (lit streets keep monsters out).
 * The look follows the epoch ({@link Style}): Middle Ages paths and cobbles, Renaissance stone, Industrial pavers and
 * gas lamps, Modern asphalt with a dashed white line, a second lane (with cars), smooth-stone pavements, zebra
 * crossings and street lights.
 */
public final class RoadDecor {

    /** One block to place. */
    public record Block(int x, int y, int z, String state) {
    }

    /**
     * Road style.
     *
     * @param surface     road block
     * @param slab        half slab for climbs
     * @param stripe      dashed centre line block (null = none), every {@code stripeEvery} blocks
     * @param sidewalk    pavement block beside the road (null = none)
     * @param lanes       1 or 2 (second lane on the left side where free)
     * @param lampPost    post block of street lamps
     * @param lampTop     the light on top
     * @param lampHeight  post blocks under the light
     * @param crossingEvery a zebra crossing every N blocks (0 = none), drawn with {@code stripe}
     */
    public record Style(String surface, String slab, String stripe, int stripeEvery, String sidewalk, int lanes, String lampPost,
                        String lampTop, int lampHeight, int crossingEvery) {

        /** Old style: one surface, spruce fence lamp posts with a lantern. */
        public static Style simple(final String surface, final String slab) {
            return new Style(surface, slab, null, 0, null, 1, "minecraft:spruce_fence", "minecraft:lantern[hanging=false]", 2, 0);
        }

        /**
         * The road of an epoch (1..4) at a settlement level; {@code cars}: the country knows the car (wider roads).
         */
        public static Style of(final int era, final int level, final int pavedLevel, final boolean cars) {
            return switch (era) {
                case 2 -> new Style(level >= pavedLevel - 1 ? "minecraft:stone_bricks" : "minecraft:cobblestone",
                    "minecraft:stone_brick_slab[type=bottom]", null, 0, null, 1, "minecraft:spruce_fence",
                    "minecraft:lantern[hanging=false]", 2, 0);
                case 3 -> new Style("minecraft:polished_andesite", "minecraft:polished_andesite_slab[type=bottom]", null, 0,
                    level >= pavedLevel ? "minecraft:stone_bricks" : null, 1, "minecraft:polished_blackstone_wall",
                    "minecraft:lantern[hanging=false]", 3, 0);
                case 4 -> new Style("minecraft:black_concrete", "minecraft:polished_blackstone_slab[type=bottom]",
                    "minecraft:white_concrete", 4, "minecraft:smooth_stone", cars ? 2 : 1, "minecraft:andesite_wall",
                    "minecraft:sea_lantern", 3, 24);
                default -> simple(level >= pavedLevel ? "minecraft:cobblestone" : "minecraft:dirt_path",
                    "minecraft:cobblestone_slab[type=bottom]");
            };
        }
    }

    private RoadDecor() {
    }

    /**
     * @param path       road columns in order
     * @param height     top ground block per column
     * @param surface    road surface block, e.g. {@code minecraft:dirt_path} or {@code minecraft:cobblestone}
     * @param slab       half slab for climbs, e.g. {@code minecraft:cobblestone_slab[type=bottom]}
     * @param lampEvery  a lamp post every N road blocks (0 = none)
     * @param free       column next to the road may take a lamp post
     */
    public static List<Block> blocks(final List<int[]> path, final IntBinaryOperator height, final String surface,
                                     final String slab, final int lampEvery, final BiPredicate<Integer, Integer> free) {
        return blocks(path, height, Style.simple(surface, slab), lampEvery, free);
    }

    private static boolean adjacent(final int[] a, final int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) == 1;
    }

    /** Road blocks in a style. Consecutive path cells that are not neighbours start a new road piece. */
    public static List<Block> blocks(final List<int[]> path, final IntBinaryOperator height, final Style style, final int lampEvery,
                                     final BiPredicate<Integer, Integer> free) {
        final List<Block> out = new ArrayList<>();
        final Set<Long> road = new HashSet<>();
        for (final int[] p : path) {
            road.add(RoadPlanner.key(p[0], p[1]));
        }
        final Set<Long> used = new HashSet<>();
        int piece = 0;
        for (int i = 0; i < path.size(); i++) {
            final int x = path.get(i)[0];
            final int z = path.get(i)[1];
            final boolean linked = i > 0 && adjacent(path.get(i - 1), path.get(i));
            piece = linked ? piece + 1 : 0;
            final int h = height.applyAsInt(x, z);
            if (h == TerrainView.UNKNOWN) {
                continue;
            }
            final boolean crossing = style.crossingEvery() > 0 && style.stripe() != null && piece % style.crossingEvery() == style.crossingEvery() / 2;
            final boolean dash = style.stripe() != null && style.stripeEvery() > 0 && piece % style.stripeEvery() == 0 && piece > 0;
            out.add(new Block(x, h, z, crossing || dash ? style.stripe() : style.surface()));
            used.add(RoadPlanner.key(x, z));
            boolean higherNeighbour = false;
            for (final int j : new int[] {i - 1, i + 1}) {
                if (j >= 0 && j < path.size() && adjacent(path.get(j), path.get(i))) {
                    final int hn = height.applyAsInt(path.get(j)[0], path.get(j)[1]);
                    higherNeighbour |= hn == h + 1;
                }
            }
            if (higherNeighbour) {
                out.add(new Block(x, h + 1, z, style.slab()));
            }
            // direction of the road here (for the sides)
            int dx = 0;
            int dz = 0;
            if (linked) {
                dx = x - path.get(i - 1)[0];
                dz = z - path.get(i - 1)[1];
            } else if (i + 1 < path.size() && adjacent(path.get(i), path.get(i + 1))) {
                dx = path.get(i + 1)[0] - x;
                dz = path.get(i + 1)[1] - z;
            }
            final int[] left = {-dz, dx};
            final int[] right = {dz, -dx};
            int sideL = 1;
            if ((dx != 0 || dz != 0) && style.lanes() > 1) {
                final int lx = x + left[0];
                final int lz = z + left[1];
                if (!road.contains(RoadPlanner.key(lx, lz)) && used.add(RoadPlanner.key(lx, lz)) && free.test(lx, lz)) {
                    final int lh = height.applyAsInt(lx, lz);
                    if (lh != TerrainView.UNKNOWN && Math.abs(lh - h) <= 1) {
                        out.add(new Block(lx, lh, lz, crossing ? style.stripe() : style.surface()));
                        sideL = 2;
                    }
                }
            }
            if ((dx != 0 || dz != 0) && style.sidewalk() != null) {
                for (final int[] s : new int[][] {{left[0] * sideL, left[1] * sideL}, right}) {
                    final int sx = x + s[0];
                    final int sz = z + s[1];
                    if (road.contains(RoadPlanner.key(sx, sz)) || !used.add(RoadPlanner.key(sx, sz)) || !free.test(sx, sz)) {
                        continue;
                    }
                    final int sh = height.applyAsInt(sx, sz);
                    if (sh != TerrainView.UNKNOWN && Math.abs(sh - h) <= 1) {
                        out.add(new Block(sx, sh, sz, style.sidewalk()));
                    }
                }
            }
            if (lampEvery > 0 && linked && piece % lampEvery == 0) {
                // perpendicular side, beyond the pavement: right first, then left
                for (final int[] side : new int[][] {
                    {right[0] * (style.sidewalk() != null ? 2 : 1), right[1] * (style.sidewalk() != null ? 2 : 1)},
                    {left[0] * (sideL + (style.sidewalk() != null ? 1 : 0)), left[1] * (sideL + (style.sidewalk() != null ? 1 : 0))},
                    {left[0], left[1]}}) {
                    final int lx = x + side[0];
                    final int lz = z + side[1];
                    if (road.contains(RoadPlanner.key(lx, lz)) || !free.test(lx, lz)) {
                        continue;
                    }
                    final int lh = height.applyAsInt(lx, lz);
                    if (lh == TerrainView.UNKNOWN) {
                        continue;
                    }
                    for (int k = 1; k <= style.lampHeight(); k++) {
                        out.add(new Block(lx, lh + k, lz, style.lampPost()));
                    }
                    out.add(new Block(lx, lh + style.lampHeight() + 1, lz, style.lampTop()));
                    break;
                }
            }
        }
        return out;
    }

    /** Orders road cells into chains of neighbours (for resurfacing a town's road network). */
    public static List<int[]> chains(final Set<Long> cells) {
        final List<int[]> out = new ArrayList<>();
        final Set<Long> left = new java.util.LinkedHashSet<>(cells);
        while (!left.isEmpty()) {
            // start at an end of a chain when there is one (a cell with a single neighbour)
            long start = left.iterator().next();
            for (final long k : left) {
                if (neighbours(left, k) <= 1) {
                    start = k;
                    break;
                }
            }
            long cur = start;
            while (true) {
                left.remove(cur);
                out.add(new int[] {RoadPlanner.keyX(cur), RoadPlanner.keyZ(cur)});
                long next = Long.MIN_VALUE;
                for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    final long n = RoadPlanner.key(RoadPlanner.keyX(cur) + d[0], RoadPlanner.keyZ(cur) + d[1]);
                    if (left.contains(n)) {
                        next = n;
                        break;
                    }
                }
                if (next == Long.MIN_VALUE) {
                    break;
                }
                cur = next;
            }
        }
        return out;
    }

    private static int neighbours(final Set<Long> cells, final long k) {
        int n = 0;
        for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (cells.contains(RoadPlanner.key(RoadPlanner.keyX(k) + d[0], RoadPlanner.keyZ(k) + d[1]))) {
                n++;
            }
        }
        return n;
    }
}
