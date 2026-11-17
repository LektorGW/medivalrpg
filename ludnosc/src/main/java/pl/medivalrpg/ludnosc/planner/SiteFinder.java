// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * Decides WHERE a building goes (pure, unit tested).
 *
 * <ul>
 *   <li>own land only, no water, no obstacles (trees are kept, player buildings untouched), gap to other plots;</li>
 *   <li>hard limit on the height difference inside the footprint and its margin (rejected above {@code maxSlope};
 *       a second pass with a relaxed limit is used only when nothing else fits);</li>
 *   <li>flat ground is preferred; on a slope the building stands along the contour lines (long side parallel to the
 *       slope) with the door on the higher side; on flat ground the door faces the town centre;</li>
 *   <li>close to existing roads (plots line up along streets);</li>
 *   <li>distance to the centre matches the zone: representative buildings in the centre, houses around it, workshops
 *       and fields on the outskirts, walls and towers at the border.</li>
 * </ul>
 */
public final class SiteFinder {

    /**
     * Result: origin (centre of the footprint, floor level y), rotation and score (lower = better).
     *
     * @param doorX column in front of the door (where the road starts)
     */
    public record Spot(int x, int y, int z, int rotation, double score, int doorX, int doorZ, int slope) {
    }

    /**
     * Search settings.
     *
     * @param radius   max distance from the centre
     * @param step     grid step of the candidates
     * @param spacing  free gap kept around other plots
     * @param maxSlope max height difference in the footprint + margin
     */
    public record Settings(int radius, int step, int spacing, int maxSlope) {
    }

    private SiteFinder() {
    }

    /** Rotation so that the front (door) looks at (tx, tz). Front = south at rotation 0. */
    static int rotationTowards(final int fromX, final int fromZ, final int tx, final int tz) {
        final int dx = tx - fromX;
        final int dz = tz - fromZ;
        if (Math.abs(dz) >= Math.abs(dx)) {
            return dz >= 0 ? 0 : 2;
        }
        return dx >= 0 ? 3 : 1;
    }

    /** Unit vector of the front after {@code rotation} quarter turns (front = south at 0). */
    public static int[] front(final int rotation) {
        return switch (Math.floorMod(rotation, 4)) {
            case 1 -> new int[] {-1, 0};
            case 2 -> new int[] {0, -1};
            case 3 -> new int[] {1, 0};
            default -> new int[] {0, 1};
        };
    }

    /** Radius of the town square / core kept free for centre buildings. */
    static final double CORE = 14;

    static double zonePenalty(final String zone, final double distance, final int borderDistance) {
        final double core = distance < CORE ? (CORE - distance) * 2.5 : 0;
        return switch (zone) {
            case "center" -> distance * 1.0;
            case "outer" -> Math.abs(distance - 34) * 0.5 + core;
            case "farm" -> Math.abs(distance - 40) * 0.5 + core;
            case "border" -> borderDistance * 2.0 + core;
            case "mountain", "water", "forest", "shore" -> Math.abs(distance - 30) * 0.3 + core;
            default -> Math.abs(distance - 20) * 0.6 + core;
        };
    }

    static int borderDistance(final TerrainView view, final int x, final int z, final int max) {
        for (int r = 1; r <= max; r++) {
            if (!view.own(x + r, z) || !view.own(x - r, z) || !view.own(x, z + r) || !view.own(x, z - r)) {
                return r;
            }
        }
        return max;
    }

    static int roadDistance(final Set<Long> roads, final int x, final int z, final int centreX, final int centreZ) {
        int best = Math.abs(x - centreX) + Math.abs(z - centreZ);
        for (final long k : roads) {
            best = Math.min(best, Math.abs(RoadPlanner.keyX(k) - x) + Math.abs(RoadPlanner.keyZ(k) - z));
        }
        return best;
    }

