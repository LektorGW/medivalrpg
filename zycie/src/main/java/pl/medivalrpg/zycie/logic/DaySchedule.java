// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * The citizens' daily plan in Minecraft time (0 = 6:00, 6000 = noon, 12000 = 18:00, 18000 = midnight).
 *
 * <p>Default boundaries follow the vanilla villager schedule so that the plugin only steers where vanilla does not
 * care much: vanilla WORK is 2000–9000 (we choose the building and, at noon, the market), vanilla MEET 9000–11000
 * (bell — left to vanilla), vanilla REST from 12000 (bed — never touched).</p>
 *
 * @param workStart    end of the morning
 * @param noonStart    lunch break (part of the citizens go to the market)
 * @param noonEnd      back to work
 * @param meetStart    vanilla meeting at the bell
 * @param eveningStart tavern / square
 * @param nightStart   home and bed (vanilla)
 * @param massEnd      Sunday mass lasts from 0 to this time
 * @param weekLength   days per week (7)
 * @param sundayIndex  {@code day % weekLength} of the Sunday
 */
public record DaySchedule(int workStart, int noonStart, int noonEnd, int meetStart, int eveningStart, int nightStart,
                          int massEnd, int weekLength, int sundayIndex) {

    public static final int DAY_TICKS = 24000;

    public static DaySchedule defaults() {
        return new DaySchedule(2000, 6000, 8000, 9000, 10500, 12500, 3000, 7, 6);
    }

    /** Problems with the boundaries (empty = valid). */
    public List<String> validate() {
        final List<String> p = new ArrayList<>();
        final int[] order = {0, this.workStart, this.noonStart, this.noonEnd, this.meetStart, this.eveningStart,
            this.nightStart, DAY_TICKS};
        for (int i = 1; i < order.length; i++) {
            if (order[i] < order[i - 1]) {
                p.add("pory dnia muszą rosnąć: praca < południe < koniec południa < spotkanie < wieczór < noc < 24000");
                break;
            }
        }
        if (this.weekLength < 1) {
            p.add("plan-dnia.dni-tygodnia musi być ≥ 1");
        }
        if (this.massEnd < 0 || this.massEnd > this.nightStart) {
            p.add("plan-dnia.msza-do musi leżeć między 0 a początkiem nocy");
        }
        return p;
    }

    public static long dayNumber(final long fullTime) {
        return Math.floorDiv(fullTime, DAY_TICKS);
    }

    public static int timeOfDay(final long fullTime) {
        return (int) Math.floorMod(fullTime, (long) DAY_TICKS);
    }

    public boolean isSunday(final long fullTime) {
        return this.weekLength > 0 && Math.floorMod(dayNumber(fullTime), (long) this.weekLength) == this.sundayIndex;
    }

    /** Phase at the given full world time (ticks since world creation, includes the day number). */
    public DayPhase phase(final long fullTime) {
        final int t = timeOfDay(fullTime);
        if (this.isSunday(fullTime) && t < this.massEnd) {
            return DayPhase.MASS;
        }
        return this.phaseOfTime(t);
    }

    /** Phase from the time of day only (no Sunday). */
    public DayPhase phaseOfTime(final int t) {
        if (t >= this.nightStart) {
            return DayPhase.NIGHT;
        }
        if (t >= this.eveningStart) {
            return DayPhase.EVENING;
        }
        if (t >= this.meetStart) {
            return DayPhase.MEET;
        }
        if (t >= this.noonStart && t < this.noonEnd) {
            return DayPhase.NOON;
        }
        if (t >= this.workStart) {
            return DayPhase.WORK;
        }
        return DayPhase.MORNING;
    }
}
