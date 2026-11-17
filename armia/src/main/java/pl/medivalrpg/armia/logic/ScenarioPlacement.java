// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where a scenario country gets its capital (block coordinates, pure): the nearest village when it is close enough
 * to the target, else the target, else the nearest allowed point on rings around it. Allowed = outside every
 * forbidden zone (protected areas such as the spawn, already enlarged by the buffer) and at least {@code minDistance}
 * from every other capital.
 */
public final class ScenarioPlacement {

    public record Point(int x, int z) {

        public double distance(final Point o) {
            return Math.hypot(this.x - (double) o.x, this.z - (double) o.z);
        }
    }

    /** Forbidden rectangle (inclusive block coordinates). */
    public record Zone(int minX, int minZ, int maxX, int maxZ) {

        public Zone {
            final int ax = Math.min(minX, maxX);
            final int bx = Math.max(minX, maxX);
            final int az = Math.min(minZ, maxZ);
            final int bz = Math.max(minZ, maxZ);
            minX = ax;
            maxX = bx;
            minZ = az;
            maxZ = bz;
        }

        public boolean contains(final Point p) {
            return p.x() >= this.minX && p.x() <= this.maxX && p.z() >= this.minZ && p.z() <= this.maxZ;
        }

        /** The zone grown by {@code blocks} on every side. */
        public Zone grow(final int blocks) {
            final int b = Math.max(0, blocks);
            return new Zone(this.minX - b, this.minZ - b, this.maxX + b, this.maxZ + b);
        }
    }

    /** Why a candidate was chosen. */
    public enum Source { VILLAGE, TARGET, SEARCH }

    public record Choice(Point point, Source source) {
    }

    private ScenarioPlacement() {
    }

    public static boolean allowed(final Point p, final List<Zone> zones, final List<Point> others, final int minDistance) {
        for (final Zone z : zones) {
            if (z.contains(p)) {
                return false;
            }
        }
        for (final Point o : others) {
            if (o.distance(p) < minDistance) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param village       nearest village found by the world, or null
     * @param villageRadius the village is used only within this distance of the target
     * @param searchMax     farthest ring searched around the target
     * @param step          ring spacing (and points per ring grow with the radius)
     */
    public static Optional<Choice> choose(final Point target, final Point village, final int villageRadius, final List<Zone> zones,
                                          final List<Point> others, final int minDistance, final int searchMax, final int step) {
        if (village != null && village.distance(target) <= villageRadius && allowed(village, zones, others, minDistance)) {
            return Optional.of(new Choice(village, Source.VILLAGE));
        }
        if (allowed(target, zones, others, minDistance)) {
            return Optional.of(new Choice(target, Source.TARGET));
        }
        final int s = Math.max(8, step);
        for (int r = s; r <= searchMax; r += s) {
            final List<Point> ring = new ArrayList<>();
            final int n = Math.max(8, (int) Math.ceil(2 * Math.PI * r / s));
            for (int i = 0; i < n; i++) {
                final double a = 2 * Math.PI * i / n;
                ring.add(new Point(target.x() + (int) Math.round(Math.cos(a) * r), target.z() + (int) Math.round(Math.sin(a) * r)));
            }
            final Optional<Point> ok = ring.stream().filter(p -> allowed(p, zones, others, minDistance))
                .min(Comparator.comparingDouble(p -> p.distance(target)));
            if (ok.isPresent()) {
                return Optional.of(new Choice(ok.get(), Source.SEARCH));
            }
        }
        return Optional.empty();
    }

    /**
     * Candidates for a land search around {@code target} when it lies in water: rings every {@code step} blocks up to
     * {@code maxRadius}, ordered by distance to the target plus a quarter of the distance to the map centre (so land
     * nearer to the target and nearer to the middle of the map is tried first).
     */
    public static List<Point> spiralCandidates(final Point target, final Point mapCentre, final int step, final int maxRadius) {
        final int s = Math.max(8, step);
        final List<Point> out = new ArrayList<>();
        for (int r = s; r <= maxRadius; r += s) {
            final int n = Math.max(8, (int) Math.ceil(2 * Math.PI * r / s));
            for (int i = 0; i < n; i++) {
                final double a = 2 * Math.PI * i / n;
                out.add(new Point(target.x() + (int) Math.round(Math.cos(a) * r), target.z() + (int) Math.round(Math.sin(a) * r)));
            }
        }
        final Point centre = mapCentre == null ? target : mapCentre;
        out.sort(Comparator.comparingDouble((Point p) -> p.distance(target) + 0.25 * p.distance(centre)));
        return out;
    }

    /** Result of a (synchronous, test) land search. */
    public record LandResult(Point point, int tried, int rejectedByDistance) {
    }

    /**
     * First candidate of {@link #spiralCandidates} that is allowed (zones, distances) and has a dry, flat spot
     * ({@link #flattest} within {@code flatRadius}). The plugin does the same asynchronously, one candidate per step.
     *
     * @param heightAt surface height at a block, null = water/lava/unusable
     */
    public static Optional<LandResult> findLand(final Point target, final Point mapCentre, final int step, final int maxRadius,
                                                final List<Zone> zones, final List<Point> others, final int minDistance, final int flatRadius,
                                                final java.util.function.BiFunction<Integer, Integer, Integer> heightAt) {
        int tried = 0;
        int rejected = 0;
        for (final Point p : spiralCandidates(target, mapCentre, step, maxRadius)) {
            if (!allowed(p, zones, others, minDistance)) {
                rejected++;
                continue;
            }
            tried++;
            if (heightAt.apply(p.x(), p.z()) == null) {
                continue;
            }
            final Point spot = flattest(p, flatRadius, 4, (dx, dz) -> heightAt.apply(p.x() + dx, p.z() + dz));
            if (spot != null) {
                return Optional.of(new LandResult(spot, tried, rejected));
            }
        }
        return Optional.empty();
    }

    /**
     * Best building spot among samples around a centre: dry (no liquid on top) and flattest (smallest height
     * difference to its neighbours), then the closest to the centre. Null when every sample is wet.
     *
     * @param heights height at (centre + dx, centre + dz), dx/dz in [-radius, radius]; null entries = liquid / unusable
     */
    public static Point flattest(final Point centre, final int radius, final int step, final java.util.function.BiFunction<Integer, Integer, Integer> heights) {
        Point best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                final Integer h = heights.apply(dx, dz);
                if (h == null) {
                    continue;
                }
                int spread = 0;
                boolean wet = false;
                for (final int[] o : new int[][] {{-3, 0}, {3, 0}, {0, -3}, {0, 3}}) {
                    final Integer n = heights.apply(dx + o[0], dz + o[1]);
                    if (n == null) {
                        wet = true;
                        break;
                    }
                    spread = Math.max(spread, Math.abs(n - h));
                }
                if (wet) {
                    continue;
                }
                final double score = spread * 10.0 + Math.hypot(dx, dz) / Math.max(1, radius);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Point(centre.x() + dx, centre.z() + dz);
                }
            }
        }
        return best;
    }
}
