// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkRotationTest {
    private static UUID id(int n) { return new UUID(0, n); }
    private static JobBoard.Assignment board(int... citizens) {
        final Map<UUID, UUID> places = new LinkedHashMap<>();
        for (int n : citizens) places.put(id(n), id(100));
        return new JobBoard.Assignment(places, Map.of(), Map.of());
    }

    @Test void newWorkersJoinANonemptyQueue() {
        final var queue = new ArrayDeque<UUID[]>();
        queue.add(new UUID[] {id(1), id(10)});
        WorkRotation.refresh(queue, Map.of(id(10), board(1, 2)), Set.of());
        assertEquals(List.of(id(1), id(2)), queue.stream().map(e -> e[0]).toList());
    }

    @Test void repeatedRefreshPreservesFairnessAndDoesNotDuplicate() {
        final var queue = new ArrayDeque<UUID[]>();
        final var boards = Map.of(id(10), board(1, 2, 3));
        WorkRotation.refresh(queue, boards, Set.of());
        final UUID first = queue.peekFirst()[0];
        queue.addLast(queue.removeFirst());
        final UUID next = queue.peekFirst()[0];
        for (int n = 0; n < 20; n++) WorkRotation.refresh(queue, boards, Set.of());
        assertEquals(3, queue.size());
        assertEquals(next, queue.peekFirst()[0]);
        assertEquals(first, queue.peekLast()[0]);
    }

    @Test void transferRemovesOldCountryAndRequeuesUnderNewOwner() {
        final var queue = new ArrayDeque<UUID[]>();
        queue.add(new UUID[] {id(1), id(10)});
        WorkRotation.refresh(queue, Map.of(id(11), board(1)), Set.of());
        assertEquals(1, queue.size());
        assertEquals(id(11), queue.peekFirst()[1]);
    }

    @Test void pausedAndDeletedCountriesLeaveTheQueueAndCanResume() {
        final var queue = new ArrayDeque<UUID[]>();
        queue.add(new UUID[] {id(1), id(10)});
        queue.add(new UUID[] {id(2), id(11)});
        final var boards = Map.of(id(10), board(1));
        WorkRotation.refresh(queue, boards, Set.of(id(10)));
        assertTrue(queue.isEmpty());
        WorkRotation.refresh(queue, boards, Set.of());
        assertEquals(id(1), queue.peekFirst()[0]);
    }
}
