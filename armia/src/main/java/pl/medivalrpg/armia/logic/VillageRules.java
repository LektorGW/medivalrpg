// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Point;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Zone;

/**
 * Villages taken over by NPC countries (command and automatic): the rules of the automat, the scan grid, names,
 * random personalities and the order in which village chunks are claimed. Pure.
 */
public final class VillageRules {

    /**
     * @param enabled           automat on
     * @param chance            chance a found village becomes a country (each village is rolled once)
     * @param minSpawnDistance  distance from protected areas (Kraje) / the world spawn
     * @param minCountryDistance distance from any claimed chunk of any country (players too)
     * @param maxCountries      no automat when there are this many NPC countries in total (scenario included)
     * @param maxAutoCountries  at most this many countries made by the automat
     * @param scanRadius        one-time scan of the pre-generated world up to this radius from the centre
     * @param scanStep          grid step of the scan (one structure search per point)
     */
    public record Rules(boolean enabled, double chance, int minSpawnDistance, int minCountryDistance, int maxCountries,
                        int maxAutoCountries, int scanRadius, int scanStep) {

        public static Rules defaults() {
            return new Rules(true, 0.33, 600, 400, 18, 6, 4000, 800);
        }
    }

    public enum Decision { CREATE, SEEN, LIMIT, SPAWN, COUNTRY, CHANCE, DISABLED }

    private VillageRules() {
    }

    /**
     * Should the automat make a country of the village at {@code village}?
     *
     * @param seen            the village was already rolled (never twice)
     * @param zones           protected areas (spawn), already grown by {@code minSpawnDistance}
     * @param countryChunks   centres of claimed chunks of all countries in this world
     * @param npcCountries    NPC countries in total
     * @param autoCountries   countries made by the automat so far
     * @param roll            random number in [0, 1)
     */
    public static Decision decide(final Rules r, final Point village, final boolean seen, final List<Zone> zones,
                                  final Collection<Point> countryChunks, final int npcCountries, final int autoCountries, final double roll) {
        if (!r.enabled()) {
            return Decision.DISABLED;
        }
        if (seen) {
            return Decision.SEEN;
        }
        if (npcCountries >= r.maxCountries() || autoCountries >= r.maxAutoCountries()) {
            return Decision.LIMIT;
        }
        for (final Zone z : zones) {
            if (z.contains(village)) {
                return Decision.SPAWN;
            }
        }
        for (final Point c : countryChunks) {
            if (c.distance(village) < r.minCountryDistance()) {
                return Decision.COUNTRY;
            }
        }
        return roll < r.chance() ? Decision.CREATE : Decision.CHANCE;
    }

    /** Points of the scan grid within {@code radius} of {@code centre}, nearest first (stable order). */
    public static List<Point> scanGrid(final Point centre, final int radius, final int step) {
        final int s = Math.max(16, step);
        final List<Point> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += s) {
            for (int dz = -radius; dz <= radius; dz += s) {
                final Point p = new Point(centre.x() + dx, centre.z() + dz);
                if (p.distance(centre) <= radius) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparingDouble((Point p) -> p.distance(centre)).thenComparingInt(Point::x).thenComparingInt(Point::z));
        return out;
    }

    /** Key of a village for "seen": its chunk. */
    public static long key(final int blockX, final int blockZ) {
        return ChunkPlanner.key(blockX >> 4, blockZ >> 4);
    }

    /** First name of the pool not taken yet (case-insensitive); with every name taken the first one + " II", " III"... */
    public static String pickName(final List<String> pool, final Set<String> taken, final Random rnd) {
        final Set<String> t = new HashSet<>();
        for (final String s : taken) {
            t.add(s.toLowerCase(Locale.ROOT));
        }
        final List<String> free = new ArrayList<>();
        for (final String n : pool) {
            if (!t.contains(n.toLowerCase(Locale.ROOT))) {
                free.add(n);
            }
        }
        if (!free.isEmpty()) {
            return free.get(rnd.nextInt(free.size()));
        }
        final String base = pool.isEmpty() ? "Ziemia_Niczyja" : pool.get(rnd.nextInt(pool.size()));
        for (final String suffix : List.of("_II", "_III", "_IV", "_V", "_VI", "_VII")) {
            if (!t.contains((base + suffix).toLowerCase(Locale.ROOT))) {
                return base + suffix;
            }
        }
        return base + "_" + (1000 + rnd.nextInt(9000));
    }

