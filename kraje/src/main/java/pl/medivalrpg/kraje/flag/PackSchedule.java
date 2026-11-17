// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

/**
 * When the resource pack is rebuilt (pure arithmetic, unit-tested). A rebuild makes every online player download the
 * whole pack again, so approved flags are batched: the first change waits {@code debounce} for others, and two
 * rebuilds are always at least {@code minInterval} apart. A forced rebuild only shortens the wait to
 * {@code forceMinInterval} after the previous one. Nothing is scheduled when nothing waits.
 */
public final class PackSchedule {

    private PackSchedule() {
    }

    /** Next rebuild after a change (approval / restore), keeping an already earlier schedule. */
    public static long afterChange(final long now, final long lastRebuildAt, final long currentNext, final FlagSettings s) {
        final long candidate = Math.max(now + s.debounceSeconds() * 1000L, lastRebuildAt + s.minIntervalMinutes() * 60_000L);
        return currentNext > 0 ? Math.min(currentNext, candidate) : candidate;
    }

    /** Admin asked for a rebuild now. */
    public static long forced(final long now, final long lastRebuildAt, final FlagSettings s) {
        return Math.max(now, lastRebuildAt + s.forceMinIntervalSeconds() * 1000L);
    }

    /** BetterModel was busy (another reload running): try again shortly. */
    public static long busy(final long now) {
        return now + 30_000L;
    }

    /** The rebuild failed: do not hammer the server, retry after the normal interval. */
    public static long failed(final long now, final FlagSettings s) {
        return now + s.minIntervalMinutes() * 60_000L;
    }

    public static boolean due(final long now, final long next) {
        return next > 0 && now >= next;
    }
}
