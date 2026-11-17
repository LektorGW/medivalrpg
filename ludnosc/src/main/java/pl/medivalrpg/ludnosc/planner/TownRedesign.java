// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * Redesign of a whole town, "plan v2" (pure, unit tested). A regular target plan around the square: avenues through
 * the centre and cross streets every {@link #blockSize} blocks (wider and denser in later epochs), a market square,
 * quarters between the streets (every fourth one a green quarter that keeps its trees). The difference to the town as
 * it stands says what to do: new street cells, buildings standing in the way of a street or the square that are taken
 * down (only Ludnosc buildings that may be demolished — never heritage, never anything else), and free quarters to
 * level. The work is done gradually by the planner (queue, budget, daily limits).
 */
public final class TownRedesign {

    /** A building as the redesign sees it. */
    public record Existing(UUID id, BlockBox box, boolean demolishable) {
    }

    /** A quarter between streets: bounds, green (kept natural) or not, free of buildings or not. */
    public record Quarter(int minX, int minZ, int maxX, int maxZ, boolean green, boolean free) {
    }

    /**
     * The plan and its difference to the town.
     *
     * @param roads     target street cells (incl. the square)
     * @param newRoads  street cells that do not exist yet (in order: avenues first, then cross streets)
     * @param demolish  buildings in the way
     * @param quarters  quarters of the plan
     */
    public record Plan(Set<Long> roads, List<Long> newRoads, List<UUID> demolish, List<Quarter> quarters, int blockSize) {
    }

    private TownRedesign() {
    }

    /** Distance between parallel streets in an epoch: the Middle Ages are loose, the modern grid is dense. */
    public static int blockSize(final int era) {
        return switch (era) {
            case 2 -> 28;
            case 3 -> 24;
            case 4 -> 20;
            default -> 32;
        };
    }

    /** Half size of the market square around the centre. */
    public static int squareHalf(final int era) {
        return era >= 2 ? 6 : 4;
    }

    /** Width of the avenues through the centre (modern epoch: two lanes). */
    public static int avenueWidth(final int era) {
        return era >= 4 ? 2 : 1;
    }

    /**
     * @param cx, cz     the square (town centre)
     * @param radius     half size of the planned area (blocks)
     * @param existing   the country's buildings
     * @param roads      the street cells as they are
     * @param own        the plan stays on the country's land
     */
    public static Plan plan(final int cx, final int cz, final int radius, final int era, final List<Existing> existing,
                            final Set<Long> roads, final java.util.function.BiPredicate<Integer, Integer> own) {
        final int b = blockSize(era);
        final int square = squareHalf(era);
        final Set<Long> target = new LinkedHashSet<>();
        final List<Long> order = new ArrayList<>();
        // the square
        for (int x = cx - square; x <= cx + square; x++) {
            for (int z = cz - square; z <= cz + square; z++) {
                add(target, order, x, z, own);
            }
        }
        // avenues through the centre, then cross streets every b blocks
        final int width = avenueWidth(era);
        for (int k = 0; k < width; k++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                add(target, order, x, cz + k, own);
            }
            for (int z = cz - radius; z <= cz + radius; z++) {
                add(target, order, cx + k, z, own);
            }
        }
        for (int off = b; off <= radius; off += b) {
            for (final int sign : new int[] {1, -1}) {
                for (int x = cx - radius; x <= cx + radius; x++) {
                    add(target, order, x, cz + sign * off, own);
                }
                for (int z = cz - radius; z <= cz + radius; z++) {
                    add(target, order, cx + sign * off, z, own);
                }
            }
        }
        // buildings in the way: demolished when allowed, otherwise the street goes around (its cells are dropped)
        final List<UUID> demolish = new ArrayList<>();
        for (final Existing e : existing) {
            boolean hit = false;
            for (int x = e.box().minX(); x <= e.box().maxX() && !hit; x++) {
                for (int z = e.box().minZ(); z <= e.box().maxZ() && !hit; z++) {
                    hit = target.contains(RoadPlanner.key(x, z));
                }
            }
            if (!hit) {
                continue;
            }
            if (e.demolishable()) {
                demolish.add(e.id());
            } else {
                for (int x = e.box().minX() - 1; x <= e.box().maxX() + 1; x++) {
                    for (int z = e.box().minZ() - 1; z <= e.box().maxZ() + 1; z++) {
                        target.remove(RoadPlanner.key(x, z));
                    }
                }
            }
        }
        final List<Long> newRoads = new ArrayList<>();
        for (final long k : order) {
            if (target.contains(k) && !roads.contains(k)) {
                newRoads.add(k);
            }
        }
        // quarters between the streets
        final List<Quarter> quarters = new ArrayList<>();
        int qi = 0;
        for (int qx = -radius / b - 1; qx <= radius / b; qx++) {
            for (int qz = -radius / b - 1; qz <= radius / b; qz++) {
                final int minX = cx + qx * b + 1 + (qx == 0 ? width - 1 : 0);
                final int minZ = cz + qz * b + 1 + (qz == 0 ? width - 1 : 0);
                final int maxX = cx + (qx + 1) * b - 1;
                final int maxZ = cz + (qz + 1) * b - 1;
                if (maxX < cx - radius || minX > cx + radius || maxZ < cz - radius || minZ > cz + radius
                    || !own.test((minX + maxX) / 2, (minZ + maxZ) / 2)) {
                    continue;
                }
                boolean free = true;
                for (final Existing e : existing) {
                    if (demolish.contains(e.id())) {
                        continue; // taken down first: the quarter is levelled and built up afterwards
                    }
                    if (e.box().maxX() >= minX - 2 && e.box().minX() <= maxX + 2 && e.box().maxZ() >= minZ - 2
                        && e.box().minZ() <= maxZ + 2) {
                        free = false;
                        break;
                    }
                }
                final boolean nearSquare = Math.abs((minX + maxX) / 2 - cx) <= b && Math.abs((minZ + maxZ) / 2 - cz) <= b;
                quarters.add(new Quarter(minX, minZ, maxX, maxZ, !nearSquare && qi++ % 4 == 3, free));
            }
        }
        return new Plan(target, newRoads, demolish, quarters, b);
    }

    private static void add(final Set<Long> target, final List<Long> order, final int x, final int z,
                            final java.util.function.BiPredicate<Integer, Integer> own) {
        if (own.test(x, z) && target.add(RoadPlanner.key(x, z))) {
            order.add(RoadPlanner.key(x, z));
        }
    }
}
