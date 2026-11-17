// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.npc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcSelectionTest {
    private static final double R = 32.0;

    private static NpcSelection.Candidate c(UUID id, double distance) {
        return new NpcSelection.Candidate(id, distance * distance, true);
    }

    @Test
    void onlyNpcsWithAViewerInRangeAreAnimated() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID nobody = UUID.randomUUID();
        NpcSelection.Plan p = NpcSelection.plan(List.of(c(near, 10), c(far, 40),
                new NpcSelection.Candidate(nobody, Double.POSITIVE_INFINITY, true)), Set.of(), R, 4, 40);
        assertEquals(List.of(near), p.start());
        assertTrue(p.stop().isEmpty());
    }

    @Test
    void theGlobalLimitKeepsTheNearest() {
        List<NpcSelection.Candidate> all = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            all.add(c(id, 30 - i * 0.25)); // later ones are nearer
        }
        NpcSelection.Plan p = NpcSelection.plan(all, Set.of(), R, 4, 10);
        assertEquals(10, p.start().size());
        assertEquals(10, p.wanted().size());
        assertEquals(ids.get(99), p.start().getFirst(), "the nearest starts first");
        for (int i = 0; i < 90; i++) {
            assertFalse(p.wanted().contains(ids.get(i)));
        }
    }

    @Test
    void hysteresisAvoidsFlickerAtTheEdge() {
        UUID id = UUID.randomUUID();
        // 34 blocks: a new one does not start, an animated one keeps playing (range 32 + 4)
        assertTrue(NpcSelection.plan(List.of(c(id, 34)), Set.of(), R, 4, 40).start().isEmpty());
        NpcSelection.Plan keep = NpcSelection.plan(List.of(c(id, 34)), Set.of(id), R, 4, 40);
        assertTrue(keep.stop().isEmpty());
        assertTrue(keep.wanted().contains(id));
        NpcSelection.Plan gone = NpcSelection.plan(List.of(c(id, 37)), Set.of(id), R, 4, 40);
        assertEquals(List.of(id), gone.stop());
    }

    @Test
    void npcsWithoutSkinOrWithoutWorkAreStopped() {
        UUID id = UUID.randomUUID();
        NpcSelection.Plan p = NpcSelection.plan(List.of(new NpcSelection.Candidate(id, 1, false)), Set.of(id), R, 4, 40);
        assertEquals(List.of(id), p.stop());
        UUID removed = UUID.randomUUID();
        assertEquals(List.of(removed), NpcSelection.plan(List.of(), Set.of(removed), R, 4, 40).stop());
    }

    @Test
    void aFullServerDoesNotSwapAnimatedNpcsOnTies() {
        UUID active = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        NpcSelection.Plan p = NpcSelection.plan(List.of(c(other, 5), c(active, 5)), Set.of(active), R, 4, 1);
        assertTrue(p.start().isEmpty());
        assertTrue(p.stop().isEmpty());
        assertEquals(Set.of(active), p.wanted());
    }

    @Test
    void zeroLimitDisablesEverything() {
        UUID id = UUID.randomUUID();
        NpcSelection.Plan p = NpcSelection.plan(List.of(c(id, 1)), Set.of(id), R, 4, 0);
        assertEquals(List.of(id), p.stop());
        assertTrue(p.start().isEmpty());
    }
}