    /** Ruler title (genitive) for the announcement from the first word of the country name. */
    public static String rulerTitle(final String countryName) {
        final String first = countryName.split("[_ ]", 2)[0].toLowerCase(Locale.ROOT);
        return switch (first) {
            case "królestwo", "krolestwo" -> "króla";
            case "księstwo", "ksiestwo" -> "księcia";
            case "hrabstwo" -> "hrabiego";
            case "margrabstwo" -> "margrabiego";
            case "ziemia", "kasztelania" -> "kasztelana";
            case "wojewodztwo", "województwo" -> "wojewody";
            case "republika" -> "dożego";
            default -> "władcy";
        };
    }

    /** "na północy" … "na południowym wschodzie" of {@code p} as seen from {@code centre} (north = −Z, east = +X). */
    public static String direction(final Point p, final Point centre) {
        final double dx = p.x() - centre.x();
        final double dz = p.z() - centre.z();
        if (Math.hypot(dx, dz) < 300) {
            return "w samym sercu krainy";
        }
        final double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        final int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return switch (sector) {
            case 0 -> "na północy";
            case 1 -> "na północnym wschodzie";
            case 2 -> "na wschodzie";
            case 3 -> "na południowym wschodzie";
            case 4 -> "na południu";
            case 5 -> "na południowym zachodzie";
            case 6 -> "na zachodzie";
            default -> "na północnym zachodzie";
        };
    }

    /** Random personality in sensible bounds: traits 20–80, honor 30–90 (index 4), max-chunki 30–60, max-zolnierze 15–30. */
    public static int[] randomPersonality(final Random rnd) {
        return new int[] {20 + rnd.nextInt(61), 20 + rnd.nextInt(61), 20 + rnd.nextInt(61), 20 + rnd.nextInt(61), 30 + rnd.nextInt(61),
            30 + rnd.nextInt(31), 15 + rnd.nextInt(16)};
    }

    /** A random colour not too close to the ones in use (RGB distance ≥ 60 when possible). */
    public static int randomColor(final Collection<Integer> used, final Random rnd) {
        int best = 0;
        double bestDist = -1;
        for (int i = 0; i < 40; i++) {
            final float h = rnd.nextFloat();
            final int c = java.awt.Color.HSBtoRGB(h, 0.55f + rnd.nextFloat() * 0.35f, 0.55f + rnd.nextFloat() * 0.35f) & 0xFFFFFF;
            double min = Double.MAX_VALUE;
            for (final int u : used) {
                min = Math.min(min, Math.sqrt(Math.pow(((c >> 16) & 255) - ((u >> 16) & 255), 2)
                    + Math.pow(((c >> 8) & 255) - ((u >> 8) & 255), 2) + Math.pow((c & 255) - (u & 255), 2)));
            }
            if (min >= 60) {
                return c;
            }
            if (min > bestDist) {
                bestDist = min;
                best = c;
            }
        }
        return best;
    }

    /**
     * Village chunks in a claim order that keeps the country connected: breadth-first from the capital chunk through
     * edge-adjacent village chunks; chunks not connected to the capital are left out.
     */
    public static List<Long> claimOrder(final long capital, final Set<Long> villageChunks) {
        final Set<Long> all = new LinkedHashSet<>(villageChunks);
        all.add(capital);
        final List<Long> out = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        final Deque<Long> todo = new ArrayDeque<>();
        todo.add(capital);
        seen.add(capital);
        while (!todo.isEmpty()) {
            final long k = todo.poll();
            if (k != capital) {
                out.add(k);
            }
            for (final long n : ChunkPlanner.neighbors4(k)) {
                if (all.contains(n) && seen.add(n)) {
                    todo.add(n);
                }
            }
        }
        return out;
    }
}
