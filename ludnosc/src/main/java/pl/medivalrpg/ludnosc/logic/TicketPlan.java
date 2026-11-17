// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import pl.medivalrpg.ludnosc.model.ChunkRef;

/**
 * One pass of the construction sites' chunk tickets without stalling the main thread (pure, unit tested).
 * A plugin chunk ticket on an unloaded chunk loads (or even generates) it synchronously, so at a start under load
 * dozens of tickets at once froze the server for 10–15 s. Now: tickets that are no longer wanted are released;
 * already loaded chunks get their ticket at once (cheap); unloaded ones are only requested for an asynchronous load,
 * at most {@code maxLoads} per pass and {@code maxInFlight} in flight — the ticket follows when the chunk arrives.
 */
public final class TicketPlan {

    /** What to do now: tickets to remove, tickets to add (chunk loaded), chunks to load asynchronously. */
    public record Step(List<ChunkRef> release, List<ChunkRef> addNow, List<ChunkRef> load) {
    }

    private TicketPlan() {
    }

    public static Step step(final Set<ChunkRef> wanted, final Set<ChunkRef> held, final Set<ChunkRef> loading,
                            final Predicate<ChunkRef> loaded, final int maxLoads, final int maxInFlight) {
        final List<ChunkRef> release = new ArrayList<>();
        for (final ChunkRef c : held) {
            if (!wanted.contains(c)) {
                release.add(c);
            }
        }
        final List<ChunkRef> addNow = new ArrayList<>();
        final List<ChunkRef> load = new ArrayList<>();
        int inFlight = loading.size();
        for (final ChunkRef c : new LinkedHashSet<>(wanted)) {
            if (held.contains(c) || loading.contains(c)) {
                continue;
            }
            if (loaded.test(c)) {
                addNow.add(c);
            } else if (load.size() < maxLoads && inFlight < maxInFlight) {
                load.add(c);
                inFlight++;
            }
        }
        return new Step(release, addNow, load);
    }
}
