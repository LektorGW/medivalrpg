// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * Real-time season cycle (pure, unit tested). From {@code anchor} (epoch millis) every {@code length} millis the next
 * season begins, starting with {@code first}. Changing the season by an admin only moves the anchor.
 *
 * @param anchor epoch millis when a season equal to {@code first} began
 * @param length millis of one season (at least one minute)
 * @param first  season that began at {@code anchor}
 */
public record SeasonClock(long anchor, long length, Season first) {

    public static final long DAY = 86_400_000L;

    public SeasonClock {
        length = Math.max(60_000L, length);
        first = first == null ? Season.WIOSNA : first;
    }

    /**
     * The season at a moment.
     *
     * @param season    the season
     * @param serial    number of seasons since the anchor (changes exactly when the season changes)
     * @param day       day of the season, 1-based
     * @param days      whole days of one season (at least 1)
     * @param startedAt epoch millis when it began
     * @param endsAt    epoch millis when the next one begins
     */
    public record State(Season season, long serial, int day, int days, long startedAt, long endsAt) {

        /** Fraction 0..1 of the season already passed. */
        public double progress(final long now) {
            final long len = Math.max(1, this.endsAt - this.startedAt);
            return Math.max(0, Math.min(1, (now - this.startedAt) / (double) len));
        }
    }

    public State at(final long now) {
        final long serial = Math.floorDiv(now - this.anchor, this.length);
        final long start = this.anchor + serial * this.length;
        final int days = (int) Math.max(1, Math.round(this.length / (double) DAY));
        final long dayLength = Math.max(1, this.length / days);
        final int day = (int) Math.min(days, 1 + Math.floorDiv(now - start, dayLength));
        return new State(this.first.plus(serial), serial, day, days, start, start + this.length);
    }

    /** A clock in which {@code season} began at {@code now} minus {@code (day - 1)} days. */
    public SeasonClock startingAt(final Season season, final int day, final long now) {
        final int days = (int) Math.max(1, Math.round(this.length / (double) DAY));
        final long dayLength = Math.max(1, this.length / days);
        final long start = now - (long) (Math.max(1, Math.min(days, day)) - 1) * dayLength;
        // the anchor stays a "first" season: go back k whole seasons so that first + k == season
        final long k = Math.floorMod(season.ordinal() - this.first.ordinal(), 4);
        return new SeasonClock(start - k * this.length, this.length, this.first);
    }

    /** Length in millis from (possibly fractional) days. */
    public static long lengthOfDays(final double days) {
        return Math.max(60_000L, Math.round(Math.max(0, days) * DAY));
    }
}