    /**
     * Rotation for a candidate: on flat ground towards the centre; on a slope with the long side along the contour
     * and the front uphill.
     *
     * @param gx height gain from west to east over the footprint, gz from north to south
     */
    static int rotationFor(final int width, final int depth, final double gx, final double gz, final int x, final int z,
                           final int centreX, final int centreZ) {
        if (Math.abs(gx) + Math.abs(gz) < 1.5) {
            return Math.hypot(x - centreX, z - centreZ) < 2 ? 0 : rotationTowards(x, z, centreX, centreZ);
        }
        final boolean slopeAlongZ = Math.abs(gz) >= Math.abs(gx);
        // rotations 0/2 keep the template's width along x
        final boolean wideX = width >= depth;
        final int[] options = slopeAlongZ == wideX ? new int[] {0, 2} : new int[] {1, 3};
        int best = options[0];
        double bestDot = Double.NEGATIVE_INFINITY;
        for (final int q : options) {
            final int[] f = front(q);
            final double dot = f[0] * gx + f[1] * gz; // front pointing uphill = positive
            if (dot > bestDot) {
                bestDot = dot;
                best = q;
            }
        }
        return best;
    }

    /**
     * One step of relaxing the rules when no ideal place exists.
     *
     * @param ownMargin    own land needed around the footprint (blocks)
     * @param waterMargin  water at or above the dug level this close rejects the site
     * @param spacing      gap to other plots ({@code -1} = the settings' value)
     * @param slopeExtra   added to the allowed height difference (the terrain is terraced / walled by the builders)
     * @param allRotations try every rotation, not only the preferred one
     */
    public record Relax(int ownMargin, int waterMargin, int spacing, int slopeExtra, boolean allRotations) {

        public static final Relax STRICT = new Relax(2, WATER_MARGIN, -1, 0, false);

        /** Strict first, then smaller margins, steeper ground (terraces, retaining walls), less spacing, any rotation. */
        public static final List<Relax> LEVELS = List.of(
            STRICT,
            new Relax(1, 2, -1, 3, true),
            new Relax(1, 2, 1, 6, true),
            new Relax(0, 1, 1, 9, true),
            new Relax(0, 1, 0, 12, true));

        int spacing(final int configured) {
            return this.spacing < 0 ? configured : Math.min(this.spacing, configured);
        }
    }

    /** Why candidates were rejected (for a readable "no place" message). */
    public static final class Stats {
        public int candidates;
        public int notOwn;
        public int unknown;
        public int water;
        public int taken;
        public int slope;

        /** Polish reason, e.g. "za mało ziemi kraju (potrzeba ok. 2 chunków)". */
        public String reason(final int width, final int depth) {
            if (this.candidates == 0) {
                return "brak ziemi kraju w zasięgu planisty";
            }
            final int[] counts = {this.unknown, this.notOwn, this.water, this.taken, this.slope};
            int best = 0;
            for (int i = 1; i < counts.length; i++) {
                if (counts[i] > counts[best]) {
                    best = i;
                }
            }
            int second = -1;
            for (int i = 0; i < counts.length; i++) {
                if (i != best && counts[i] > 0 && counts[i] * 3 >= counts[best] && (second < 0 || counts[i] > counts[second])) {
                    second = i;
                }
            }
            final String first = this.text(best, width, depth);
            return second < 0 || best == 0 ? first : first + "; " + this.text(second, width, depth);
        }

        private String text(final int what, final int width, final int depth) {
            final int chunks = Math.max(1, (int) Math.ceil((Math.max(width, depth) + 4) / 16.0)
                * (int) Math.ceil((Math.min(width, depth) + 4) / 16.0));
            return switch (what) {
                case 0 -> "teren kraju nie jest jeszcze zbadany (chunki się wczytują) — spróbuj za chwilę";
                case 1 -> "za mało ziemi kraju: budynek " + width + "×" + depth + " potrzebuje ok. " + chunks
                    + (chunks == 1 ? " wolnego chunka" : " wolnych chunków") + " przy osadzie";
                case 2 -> "w pobliżu jest woda (jezioro lub rzeka) — budynek nie może stać przy brzegu";
                case 3 -> "kolizja z innymi budynkami, placami budowy lub drogami";
                default -> "teren za stromy nawet dla tarasów i murów oporowych";
            };
        }
    }

