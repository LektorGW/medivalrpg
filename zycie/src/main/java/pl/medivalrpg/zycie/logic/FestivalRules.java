// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/**
 * When festivals start and how long they last (pure). Times are Minecraft ticks.
 *
 * @param duration     length of a festival (24000 = one Minecraft day)
 * @param cooldown     minimum time between the starts of two automatic festivals of one country
 * @param moodBonus    mood points while the festival lasts
 */
public record FestivalRules(long duration, long cooldown, int moodBonus) {

    public static FestivalRules defaults() {
        return new FestivalRules(DaySchedule.DAY_TICKS, 2L * DaySchedule.DAY_TICKS, 12);
    }

    /** Festival state: running until {@code until} (tick), last started at {@code lastStart} (-1 = never). */
    public record State(long until, long lastStart) {

        public static final State NONE = new State(-1L, -1L);

        public boolean active(final long now) {
            return now < this.until;
        }
    }

    /**
     * Tries to start (or prolong) a festival.
     *
     * @param forced admin command: ignores the cooldown
     * @return the new state, or the unchanged one when the cooldown forbids it
     */
    public State start(final State previous, final long now, final boolean forced) {
        final State st = previous == null ? State.NONE : previous;
        if (!forced && st.lastStart() >= 0 && st.lastStart() <= now && now - st.lastStart() < this.cooldown) {
            return st;
        }
        return new State(Math.max(st.until(), now + this.duration), now);
    }

    /** True when {@link #start} would change the state now. */
    public boolean canStart(final State st, final long now, final boolean forced) {
        return !this.start(st, now, forced).equals(st == null ? State.NONE : st);
    }

    /** A great building (palace, cathedral…) was finished: the count grew since the last check (-1 = first check). */
    public static boolean greatBuildingFinished(final int previousCount, final int currentCount) {
        return previousCount >= 0 && currentCount > previousCount;
    }
}
