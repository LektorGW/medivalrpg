// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/**
 * Protest and departure thresholds (pure). Times are Minecraft ticks of the main world's full time.
 *
 * @param protestBelow  mood below which discontent accumulates (20)
 * @param protestAfter  ticks of continuous low mood before a protest starts (24000 = one Minecraft day)
 * @param protestEndAt  mood at which the protest ends (hysteresis, 25)
 * @param exodusBelow   mood below which citizens start leaving (10) — signalled through {@code unrest}
 */
public record UnrestRules(int protestBelow, long protestAfter, int protestEndAt, int exodusBelow) {

    public static UnrestRules defaults() {
        return new UnrestRules(20, DaySchedule.DAY_TICKS, 25, 10);
    }

    /**
     * Unrest state of a country.
     *
     * @param lowSince tick since which the mood has been below {@link #protestBelow}, -1 = not low
     * @param protest  protest on the market square is running
     */
    public record State(long lowSince, boolean protest) {

        public static final State CALM = new State(-1L, false);
    }

    public State update(final State previous, final int mood, final long now) {
        final State st = previous == null ? State.CALM : previous;
        if (mood >= this.protestEndAt) {
            return State.CALM;
        }
        if (mood >= this.protestBelow) {
            // between the thresholds: a running protest goes on, a beginning discontent is forgotten
            return st.protest() ? new State(st.lowSince(), true) : State.CALM;
        }
        long since = st.lowSince() < 0 || st.lowSince() > now ? now : st.lowSince();
        final boolean protest = st.protest() || now - since > this.protestAfter;
        return new State(since, protest);
    }

    /** What {@code ZycieApi#unrest} reports: a protest, or a mood so low that citizens leave. */
    public boolean unrest(final State st, final int mood) {
        return (st != null && st.protest()) || mood < this.exodusBelow;
    }

    /** Citizens leave (Ludnosc departures): very low mood. */
    public boolean exodus(final int mood) {
        return mood < this.exodusBelow;
    }
}
