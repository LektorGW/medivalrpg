// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Smart ordering of construction jobs: the manager's priority first, then structures the network needs most —
 * segments that connect two stations/depots (a usable line once done), segments touching already built track (the
 * network grows outwards instead of in disconnected pieces), stations and depots before plain segments of equal rank,
 * then the job closest to completion. Pure.
 */
public final class JobOrder {

    /** View of a job for ordering. */
    public record Item(String id, int priority, boolean connectsWaitingPoints, boolean touchesBuilt, boolean structure,
                       double remainingFraction, long createdAt, boolean paused) {
    }

    private JobOrder() {
    }

    public static List<Item> order(final List<Item> items) {
        final List<Item> out = new ArrayList<>(items);
        out.removeIf(Item::paused);
        out.sort(Comparator.comparingInt((Item i) -> -i.priority())
            .thenComparing(i -> !i.connectsWaitingPoints())
            .thenComparing(i -> !i.touchesBuilt())
            .thenComparing(i -> !i.structure())
            .thenComparingDouble(Item::remainingFraction)
            .thenComparingLong(Item::createdAt));
        return out;
    }

    /** The {@code max} jobs that should be active now. */
    public static List<String> active(final List<Item> items, final int max) {
        return order(items).stream().limit(Math.max(0, max)).map(Item::id).toList();
    }
}