    /** Strict search for citizen huts: safety gaps and slope never relax. */
    public static Search strictSearch(final TerrainView view, final int[] size, final int x, final int z,
                                      final String zone, final List<BlockBox> taken, final Set<Long> roads,
                                      final Settings settings, final int depth) {
        final Stats stats = new Stats();
        return new Search(search(view, size, x, z, zone, taken, roads, settings, settings.maxSlope(), depth, Relax.STRICT, stats), stats, 0);
    }

    /** Result of a search: the spot (or null), the rejection statistics and the relax level used (0 = strict). */
    public record Search(Spot spot, Stats stats, int level) {
    }

    /**
     * Best spot for a building around the centre: strict rules first, then {@link Relax#LEVELS} one by one (smaller
     * margins, steeper ground, less spacing, any rotation); null when even the loosest rules find nothing.
     *
     * @param size   template width (x) and depth (z) at rotation 0
     * @param taken  boxes of existing buildings, sites, queued orders
     */
    public static Spot find(final TerrainView view, final int[] size, final int centreX, final int centreZ, final String zone,
                            final List<BlockBox> taken, final Set<Long> roads, final Settings s) {
        return find(view, size, centreX, centreZ, zone, taken, roads, s, 0);
    }

    /**
     * As {@link #find(TerrainView, int[], int, int, String, List, Set, Settings)} for a template that reaches
     * {@code depth} layers below the floor (its ground layer: well shaft, cellar, mine).
     */
    public static Spot find(final TerrainView view, final int[] size, final int centreX, final int centreZ, final String zone,
                            final List<BlockBox> taken, final Set<Long> roads, final Settings s, final int depth) {
        return find(view, size, centreX, centreZ, zone, taken, roads, s, depth, false);
    }

    /** {@code flat}: high-rise buildings only on flat plots (slope ≤ 2, never relaxed). */
    public static Spot find(final TerrainView view, final int[] size, final int centreX, final int centreZ, final String zone,
                            final List<BlockBox> taken, final Set<Long> roads, final Settings s, final int depth, final boolean flat) {
        return search(view, size, centreX, centreZ, zone, taken, roads, s, depth, flat).spot();
    }

    /** The search with statistics (for the reason of a refusal). */
    public static Search search(final TerrainView view, final int[] size, final int centreX, final int centreZ, final String zone,
                                final List<BlockBox> taken, final Set<Long> roads, final Settings s, final int depth,
                                final boolean flat) {
        final Stats stats = new Stats();
        if (flat) {
            final Spot spot = search(view, size, centreX, centreZ, zone, taken, roads, s, Math.min(2, s.maxSlope()), depth,
                Relax.STRICT, stats);
            return new Search(spot, stats, 0);
        }
        for (int level = 0; level < Relax.LEVELS.size(); level++) {
            final Relax r = Relax.LEVELS.get(level);
            final Stats round = new Stats();
            final Spot spot = search(view, size, centreX, centreZ, zone, taken, roads, s, s.maxSlope() + r.slopeExtra(), depth, r,
                round);
            if (level == 0 || round.candidates > 0) {
                // the statistics of the loosest round say best why nothing fits
                stats.candidates = round.candidates;
                stats.notOwn = round.notOwn;
                stats.unknown = round.unknown;
                stats.water = round.water;
                stats.taken = round.taken;
                stats.slope = round.slope;
            }
            if (spot != null) {
                return new Search(spot, stats, level);
            }
        }
        return new Search(null, stats, Relax.LEVELS.size() - 1);
    }

    /** Water touching the plot + this margin at or above the dug level rejects a site (except water buildings). */
    public static final int WATER_MARGIN = 3;

