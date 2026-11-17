// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongPredicate;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Plans of the terrain restore ({@code /kraj admin przywroc}): which chunks are regenerated from the world seed,
 * which are skipped (protected areas such as the spawn, claimed land when restoring the wilderness), the order and
 * the batches the running job regenerates (one WorldEdit regeneration per batch), the time estimate and the
 * progress milestones. Pure functions over chunk keys (no Bukkit), so they are unit tested.
 */
public final class RegenPlanner {

    /** Most chunks one restore may touch (a whole large country fits). */
    public static final int MAX_CHUNKS = 20_000;
    /** Most chunks of one batch (one temporary world + one block copy on the main thread). */
    public static final int MAX_BATCH = 16;

    /** Why a chunk of the area is not regenerated. */
    public enum Skip {
        /** Inside a protected area (e.g. the server spawn) — never regenerated. */
        PROTECTED,
        /** Restoring the wilderness: the chunk belongs to a country. */
        CLAIMED,
        /** Restoring a part of a country: the chunk does not belong to it. */
        NOT_OWN
    }

    /** One batch: chunks of one world regenerated together. */
    public record Batch(String world, List<Long> chunks) {

        public Batch {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * @param chunks  chunks to regenerate per world (insertion order = world order)
     * @param skipped number of skipped chunks per reason (only reasons that occur)
     */
    public record Plan(Map<String, List<Long>> chunks, Map<Skip, Integer> skipped) {

        public Plan {
            final Map<String, List<Long>> copy = new LinkedHashMap<>();
            chunks.forEach((w, keys) -> {
                if (!keys.isEmpty()) {
                    copy.put(w, List.copyOf(keys));
                }
            });
            chunks = Collections.unmodifiableMap(copy);
            skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
        }

        public int count() {
            return this.chunks.values().stream().mapToInt(List::size).sum();
        }

        public int skippedCount() {
            return this.skipped.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int skipped(final Skip reason) {
            return this.skipped.getOrDefault(reason, 0);
        }
    }

    /** Ownership and protection of one world's chunks. */
    public interface Land {
        /** {@code true} when the chunk belongs to any country. */
        boolean claimed(long key);

        boolean isProtected(long key);
    }

    private RegenPlanner() {
    }

    // ------------------------------------------------------------------ plans

    /** A whole country: every claimed chunk of every world, protected ones skipped. */
    public static Plan country(final Map<String, ? extends Collection<Long>> claims,
                               final Function<String, LongPredicate> isProtected) {
        final Map<String, List<Long>> chunks = new LinkedHashMap<>();
        final Map<Skip, Integer> skipped = new LinkedHashMap<>();
        for (final String world : new TreeSet<>(claims.keySet())) {
            final LongPredicate prot = isProtected.apply(world);
            final List<Long> keep = new ArrayList<>();
            for (final long key : sorted(claims.get(world))) {
                if (prot.test(key)) {
                    skipped.merge(Skip.PROTECTED, 1, Integer::sum);
                } else {
                    keep.add(key);
                }
            }
            chunks.put(world, keep);
        }
        return new Plan(chunks, skipped);
    }

    /** The part of a country inside {@code area} (one world): chunks of other owners and protected ones skipped. */
    public static Plan countryPart(final String world, final Collection<Long> area, final Set<Long> territory,
                                   final LongPredicate isProtected) {
        final List<Long> keep = new ArrayList<>();
        final Map<Skip, Integer> skipped = new LinkedHashMap<>();
        for (final long key : new LinkedHashSet<>(area)) {
            if (!territory.contains(key)) {
                skipped.merge(Skip.NOT_OWN, 1, Integer::sum);
            } else if (isProtected.test(key)) {
                skipped.merge(Skip.PROTECTED, 1, Integer::sum);
            } else {
                keep.add(key);
            }
        }
        return new Plan(Map.of(world, keep), skipped);
    }

    /** The wilderness inside {@code area} (one world): claimed and protected chunks skipped. */
    public static Plan wilderness(final String world, final Collection<Long> area, final Land land) {
        final List<Long> keep = new ArrayList<>();
        final Map<Skip, Integer> skipped = new LinkedHashMap<>();
        for (final long key : new LinkedHashSet<>(area)) {
            if (land.claimed(key)) {
                skipped.merge(Skip.CLAIMED, 1, Integer::sum);
            } else if (land.isProtected(key)) {
                skipped.merge(Skip.PROTECTED, 1, Integer::sum);
            } else {
                keep.add(key);
            }
        }
        return new Plan(Map.of(world, keep), skipped);
    }

    private static List<Long> sorted(final Collection<Long> keys) {
        final List<Long> list = new ArrayList<>(new LinkedHashSet<>(keys));
        list.sort(Comparator.<Long>comparingInt(ChunkKeys::x).thenComparingInt(ChunkKeys::z));
        return list;
    }

    // ------------------------------------------------------------------ batches

    /**
     * Splits the chunks of every world into batches of at most {@code perBatch} chunks that lie close together:
     * chunks are grouped in square tiles of {@code ceil(sqrt(perBatch))} chunks, tiles are visited row by row
     * (serpentine, so consecutive batches are neighbours), and each tile is cut into batches. Neighbouring chunks in
     * one batch share the temporary world WorldEdit generates for them, which is what makes batching cheaper.
     */
    public static List<Batch> batches(final Map<String, List<Long>> chunks, final int perBatch) {
        final int size = Math.max(1, Math.min(MAX_BATCH, perBatch));
        final int tile = (int) Math.ceil(Math.sqrt(size));
        final List<Batch> out = new ArrayList<>();
        for (final Map.Entry<String, List<Long>> e : chunks.entrySet()) {
            // row (tile z) → column (tile x) → chunks
            final TreeMap<Integer, TreeMap<Integer, List<Long>>> rows = new TreeMap<>();
            for (final long key : new LinkedHashSet<>(e.getValue())) {
                rows.computeIfAbsent(Math.floorDiv(ChunkKeys.z(key), tile), r -> new TreeMap<>())
                    .computeIfAbsent(Math.floorDiv(ChunkKeys.x(key), tile), c -> new ArrayList<>())
                    .add(key);
            }
            boolean forward = true;
            for (final TreeMap<Integer, List<Long>> row : rows.values()) {
                final Collection<List<Long>> cells = forward ? row.values() : row.descendingMap().values();
                for (final List<Long> cell : cells) {
                    cell.sort(Comparator.<Long>comparingInt(ChunkKeys::z).thenComparingInt(ChunkKeys::x));
                    for (int i = 0; i < cell.size(); i += size) {
                        out.add(new Batch(e.getKey(), cell.subList(i, Math.min(cell.size(), i + size))));
                    }
                }
                forward = !forward;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ time and progress

    /**
     * Estimated duration in milliseconds: regeneration time of every chunk plus the pauses between batches.
     *
     * @param msPerChunk measured (or configured) main-thread time of one chunk
     * @param pauseTicks ticks between two batches
     */
    public static long estimateMillis(final int chunks, final int perBatch, final double msPerChunk, final int pauseTicks) {
        if (chunks <= 0) {
            return 0;
        }
        final int size = Math.max(1, Math.min(MAX_BATCH, perBatch));
        final long batches = (chunks + size - 1) / size;
        return Math.round(chunks * Math.max(0.0, msPerChunk)) + (batches - 1) * Math.max(0, pauseTicks) * 50L;
    }

    /**
     * The progress milestone to announce after {@code done} of {@code total} chunks, or {@code -1}: the highest
     * multiple of {@code step} percent reached that is above {@code lastAnnounced} (and below 100 — the end has
     * its own message).
     */
    public static int milestone(final int done, final int total, final int step, final int lastAnnounced) {
        if (total <= 0 || step <= 0 || step >= 100) {
            return -1;
        }
        final int percent = (int) ((long) done * 100 / total);
        final int reached = percent / step * step;
        return reached > lastAnnounced && reached > 0 && reached < 100 ? reached : -1;
    }

    /** Running average of the time per chunk (simple exponential smoothing; the first sample is taken as is). */
    public static double smooth(final double previous, final double sample) {
        return previous <= 0 ? sample : previous * 0.7 + sample * 0.3;
    }
}
