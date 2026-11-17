// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds a safe place to stand: feet and head in passable blocks, a solid non-dangerous block below (no lava, fire,
 * water or fall into the void). The original block is tried first, then the nearest candidates within
 * {@code radius} (by squared distance; ties: horizontal rings first, then vertical offsets 0, +1, −1, +2, −2, …).
 * Pure – the world is read through {@link Probe}, so it is unit-tested without a server.
 */
public final class SafeSpot {

    /** What a block means for a player standing there. */
    public enum Cell {
        /** Full block you can stand on (stone, planks, grass…). */
        SOLID,
        /** Air, plants, open doors – can be walked through. */
        PASSABLE,
        /** Water and other liquids except lava (you would swim or drown). */
        LIQUID,
        /** Lava, fire, magma, cactus, powder snow, campfire… – never stand in or on it. */
        DANGER
    }

    @FunctionalInterface
    public interface Probe {
        Cell at(int x, int y, int z);
    }

    public record Spot(int x, int y, int z) {
    }

    /** Default search radius of {@code /kraj admin tp}. */
    public static final int DEFAULT_RADIUS = 8;

    private SafeSpot() {
    }

    /** Feet at (x, y, z) are safe. */
    public static boolean safe(final Probe probe, final int x, final int y, final int z) {
        return probe.at(x, y, z) == Cell.PASSABLE
            && probe.at(x, y + 1, z) == Cell.PASSABLE
            && probe.at(x, y - 1, z) == Cell.SOLID;
    }

    /**
     * @param minY lowest feet y to try (world min + 1)
     * @param maxY highest feet y to try (world max − 2)
     */
    public static Optional<Spot> find(final Probe probe, final int x, final int y, final int z, final int radius,
                                      final int minY, final int maxY) {
        if (y >= minY && y <= maxY && safe(probe, x, y, z)) {
            return Optional.of(new Spot(x, y, z));
        }
        final int r = Math.max(0, radius);
        final List<int[]> columns = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                columns.add(new int[] {dx, dz});
            }
        }
        columns.sort(Comparator.<int[]>comparingInt(c -> c[0] * c[0] + c[1] * c[1])
            .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        Spot best = null;
        int bestCost = Integer.MAX_VALUE;
        for (final int[] c : columns) {
            final int horizontal = c[0] * c[0] + c[1] * c[1];
            if (horizontal > bestCost) {
                break; // every further column is already worse than the best spot found
            }
            for (int i = 0; i <= 2 * r; i++) {
                final int dy = (i % 2 == 1) ? (i + 1) / 2 : -(i / 2);
                final int fy = y + dy;
                if (fy < minY || fy > maxY) {
                    continue;
                }
                final int cost = horizontal + dy * dy;
                if (cost < bestCost && safe(probe, x + c[0], fy, z + c[1])) {
                    best = new Spot(x + c[0], fy, z + c[1]);
                    bestCost = cost;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
