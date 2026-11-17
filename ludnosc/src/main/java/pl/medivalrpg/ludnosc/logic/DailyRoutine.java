// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * Rhythm of a citizen's day in Minecraft time (0 = 6:00, 6000 = noon, 12000 = 18:00, 18000 = midnight), pure and
 * unit tested. The same boundaries as the default plan of the Zycie plugin: night at home in bed, morning and
 * afternoon at work, a break at noon (market), evening at home. Used for the abstract life of citizens in unloaded
 * chunks (what they are doing now) and for steering loaded ones when Zycie is absent.
 *
 * @param workStart    end of the morning (the way to work)
 * @param noonStart    lunch break
 * @param noonEnd      back to work
 * @param eveningStart home time
 * @param nightStart   bed
 */
public record DailyRoutine(int workStart, int noonStart, int noonEnd, int eveningStart, int nightStart) {

    public static final int DAY_TICKS = 24000;

    /** Part of the day. */
    public enum Phase {
        MORNING("rano"),
        WORK("praca"),
        NOON("południe"),
        EVENING("wieczór"),
        NIGHT("noc");

        private final String title;

        Phase(final String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    /** Where the citizen should be. */
    public enum Place {
        HOME("w domu"),
        BED("w łóżku"),
        WORK("w pracy"),
        MARKET("na rynku (przerwa)"),
        COMMUTE("w drodze do pracy"),
        PLAY("bawi się przy domu"),
        IDLE("szuka pracy na rynku");

        private final String title;

        Place(final String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    public static DailyRoutine defaults() {
        return new DailyRoutine(2000, 6000, 8000, 10500, 12500);
    }

    /** Boundaries must grow (else defaults are used by the caller). */
    public boolean valid() {
        return 0 <= this.workStart && this.workStart <= this.noonStart && this.noonStart <= this.noonEnd
            && this.noonEnd <= this.eveningStart && this.eveningStart <= this.nightStart && this.nightStart <= DAY_TICKS;
    }

    public static int timeOfDay(final long fullTime) {
        return (int) Math.floorMod(fullTime, (long) DAY_TICKS);
    }

    public Phase phase(final long fullTime) {
        final int t = timeOfDay(fullTime);
        if (t >= this.nightStart) {
            return Phase.NIGHT;
        }
        if (t >= this.eveningStart) {
            return Phase.EVENING;
        }
        if (t >= this.noonStart && t < this.noonEnd) {
            return Phase.NOON;
        }
        if (t >= this.workStart) {
            return Phase.WORK;
        }
        return Phase.MORNING;
    }

    /**
     * Where a citizen is expected now.
     *
     * @param adult    children do not work
     * @param employed has a workplace
     * @param housed   has a bed
     */
    public Place place(final long fullTime, final boolean adult, final boolean employed, final boolean housed) {
        final Phase p = this.phase(fullTime);
        if (p == Phase.NIGHT) {
            return housed ? Place.BED : Place.IDLE;
        }
        if (p == Phase.EVENING) {
            return housed ? Place.HOME : Place.MARKET;
        }
        if (!adult) {
            return housed ? Place.PLAY : Place.MARKET;
        }
        return switch (p) {
            case MORNING -> employed ? Place.COMMUTE : (housed ? Place.HOME : Place.IDLE);
            case WORK -> employed ? Place.WORK : Place.IDLE;
            case NOON -> Place.MARKET;
            default -> Place.HOME;
        };
    }

    /** Clock of the Minecraft time, e.g. {@code 09:30}. */
    public static String clock(final long fullTime) {
        final int t = timeOfDay(fullTime);
        final int minutes = (int) ((t / 1000.0 * 60 + 6 * 60) % (24 * 60));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
