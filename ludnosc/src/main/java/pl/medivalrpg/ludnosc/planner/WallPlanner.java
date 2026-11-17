// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * City walls (pure, unit tested): a ring around the built-up area (bounding rectangle of the buildings + margin),
 * walked clockwise; every ring cell gets a wall column standing on its own ground (stepped on slopes, no gaps),
 * merlons on every other cell, a tower every {@code towerEvery} cells and on the corners, and a gate — an opening
 * three cells wide with an arch and lanterns — wherever a road crosses the ring. Built in segments (modules).
 */
public final class WallPlanner {

    /** One block. */
    public record Piece(int x, int y, int z, String state) {
    }

    /**
     * Wall style.
     *
     * @param wall    wall block
     * @param merlon  crenel block on top
     * @param tower   tower block
     * @param height  wall height above the ground
     */
    public record Style(String wall, String merlon, String tower, int height) {

        /**
         * Medieval stone walls; Renaissance bastions (lower, light stone, heavy corners); Industrial and Modern:
         * no walls any more — posts: a low concrete / brick barrier with lights.
         */
        public static Style of(final int era) {
            return switch (era) {
                case 2 -> new Style("minecraft:smooth_stone", "minecraft:stone_brick_wall", "minecraft:polished_andesite", 4);
                case 3 -> new Style("minecraft:bricks", "minecraft:iron_bars", "minecraft:stone_bricks", 2);
                case 4 -> new Style("minecraft:gray_concrete", "minecraft:iron_bars", "minecraft:light_gray_concrete", 1);
                default -> new Style("minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:cobblestone", 5);
            };
        }

        /** Wooden palisade of a young settlement / a quick border defence. */
        public static Style palisade() {
            return new Style("minecraft:spruce_log[axis=y]", "minecraft:spruce_fence", "minecraft:stripped_spruce_log[axis=y]", 3);
        }
    }

    private WallPlanner() {
    }