    private static Spot search(final TerrainView view, final int[] size, final int centreX, final int centreZ,
                               final String zone, final List<BlockBox> taken, final Set<Long> roads, final Settings s,
                               final int maxSlope, final int depth, final Relax relax, final Stats stats) {
        Spot best = null;
        for (int dz = -s.radius(); dz <= s.radius(); dz += s.step()) {
            for (int dx = -s.radius(); dx <= s.radius(); dx += s.step()) {
                if (dx * dx + dz * dz > s.radius() * s.radius()) {
                    continue;
                }
                final Spot spot = evaluate(view, size[0], size[1], centreX + dx, centreZ + dz, centreX, centreZ, zone, taken,
                    roads, s.spacing(), maxSlope, depth, relax, stats);
                if (spot != null && (best == null || spot.score() < best.score())) {
                    best = spot;
                }
            }
        }
        return best;
    }

    /** Scores one candidate (rotation chosen here); null when unsuitable. */
    static Spot evaluate(final TerrainView view, final int width, final int depth, final int x, final int z, final int centreX,
                         final int centreZ, final String zone, final List<BlockBox> taken, final Set<Long> roads,
                         final int spacing, final int maxSlope) {
        return evaluate(view, width, depth, x, z, centreX, centreZ, zone, taken, roads, spacing, maxSlope, 0);
    }

    static Spot evaluate(final TerrainView view, final int width, final int depth, final int x, final int z, final int centreX,
                         final int centreZ, final String zone, final List<BlockBox> taken, final Set<Long> roads,
                         final int spacing, final int maxSlope, final int below) {
        return evaluate(view, width, depth, x, z, centreX, centreZ, zone, taken, roads, spacing, maxSlope, below, Relax.STRICT,
            new Stats());
    }

    static Spot evaluate(final TerrainView view, final int width, final int depth, final int x, final int z, final int centreX,
                         final int centreZ, final String zone, final List<BlockBox> taken, final Set<Long> roads,
                         final int spacing, final int maxSlope, final int below, final Relax relax, final Stats stats) {
        // quick reject on the centre and the corners of the larger square (+ own margin) before sampling
        if (!view.own(x, z)) {
            return null; // not a candidate at all (outside the country)
        }
        stats.candidates++;
        if (view.height(x, z) == TerrainView.UNKNOWN) {
            stats.unknown++;
            return null;
        }
        // the footprint + own margin in either orientation must be the country's (corners), and scanned
        final int hx = width / 2 + relax.ownMargin();
        final int hz = depth / 2 + relax.ownMargin();
        int quick = 0;
        for (final int[] half : new int[][] {{hx, hz}, {hz, hx}}) {
            boolean ok = true;
            for (final int[] c : new int[][] {{x - half[0], z - half[1]}, {x + half[0], z - half[1]}, {x - half[0], z + half[1]},
                {x + half[0], z + half[1]}}) {
                if (!view.own(c[0], c[1])) {
                    quick = Math.max(quick, 1);
                    ok = false;
                    break;
                }
                if (view.height(c[0], c[1]) == TerrainView.UNKNOWN) {
                    quick = 2;
                    ok = false;
                    break;
                }
            }
            if (ok) {
                quick = 0;
                break;
            }
        }
        if (quick == 1) {
            stats.notOwn++;
            return null;
        }
        if (quick == 2) {
            stats.unknown++;
            return null;
        }
        // slope (east-west, north-south) from the four sides of the area
        final double gx = (avg(view, x + width / 2, z, 2) - avg(view, x - width / 2, z, 2));
        final double gz = (avg(view, x, z + depth / 2, 2) - avg(view, x, z - depth / 2, 2));
        final int preferred = rotationFor(width, depth, gx, gz, x, z, centreX, centreZ);
        Spot best = null;
        int reject = -1;
        for (int k = 0; k < (relax.allRotations() ? 4 : 1); k++) {
            final int q = Math.floorMod(preferred + k, 4);
            final int[] why = new int[1];
            final Spot s = evaluateRotation(view, width, depth, x, z, centreX, centreZ, zone, taken, roads,
                relax.spacing(spacing), maxSlope, below, relax, q, k > 0 ? 4.0 : 0, why);
            if (s != null && (best == null || s.score() < best.score())) {
                best = s;
            }
            if (s == null && reject < 0) {
                reject = why[0];
            }
        }
        if (best == null) {
            switch (reject) {
                case 1 -> stats.notOwn++;
                case 2 -> stats.unknown++;
                case 3 -> stats.water++;
                case 4 -> stats.taken++;
                default -> stats.slope++;
            }
        }
        return best;
    }

