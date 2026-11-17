// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FreeForAllTest {

    @Test
    void lastStandingWinsAndPlacesFollowEliminationOrder() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b", "c", "d"));
        assertTrue(f.eliminate("b"));
        assertFalse(f.eliminate("b"));
        f.eliminate("d");
        assertFalse(f.isFinished());
        f.eliminate("a");
        assertTrue(f.isFinished());
        assertEquals(Optional.of("c"), f.champion());
        assertEquals(List.of(List.of("c"), List.of("a"), List.of("d")), f.placements());
    }

    @Test
    void timeoutUsesRanking() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b", "c", "d"));
        f.eliminate("a");
        f.resolveTimeout(List.of("c", "d", "b"));
        assertEquals(Optional.of("c"), f.champion());
        assertEquals(List.of(List.of("c"), List.of("d"), List.of("b")), f.placements());
    }

    @Test
    void timeoutWithMissingRankingEntries() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b", "c"));
        f.resolveTimeout(List.of("b", "ghost"));
        assertEquals(Optional.of("b"), f.champion());
        assertTrue(f.isFinished());
    }

    @Test
    void nobodyLeftNoPrizes() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b"));
        f.eliminate("a");
        f.eliminate("b");
        assertTrue(f.isFinished());
        assertTrue(f.champion().isEmpty());
        assertEquals(List.of(List.of(), List.of(), List.of()), f.placements());
    }

    @Test
    void twoPlayersNoThirdPlace() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b"));
        f.eliminate("a");
        assertEquals(List.of(List.of("b"), List.of("a"), List.of()), f.placements());
    }

    @Test
    void notFinishedHasNoPlacements() {
        final FreeForAll<String> f = new FreeForAll<>(List.of("a", "b", "c"));
        assertTrue(f.placements().isEmpty());
    }

    @Test
    void duplicatesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FreeForAll<>(List.of("a", "a")));
    }
}
