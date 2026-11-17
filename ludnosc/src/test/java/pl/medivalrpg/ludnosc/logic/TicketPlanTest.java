// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.ChunkRef;

/** The sites' chunk tickets never load chunks synchronously: loaded ones at once, the rest asynchronously, bounded. */
class TicketPlanTest {

    private static ChunkRef c(final int x) {
        return new ChunkRef("world", x, 0);
    }

    @Test
    void loadedChunksGetTicketsAndUnloadedAreLoadedAFewAtATime() {
        final Set<ChunkRef> wanted = new LinkedHashSet<>();
        for (int i = 0; i < 64; i++) {
            wanted.add(c(i));
        }
        final TicketPlan.Step s = TicketPlan.step(wanted, Set.of(), Set.of(), ch -> ch.cx() < 10, 2, 6);
        assertEquals(10, s.addNow().size());
        assertEquals(List.of(c(10), c(11)), s.load()); // only two synchronous-free loads per pass
        assertTrue(s.release().isEmpty());
    }

    @Test
    void inFlightLoadsAreBoundedAndReleasedTicketsGoFirst() {
        final Set<ChunkRef> wanted = new LinkedHashSet<>(List.of(c(1), c(2), c(3), c(4)));
        final Set<ChunkRef> held = new HashSet<>(List.of(c(1), c(99)));
        final Set<ChunkRef> loading = new HashSet<>(List.of(c(2), c(50), c(51), c(52), c(53)));
        final TicketPlan.Step s = TicketPlan.step(wanted, held, loading, ch -> false, 2, 6);
        assertEquals(List.of(c(99)), s.release());
        assertTrue(s.addNow().isEmpty());
        assertEquals(List.of(c(3)), s.load()); // 5 in flight + 1 = the limit of 6
    }

    /** A start with 7 sites × 9 chunks: every pass is bounded, all tickets arrive after a few passes. */
    @Test
    void startUnderLoadConverges() {
        final Set<ChunkRef> wanted = new LinkedHashSet<>();
        for (int i = 0; i < 63; i++) {
            wanted.add(c(i));
        }
        final Set<ChunkRef> held = new HashSet<>();
        final Set<ChunkRef> loaded = new HashSet<>();
        int passes = 0;
        while (held.size() < wanted.size() && passes < 100) {
            passes++;
            final TicketPlan.Step s = TicketPlan.step(wanted, held, Set.of(), loaded::contains, 2, 6);
            assertTrue(s.load().size() <= 2);
            held.addAll(s.addNow());
            loaded.addAll(s.load()); // the asynchronous load finishes before the next pass
        }
        assertEquals(63, held.size());
        assertTrue(passes <= 33, "passes " + passes);
    }
}