    /** One rotation; null with {@code why[0]} = 1 not own, 2 unknown, 3 water, 4 taken / road, 5 slope. */
    private static Spot evaluateRotation(final TerrainView view, final int width, final int depth, final int x, final int z,
                                         final int centreX, final int centreZ, final String zone, final List<BlockBox> taken,
                                         final Set<Long> roads, final int spacing, final int maxSlope, final int below,
                                         final Relax relax, final int q, final double rotationPenalty, final int[] why) {
        final int w = q % 2 == 0 ? width : depth;
        final int d = q % 2 == 0 ? depth : width;
        final int minX = x - w / 2;
        final int minZ = z - d / 2;
        final int maxX = minX + w - 1;
        final int maxZ = minZ + d - 1;
        for (final BlockBox t : taken) {
            if (t != null && t.maxX() >= minX - spacing && t.minX() <= maxX + spacing
                && t.maxZ() >= minZ - spacing && t.minZ() <= maxZ + spacing) {
                why[0] = 4;
                return null;
            }
        }
        // Every footprint cell; in the surrounding margin sample every second cell and the border.
        final int m = Math.max(1, relax.ownMargin());
        final int[] hs = new int[(w + 2 * m) * (d + 2 * m)];
        int n = 0;
        for (int cz = minZ - m; cz <= maxZ + m; cz++) {
            for (int cx = minX - m; cx <= maxX + m; cx++) {
                final boolean inside = cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ;
                if (inside && !view.own(cx, cz)) {
                    why[0] = 1;
                    return null;
                }
                // Safety checks cover every cell. Sampling heights must not hide a single water block,
                // tree trunk, player block or street on the other colour of the checkerboard.
                if (inside && view.liquid(cx, cz)) {
                    why[0] = 3;
                    return null;
                }
                if (inside && (view.obstacle(cx, cz) || roads.contains(RoadPlanner.key(cx, cz)))) {
                    why[0] = 4;
                    return null;
                }
                final boolean edge = cx == minX - m || cx == maxX + m || cz == minZ - m || cz == maxZ + m;
                if (!inside && !edge && ((cx + cz) & 1) != 0) {
                    continue;
                }
                final int h = view.height(cx, cz);
                if (h == TerrainView.UNKNOWN) {
                    if (inside) {
                        why[0] = 2;
                        return null;
                    }
                    continue;
                }
                hs[n++] = h;
            }
        }
        if (n == 0) {
            why[0] = 2;
            return null;
        }
        final int[] sorted = Arrays.copyOf(hs, n);
        Arrays.sort(sorted);
        final int median = sorted[n / 2];
        final int slope = sorted[n - 1] - sorted[0];
        if (slope > maxSlope) {
            why[0] = 5;
            return null;
        }
        double flat = 0;
        for (int i = 0; i < n; i++) {
            flat += Math.abs(sorted[i] - median);
        }
        flat /= n;
        final int[] f = front(q);
        final int doorX = f[0] < 0 ? minX - 1 : f[0] > 0 ? maxX + 1 : x;
        final int doorZ = f[1] < 0 ? minZ - 1 : f[1] > 0 ? maxZ + 1 : z;
        int y = median;
        final int hd = view.height(doorX, doorZ);
        if (hd != TerrainView.UNKNOWN && !view.liquid(doorX, doorZ)) {
            y = Math.max(median - 1, Math.min(median + 1, hd)); // threshold ≤ 1 block above the ground at the door
        }
        // Reserve a walkable approach: a valid footprint alone can leave the entrance against a cliff,
        // tree, another building or foreign land. A road is welcome here, but never inside the building.
        int previous = y;
        for (int step = 0; step < 2; step++) {
            final int ax = doorX + f[0] * step;
            final int az = doorZ + f[1] * step;
            final int h = view.height(ax, az);
            if (!view.own(ax, az) || h == TerrainView.UNKNOWN || view.liquid(ax, az)
                || view.obstacle(ax, az) && !roads.contains(RoadPlanner.key(ax, az))) {
                why[0] = !view.own(ax, az) ? 1 : h == TerrainView.UNKNOWN ? 2 : view.liquid(ax, az) ? 3 : 4;
                return null;
            }
            for (final BlockBox t : taken) {
                if (t != null && ax >= t.minX() && ax <= t.maxX() && az >= t.minZ() && az <= t.maxZ()) {
                    why[0] = 4;
                    return null;
                }
            }
            if (Math.abs(h - previous) > 1) {
                why[0] = 5;
                return null;
            }
            previous = h;
        }
        // water: never dig next to a lake or river at or below its level (it would flood the site); keep a distance
        double waterPenalty = 0;
        if (!"water".equals(zone)) {
            final int level = Drainage.waterLevelNear(view, minX, minZ, maxX, maxZ, relax.waterMargin());
            if (level != TerrainView.UNKNOWN) {
                if (level >= y - below - 1) {
                    why[0] = 3;
                    return null;
                }
                waterPenalty += 12;
            }
            for (int cz = minZ - 6; cz <= maxZ + 6 && waterPenalty == 0; cz += 2) {
                for (int cx = minX - 6; cx <= maxX + 6; cx += 2) {
                    if (view.liquid(cx, cz)) {
                        waterPenalty = 5;
                        break;
                    }
                }
            }
        }
        final double distance = Math.hypot(x - centreX, z - centreZ);
        final int border = "border".equals(zone) ? borderDistance(view, x, z, 24) : 0;
        double terrainBonus = 0;
        if ("mountain".equals(zone) || "water".equals(zone) || "forest".equals(zone) || "shore".equals(zone)) {
            // shore (glassworks, brickworks): near water for sand and clay, but on dry ground like any building
            final int found = nearby(view, "shore".equals(zone) ? "water" : zone, minX, minZ, maxX, maxZ, "shore".equals(zone) ? 14 : 8);
            if ("water".equals(zone) && found == 0) {
                why[0] = 3;
                return null; // a harbour needs water next to it
            }
            terrainBonus = -Math.min(20, found) * 1.5;
        }
        final double score = flat * 3.0 + slope * 2.0 + zonePenalty(zone, distance, border) + terrainBonus + waterPenalty
            + roadDistance(roads, doorX, doorZ, centreX, centreZ) * 0.3 + rotationPenalty;
        return new Spot(x, y, z, q, score, doorX, doorZ, slope);
    }

    /** Rock / water / trees within {@code r} blocks of the footprint (sampled every second block). */
    static int nearby(final TerrainView view, final String zone, final int minX, final int minZ, final int maxX, final int maxZ,
                      final int r) {
        int n = 0;
        for (int z = minZ - r; z <= maxZ + r; z += 2) {
            for (int x = minX - r; x <= maxX + r; x += 2) {
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    continue;
                }
                final boolean hit = switch (zone) {
                    case "mountain" -> view.stone(x, z);
                    case "water" -> view.liquid(x, z);
                    default -> view.tree(x, z);
                };
                if (hit) {
                    n++;
                }
            }
        }
        return n;
    }

    private static double avg(final TerrainView view, final int x, final int z, final int r) {
        double sum = 0;
        int n = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                final int h = view.height(x + dx, z + dz);
                if (h != TerrainView.UNKNOWN) {
                    sum += h;
                    n++;
                }
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
