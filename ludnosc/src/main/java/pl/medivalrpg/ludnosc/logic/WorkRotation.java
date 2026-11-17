// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Refreshes the visible-work queue without starving workers at its tail. Main thread only. */
public final class WorkRotation {
    private WorkRotation() { }

    public static void refresh(final ArrayDeque<UUID[]> queue, final Map<UUID, JobBoard.Assignment> boards,
                               final Set<UUID> paused) {
        final Set<UUID> queued = new HashSet<>();
        queue.removeIf(e -> paused.contains(e[1])
            || !boards.getOrDefault(e[1], JobBoard.Assignment.EMPTY).workplaceOf().containsKey(e[0])
            || !queued.add(e[0]));
        boards.forEach((country, board) -> {
            if (!paused.contains(country)) {
                board.workplaceOf().keySet().forEach(citizen -> {
                    if (queued.add(citizen)) {
                        queue.addLast(new UUID[] {citizen, country});
                    }
                });
            }
        });
    }
}
