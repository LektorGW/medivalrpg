// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Plans of the admin territory tools ({@code /kraj admin zajmij|porzuc|przylacz} with an area): which chunks of a
 * square, a WorldEdit selection or a ring around the border change owner, and which are skipped and why. Pure
 * functions over chunk keys of one world (no Bukkit), so they are unit tested.
 */
public final class AreaPlanner {

    /** Largest {@code promien} (square of 33 × 33 chunks). */
    public static final int MAX_RADIUS = 16;
    /** Largest number of rings of {@code przylacz}. */
    public static final int MAX_RINGS = 16;
    /** Most chunks a WorldEdit selection may cover. */
    public static final int MAX_SELECTION_CHUNKS = 4096;
    /** Operations with more chunks than this show a preview and need {@code potwierdz}. */
    public static final int PREVIEW_THRESHOLD = 16;
    /** Chunks changed per server tick by a running operation. */
    public static final int CHUNKS_PER_TICK = 32;

    /** Why a chunk of the area is left alone. */
    public enum Skip {
        /** Claim: already belongs to the country. */
        OWN,
        /** Claim: belongs to another country. */
        FOREIGN,
        /** Claim: inside a protected area (e.g. the server spawn). */
        PROTECTED,
        /** Unclaim: the capital is never released. */
        CAPITAL,
        /** Unclaim: does not belong to the country. */
        NOT_OWN
    }

    /** Ownership of the chunks of one world. */
    public interface Land {
        /** Country owning the chunk, {@code null} = wilderness. */
        UUID ownerAt(long key);

        boolean isProtected(long key);
    }

    /**
     * @param chunks  chunks that change owner, in execution order (nearest to the centre first)
     * @param skipped every other chunk of the area with the reason
     * @param foreign chunks skipped as {@link Skip#FOREIGN}, per owning country
     */
    public record Plan(List<Long> chunks, Map<Long, Skip> skipped, Map<UUID, Integer> foreign) {

        public Plan {
            chunks = List.copyOf(chunks);
            skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
            foreign = Collections.unmodifiableMap(new LinkedHashMap<>(foreign));
        }

        public int count() {
            return this.chunks.size();
        }

        /** Number of skipped chunks per reason (only reasons that occur, in enum order). */
        public Map<Skip, Integer> skipCounts() {
            final Map<Skip, Integer> counts = new EnumMap<>(Skip.class);
            for (final Skip s : this.skipped.values()) {
                counts.merge(s, 1, Integer::sum);
            }
            return counts;
        }

        public boolean needsPreview() {
            return this.chunks.size() > PREVIEW_THRESHOLD;
        }
    }

    private AreaPlanner() {
    }

    // ------------------------------------------------------------------ areas

