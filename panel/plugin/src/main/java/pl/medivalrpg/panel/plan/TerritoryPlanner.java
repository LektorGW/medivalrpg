// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * Decides which chunks of an {@code admin.npc.territory} request can be claimed and released. Kraje's
 * {@code ClaimCause.ADMIN} ignores adjacency, protected areas and connectivity, so the panel checks them here:
 * <ul>
 *   <li>added chunks must be free (not another country's), outside protected areas and end up edge-connected to an
 *       existing component of the country;</li>
 *   <li>the capital is never released, and a release that would split any existing island is skipped.</li>
 * </ul>
 * Pure, no Bukkit; the caller supplies ownership and protection lookups.
 */
public final class TerritoryPlanner {

    public static final int MAX_CHANGES = 512;

    /** A chunk that is not changed, with a code and a Polish reason. */
    public record Skip(int cx, int cz, String code, String reason) {
    }

    /** Chunks to claim (in order), to release, skipped ones, and the claim cost. */
    public record Decision(List<Long> claim, List<Long> release, List<Skip> skipped, long cost) {
    }

    private TerritoryPlanner() {
    }

    /**
     * Claim cost of {@code n} chunks starting at {@code count} owned chunks: Kraje's formula
     * {@code base + perChunk × current chunk count}, applied chunk by chunk.
     */
    public static long cost(final int count, final int n, final long base, final long perChunk) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += base + perChunk * (long) (count + i);
        }
        return sum;
    }

    /**
     * @param owned       chunks of the country now
     * @param capital     capital chunk key
     * @param add         requested additions (duplicates ignored)
     * @param remove      requested releases
     * @param foreignName name of the other country owning a chunk, or null when the chunk is free or ours
     * @param protectedBy label of the protected area covering a chunk, or null
     */
    public static Decision decide(final Set<Long> owned, final long capital, final List<Long> add, final List<Long> remove,
                                  final LongFunction<String> foreignName, final LongFunction<String> protectedBy,
                                  final long costBase, final long costPerChunk) {
        final List<Skip> skipped = new ArrayList<>();
        final Set<Long> removeSet = new LinkedHashSet<>(remove);
        final Set<Long> candidates = new LinkedHashSet<>();
        for (final long k : new LinkedHashSet<>(add)) {
            final int cx = ChunkGeometry.cx(k);
            final int cz = ChunkGeometry.cz(k);
            if (removeSet.contains(k)) {
                skipped.add(new Skip(cx, cz, "CONFLICT", "chunk jest jednocześnie dodawany i usuwany"));
                continue;
            }
            if (owned.contains(k)) {
                skipped.add(new Skip(cx, cz, "ALREADY_OWN", "chunk już należy do kraju"));
                continue;
            }
            final String foreign = foreignName.apply(k);
            if (foreign != null) {
                skipped.add(new Skip(cx, cz, "FOREIGN", "należy do kraju " + foreign));
                continue;
            }
            final String area = protectedBy.apply(k);
            if (area != null) {
                skipped.add(new Skip(cx, cz, "PROTECTED", "obszar chroniony: " + area));
                continue;
            }
            candidates.add(k);
        }
        // Additions may grow any existing island, but may not create an unanchored component.
        final Set<Long> grown = new HashSet<>(owned);
        grown.addAll(candidates);
        final List<Set<Long>> original = components(owned);
        final Set<Long> reach = new HashSet<>();
        for (final Set<Long> component : original) {
            final long seed = component.iterator().next();
            if (!reach.contains(seed)) reach.addAll(ChunkGeometry.reachable(grown, seed));
        }
        final List<Long> claim = new ArrayList<>();
        for (final long k : candidates) {
            if (reach.contains(k)) {
                claim.add(k);
            } else {
                skipped.add(new Skip(ChunkGeometry.cx(k), ChunkGeometry.cz(k), "NOT_ADJACENT", "nie przylega do terytorium kraju"));
            }
        }
        final Set<Long> after = new HashSet<>(owned);
        after.addAll(claim);
        final List<Long> release = new ArrayList<>();
        for (final long k : removeSet) {
            final int cx = ChunkGeometry.cx(k);
            final int cz = ChunkGeometry.cz(k);
            if (!owned.contains(k)) {
                skipped.add(new Skip(cx, cz, "NOT_OWN", "chunk nie należy do kraju"));
                continue;
            }
            if (k == capital) {
                skipped.add(new Skip(cx, cz, "CAPITAL", "nie można usunąć stolicy"));
                continue;
            }
            after.remove(k);
            if (preservesComponents(after, original)) {
                release.add(k);
            } else {
                after.add(k);
                skipped.add(new Skip(cx, cz, "DISCONNECTS", "usunięcie rozdzieliłoby terytorium kraju"));
            }
        }
        return new Decision(claim, release, skipped, cost(owned.size(), claim.size(), costBase, costPerChunk));
    }

    private static List<Set<Long>> components(final Set<Long> owned) {
        final List<Set<Long>> result = new ArrayList<>();
        final Set<Long> remaining = new HashSet<>(owned);
        while (!remaining.isEmpty()) {
            final Set<Long> component = ChunkGeometry.reachable(owned, remaining.iterator().next());
            remaining.removeAll(component);
            result.add(component);
        }
        return result;
    }

    /** Each original island's surviving chunks stay together; an unrelated disappearing island cannot mask a split. */
    private static boolean preservesComponents(final Set<Long> after, final List<Set<Long>> original) {
        for (final Set<Long> component : original) {
            final Set<Long> survivors = new HashSet<>(component);
            survivors.retainAll(after);
            if (survivors.isEmpty()) continue;
            if (!ChunkGeometry.reachable(after, survivors.iterator().next()).containsAll(survivors)) return false;
        }
        return true;
    }
}
