// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * How long the planner skips a building type that found no place (pure, unit tested): 10 minutes after the first
 * failure, doubled with every further one, at most 12 hours. Meanwhile other buildings are planned.
 */
public final class Backoff {

    public static final long FIRST = 10 * 60_000L;
    public static final long MAX = 12 * 3_600_000L;

    private Backoff() {
    }

    /** Delay after {@code failures} (1 = the first) failures in a row. */
    public static long delay(final int failures) {
        if (failures <= 1) {
            return FIRST;
        }
        final int doublings = Math.min(failures - 1, 20);
        return Math.min(MAX, FIRST << doublings);
    }
}