    /** Rectangle around the boxes + margin: {minX, minZ, maxX, maxZ}, or null when there are no boxes. */
    public static int[] bounds(final List<BlockBox> town, final int margin) {
        if (town.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final BlockBox b : town) {
            minX = Math.min(minX, b.minX());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        return new int[] {minX - margin, minZ - margin, maxX + margin, maxZ + margin};
    }

    /** The ring cells of a rectangle, clockwise from the north-west corner (each cell once). */
    public static List<int[]> ring(final int[] r) {
        final List<int[]> out = new ArrayList<>();
        for (int x = r[0]; x <= r[2]; x++) {
            out.add(new int[] {x, r[1]});
        }
        for (int z = r[1] + 1; z <= r[3]; z++) {
            out.add(new int[] {r[2], z});
        }
        for (int x = r[2] - 1; x >= r[0]; x--) {
            out.add(new int[] {x, r[3]});
        }
        for (int z = r[3] - 1; z > r[1]; z--) {
            out.add(new int[] {r[0], z});
        }
        return out;
    }

    /** Ring indices where a road crosses (the gates). */
    public static Set<Integer> gates(final List<int[]> ring, final Set<Long> roads) {
        final Set<Integer> out = new HashSet<>();
        for (int i = 0; i < ring.size(); i++) {
            if (roads.contains(RoadPlanner.key(ring.get(i)[0], ring.get(i)[1]))) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Blocks of ring cells {@code from..to-1} (a segment).
     *
     * @param height    ground top per column
     * @param buildable the column may take wall (own land, no building)
     */
    public static List<Piece> blocks(final List<int[]> ring, final int from, final int to, final IntBinaryOperator height,
                                     final Set<Long> roads, final Style s, final int towerEvery,
                                     final BiPredicate<Integer, Integer> buildable) {
        final Set<Integer> gates = gates(ring, roads);
        final Set<Integer> open = new HashSet<>();
        for (final int g : gates) {
            for (int k = -1; k <= 1; k++) {
                open.add(Math.floorMod(g + k, ring.size()));
            }
        }
        final List<Piece> out = new ArrayList<>();
        for (int i = from; i < Math.min(to, ring.size()); i++) {
            final int x = ring.get(i)[0];
            final int z = ring.get(i)[1];
            final int h = height.applyAsInt(x, z);
            if (h == TerrainView.UNKNOWN || !buildable.test(x, z)) {
                continue;
            }
            if (open.contains(i)) {
                if (gates.contains(i)) {
                    // arch over the gate and lanterns on the posts
                    for (int k = -1; k <= 1; k++) {
                        final int[] c = ring.get(Math.floorMod(i + k, ring.size()));
                        out.add(new Piece(c[0], h + s.height(), c[1], s.wall()));
                        out.add(new Piece(c[0], h + s.height() + 1, c[1], s.merlon()));
                    }
                    for (final int k : new int[] {-2, 2}) {
                        final int[] c = ring.get(Math.floorMod(i + k, ring.size()));
                        out.add(new Piece(c[0], h + s.height() + 1, c[1], "minecraft:lantern[hanging=false]"));
                    }
                }
                continue;
            }
            final boolean tower = i % towerEvery == 0 || corner(ring, i);
            final int top = h + s.height() + (tower ? 3 : 0);
            for (int y = h + 1; y <= top; y++) {
                out.add(new Piece(x, y, z, tower ? s.tower() : s.wall()));
            }
            if (tower) {
                out.add(new Piece(x, top + 1, z, s.merlon()));
                out.add(new Piece(x, top + 2, z, "minecraft:lantern[hanging=false]"));
            } else if ((i & 1) == 0) {
                out.add(new Piece(x, top + 1, z, s.merlon()));
            }
        }
        return out;
    }

    /**
     * Blocks of one building site of the walls: an open stretch of ring cells (the site never knows the rest of the
     * ring). Unlike {@link #blocks} nothing wraps around: a gate at one end of the stretch never opens a hole at the
     * other end, the ends are no corners, and corners are real bends of the line (measured over {@link #BEND_REACH}
     * cells) — a diagonal stretch walled cell by cell in steps is a straight wall, not a row of towers. Short stretches
     * (closing a hole, under half the tower spacing) get no tower.
     *
     * @param height    ground top per column ({@link TerrainView#UNKNOWN} = skip the column)
     * @param buildable the column may take wall (own land, no building)
     */
    public static List<Piece> section(final List<int[]> cells, final IntBinaryOperator height, final Set<Long> roads, final Style s,
                                      final int towerEvery, final BiPredicate<Integer, Integer> buildable) {
        final int n = cells.size();
        final boolean[] gate = new boolean[n];
        for (int i = 0; i < n; i++) {
            gate[i] = roads.contains(RoadPlanner.key(cells.get(i)[0], cells.get(i)[1]));
        }
        final List<Piece> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // one tower per bend: where the line turns most sharply
            final double turn = turn(cells, i);
            final boolean corner = turn < BEND_COS && turn <= turn(cells, i - 1) && turn < turn(cells, i + 1);
            final int x = cells.get(i)[0];
            final int z = cells.get(i)[1];
            final int h = height.applyAsInt(x, z);
            if (h == TerrainView.UNKNOWN || !buildable.test(x, z)) {
                continue;
            }
            final boolean open = gate[i] || i > 0 && gate[i - 1] || i + 1 < n && gate[i + 1];
            if (open) {
                if (gate[i]) {
                    for (int k = -1; k <= 1; k++) {
                        if (i + k < 0 || i + k >= n) {
                            continue;
                        }
                        final int[] c = cells.get(i + k);
                        out.add(new Piece(c[0], h + s.height(), c[1], s.wall()));
                        out.add(new Piece(c[0], h + s.height() + 1, c[1], s.merlon()));
                    }
                    for (final int k : new int[] {-2, 2}) {
                        if (i + k < 0 || i + k >= n || gate[i + k]) {
                            continue;
                        }
                        final int[] c = cells.get(i + k);
                        out.add(new Piece(c[0], h + s.height() + 1, c[1], "minecraft:lantern[hanging=false]"));
                    }
                }
                continue;
            }
            final boolean tower = n >= towerEvery / 2 && i % towerEvery == 0 || corner;
            final int top = h + s.height() + (tower ? 3 : 0);
            for (int y = h + 1; y <= top; y++) {
                out.add(new Piece(x, y, z, tower ? s.tower() : s.wall()));
            }
            if (tower) {
                out.add(new Piece(x, top + 1, z, s.merlon()));
                out.add(new Piece(x, top + 2, z, "minecraft:lantern[hanging=false]"));
            } else if ((i & 1) == 0) {
                out.add(new Piece(x, top + 1, z, s.merlon()));
            }
        }
        return out;
    }

    /** Cells before and after a cell compared for a bend of the wall line. */
    public static final int BEND_REACH = 3;

    /** A bend: the cosine of the turn below this (about 40°). */
    static final double BEND_COS = 0.75;

    /**
     * Cosine of the turn of the line at cell {@code i}, both sides measured over {@link #BEND_REACH} cells (1 = straight;
     * also 1 near the ends of the stretch and outside it).
     */
    static double turn(final List<int[]> cells, final int i) {
        if (i - BEND_REACH < 0 || i + BEND_REACH >= cells.size()) {
            return 1;
        }
        final int[] a = cells.get(i - BEND_REACH);
        final int[] c = cells.get(i);
        final int[] b = cells.get(i + BEND_REACH);
        final double ax = c[0] - a[0];
        final double az = c[1] - a[1];
        final double bx = b[0] - c[0];
        final double bz = b[1] - c[1];
        final double la = Math.hypot(ax, az);
        final double lb = Math.hypot(bx, bz);
        if (la < 1e-9 || lb < 1e-9) {
            return 1;
        }
        return (ax * bx + az * bz) / (la * lb);
    }

    /**
     * The most threatened stretch of the border: the outer edge (16 cells, in order) of the own chunk nearest to the
     * enemy capital that faces it (its neighbour in the enemy's direction is not the country's). Null when there is
     * none or it is already in {@code done}.
     */
    public static List<int[]> threatenedEdge(final Set<Long> claims, final int enemyX, final int enemyZ, final Set<Long> done) {
        long best = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE;
        int bestSide = -1;
        final int[][] sides = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (final long c : claims) {
            if (done.contains(c)) {
                continue;
            }
            final int cx = (int) (c >> 32);
            final int cz = (int) c;
            final double d = Math.hypot(cx * 16 + 8 - enemyX, cz * 16 + 8 - enemyZ);
            if (d >= bestD) {
                continue;
            }
            // the side facing the enemy
            final int side = Math.abs(enemyX - (cx * 16 + 8)) >= Math.abs(enemyZ - (cz * 16 + 8))
                ? (enemyX > cx * 16 + 8 ? 1 : 3) : (enemyZ > cz * 16 + 8 ? 2 : 0);
            final long neighbour = ((long) (cx + sides[side][0]) << 32) | ((cz + sides[side][1]) & 0xFFFFFFFFL);
            if (claims.contains(neighbour)) {
                continue; // not the border towards the enemy
            }
            best = c;
            bestD = d;
            bestSide = side;
        }
        if (best == Long.MIN_VALUE) {
            return null;
        }
        final int cx = (int) (best >> 32);
        final int cz = (int) best;
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            out.add(switch (bestSide) {
                case 0 -> new int[] {cx * 16 + i, cz * 16};
                case 1 -> new int[] {cx * 16 + 15, cz * 16 + i};
                case 2 -> new int[] {cx * 16 + i, cz * 16 + 15};
                default -> new int[] {cx * 16, cz * 16 + i};
            });
        }
        return out;
    }

    /** Chunk key of an edge (to remember it). */
    public static long edgeChunk(final List<int[]> edge) {
        return ((long) (edge.get(0)[0] >> 4) << 32) | ((edge.get(0)[1] >> 4) & 0xFFFFFFFFL);
    }

    private static boolean corner(final List<int[]> ring, final int i) {
        final int[] a = ring.get(Math.floorMod(i - 1, ring.size()));
        final int[] b = ring.get(Math.floorMod(i + 1, ring.size()));
        return a[0] != b[0] && a[1] != b[1];
    }
}