    /**
     * Every chunk of the square of {@code radius} around ({@code cx}, {@code cz}) — {@code (2r+1)²} chunks, the
     * centre first, then by growing distance (ties in a fixed order), so a batched claim spreads from the admin.
     */
    public static List<Long> square(final int cx, final int cz, final int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius < 0");
        }
        final List<int[]> cells = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                cells.add(new int[] {dx, dz});
            }
        }
        cells.sort(Comparator.<int[]>comparingInt(c -> c[0] * c[0] + c[1] * c[1])
            .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        final List<Long> keys = new ArrayList<>(cells.size());
        for (final int[] c : cells) {
            keys.add(ChunkKeys.key(cx + c[0], cz + c[1]));
        }
        return keys;
    }

    /** Orders any area (e.g. a WorldEdit selection) like {@link #square}: nearest to ({@code cx}, {@code cz}) first. */
    public static List<Long> sortedFrom(final Collection<Long> area, final int cx, final int cz) {
        final List<Long> keys = new ArrayList<>(new LinkedHashSet<>(area));
        keys.sort(Comparator.<Long>comparingLong(k -> distSq(k, cx, cz))
            .thenComparingInt(ChunkKeys::x).thenComparingInt(ChunkKeys::z));
        return keys;
    }

    private static long distSq(final long key, final int cx, final int cz) {
        final long dx = ChunkKeys.x(key) - (long) cx;
        final long dz = ChunkKeys.z(key) - (long) cz;
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ plans

    /** Claim for {@code country}: free chunks of the area; own, foreign and protected chunks are skipped. */
    public static Plan claim(final UUID country, final Collection<Long> area, final Land land) {
        final List<Long> chunks = new ArrayList<>();
        final Map<Long, Skip> skipped = new LinkedHashMap<>();
        final Map<UUID, Integer> foreign = new LinkedHashMap<>();
        for (final long key : new LinkedHashSet<>(area)) {
            final Skip skip = claimSkip(country, key, land, foreign);
            if (skip == null) {
                chunks.add(key);
            } else {
                skipped.put(key, skip);
            }
        }
        return new Plan(chunks, skipped, foreign);
    }

    private static Skip claimSkip(final UUID country, final long key, final Land land, final Map<UUID, Integer> foreign) {
        final UUID owner = land.ownerAt(key);
        if (owner != null) {
            if (owner.equals(country)) {
                return Skip.OWN;
            }
            foreign.merge(owner, 1, Integer::sum);
            return Skip.FOREIGN;
        }
        return land.isProtected(key) ? Skip.PROTECTED : null;
    }

    /** Unclaim from {@code country}: its chunks of the area except the capital ({@code capitalKey}, may be null). */
    public static Plan unclaim(final UUID country, final Collection<Long> area, final Long capitalKey, final Land land) {
        final List<Long> chunks = new ArrayList<>();
        final Map<Long, Skip> skipped = new LinkedHashMap<>();
        for (final long key : new LinkedHashSet<>(area)) {
            if (!country.equals(land.ownerAt(key))) {
                skipped.put(key, Skip.NOT_OWN);
            } else if (capitalKey != null && key == capitalKey) {
                skipped.put(key, Skip.CAPITAL);
            } else {
                chunks.add(key);
            }
        }
        return new Plan(chunks, skipped, Map.of());
    }

    /**
     * {@code przylacz}: {@code rings} rings of free chunks around the current border of {@code territory}. Each ring
     * takes the free chunks touching the territory (also diagonally, so the corners are filled) that connect to it
     * edge-wise; foreign and protected chunks are skipped and do not grow further rings.
     */
    public static Plan rings(final UUID country, final Set<Long> territory, final int rings, final Land land) {
        final Set<Long> current = new HashSet<>(territory);
        final List<Long> chunks = new ArrayList<>();
        final Map<Long, Skip> skipped = new LinkedHashMap<>();
        final Map<UUID, Integer> foreign = new LinkedHashMap<>();
        for (int ring = 0; ring < rings; ring++) {
            final Set<Long> free = new LinkedHashSet<>();
            for (final long key : sortedKeys(current)) {
                for (final long n : ring8(key)) {
                    if (current.contains(n) || free.contains(n) || skipped.containsKey(n)) {
                        continue;
                    }
                    final Skip skip = claimSkip(country, n, land, foreign);
                    if (skip == null) {
                        free.add(n);
                    } else if (skip != Skip.OWN) {
                        skipped.put(n, skip);
                    }
                }
            }
            // Keep only free chunks reachable edge-wise from the territory (through this ring's free chunks).
            final List<Long> added = new ArrayList<>();
            final Deque<Long> queue = new ArrayDeque<>();
            for (final long key : free) {
                if (ChunkGraph.isAdjacent(current, key)) {
                    queue.add(key);
                }
            }
            final Set<Long> accepted = new HashSet<>(queue);
            while (!queue.isEmpty()) {
                final long key = queue.poll();
                added.add(key);
                for (final long n : ChunkGraph.neighbours(key)) {
                    if (free.contains(n) && accepted.add(n)) {
                        queue.add(n);
                    }
                }
            }
            if (added.isEmpty()) {
                break;
            }
            current.addAll(added);
            chunks.addAll(added);
        }
        return new Plan(chunks, skipped, foreign);
    }

    private static List<Long> sortedKeys(final Set<Long> keys) {
        final List<Long> list = new ArrayList<>(keys);
        list.sort(Comparator.<Long>comparingInt(ChunkKeys::x).thenComparingInt(ChunkKeys::z));
        return list;
    }

    private static long[] ring8(final long key) {
        final int x = ChunkKeys.x(key);
        final int z = ChunkKeys.z(key);
        final long[] out = new long[8];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    out[i++] = ChunkKeys.key(x + dx, z + dz);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ connectivity

    /**
     * Number of edge-connected pieces the territory splits into once {@code removed} is taken away, or 0 when the
     * removal does not add a split (removing a whole exclave is fine).
     */
    public static int splitsInto(final Set<Long> territory, final Collection<Long> removed) {
        final Set<Long> after = new HashSet<>(territory);
        after.removeAll(removed);
        final int before = ChunkGraph.componentCount(territory);
        final int now = ChunkGraph.componentCount(after);
        return now > before ? now : 0;
    }

    // ------------------------------------------------------------------ preview outline

    /** One chunk edge in block coordinates (axis-aligned, 16 blocks long). */
    public record Edge(int x1, int z1, int x2, int z2) {
    }

    /** Outer edges of an area: every chunk side whose neighbour is not in the area. */
    public static List<Edge> outline(final Set<Long> area) {
        final List<Edge> edges = new ArrayList<>();
        for (final long key : sortedKeys(area)) {
            final int x = ChunkKeys.x(key);
            final int z = ChunkKeys.z(key);
            final int bx = x << 4;
            final int bz = z << 4;
            if (!area.contains(ChunkKeys.key(x, z - 1))) {
                edges.add(new Edge(bx, bz, bx + 16, bz));
            }
            if (!area.contains(ChunkKeys.key(x, z + 1))) {
                edges.add(new Edge(bx, bz + 16, bx + 16, bz + 16));
            }
            if (!area.contains(ChunkKeys.key(x - 1, z))) {
                edges.add(new Edge(bx, bz, bx, bz + 16));
            }
            if (!area.contains(ChunkKeys.key(x + 1, z))) {
                edges.add(new Edge(bx + 16, bz, bx + 16, bz + 16));
            }
        }
        return edges;
    }
}
