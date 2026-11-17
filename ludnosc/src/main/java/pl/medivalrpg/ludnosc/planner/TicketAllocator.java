// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.ludnosc.model.ChunkRef;

/**
 * Which construction sites may keep their chunks loaded (plugin chunk tickets) within a server-wide limit (pure,
 * unit tested). Sites are taken in priority order (oldest order first) and get all their chunks or nothing; chunks
 * shared by two sites are counted once. Sites left out progress virtually and catch up when their chunks load.
 */
public final class TicketAllocator {

    /** A site that wants its chunks loaded. */
    public record Demand(UUID site, Set<ChunkRef> chunks) {
    }

    /** Granted sites and the chunks they hold. */
    public record Allocation(Set<UUID> sites, Set<ChunkRef> chunks) {
    }

    private TicketAllocator() {
    }

    public static Allocation allocate(final List<Demand> demands, final int maxChunks) {
        final Set<UUID> granted = new LinkedHashSet<>();
        final Set<ChunkRef> held = new LinkedHashSet<>();
        for (final Demand d : demands) {
            final Set<ChunkRef> extra = new HashSet<>(d.chunks());
            extra.removeAll(held);
            if (held.size() + extra.size() > maxChunks) {
                continue; // all or nothing; smaller later sites may still fit
            }
            held.addAll(extra);
            granted.add(d.site());
        }
        return new Allocation(granted, held);
    }

    /**
     * Virtual progress: block credit after {@code periods} work periods of a site that is not loaded.
     *
     * @param perPeriod blocks per period (builders × blocks/s × period seconds)
     * @param remaining blocks left to place (the credit never exceeds it by more than one period)
     */
    public static double credit(final double credit, final int periods, final double perPeriod, final int remaining) {
        return Math.min(credit + Math.max(0, periods) * perPeriod, Math.max(0, remaining) + perPeriod);
    }
}
