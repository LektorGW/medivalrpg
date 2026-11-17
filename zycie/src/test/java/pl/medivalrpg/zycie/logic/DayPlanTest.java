// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DayPlanTest {

    private final DaySchedule sched = DaySchedule.defaults();
    private final DailyPlan plan = DailyPlan.defaults();

    /** A weekday (day 1). */
    private static long weekday(final int time) {
        return DaySchedule.DAY_TICKS + time;
    }

    @Test
    void phasesFollowTheClock() {
        assertEquals(DayPhase.MORNING, this.sched.phase(weekday(500)));
        assertEquals(DayPhase.WORK, this.sched.phase(weekday(2000)));
        assertEquals(DayPhase.WORK, this.sched.phase(weekday(5999)));
        assertEquals(DayPhase.NOON, this.sched.phase(weekday(6000)));
        assertEquals(DayPhase.WORK, this.sched.phase(weekday(8500)));
        assertEquals(DayPhase.MEET, this.sched.phase(weekday(9500)));
        assertEquals(DayPhase.EVENING, this.sched.phase(weekday(11000)));
        assertEquals(DayPhase.NIGHT, this.sched.phase(weekday(13000)));
        assertEquals(DayPhase.NIGHT, this.sched.phase(weekday(23999)));
        assertTrue(this.sched.validate().isEmpty());
    }

    @Test
    void everySeventhDayHasAMorningMass() {
        final long sunday = 6L * DaySchedule.DAY_TICKS;
        assertTrue(this.sched.isSunday(sunday + 100));
        assertFalse(this.sched.isSunday(weekday(100)));
        assertEquals(DayPhase.MASS, this.sched.phase(sunday + 100));
        assertEquals(DayPhase.MASS, this.sched.phase(sunday + 2999));
        assertEquals(DayPhase.WORK, this.sched.phase(sunday + 3000));
        assertEquals(DayPhase.MASS, this.sched.phase(sunday + 7L * DaySchedule.DAY_TICKS + 10), "next week too");
    }

    @Test
    void invalidScheduleIsReported() {
        final DaySchedule bad = new DaySchedule(5000, 4000, 8000, 9000, 10500, 12500, 3000, 7, 6);
        assertFalse(bad.validate().isEmpty());
    }

    private Destination dest(final DayPhase phase, final double share) {
        return this.plan.destination(new DailyPlan.Situation(phase, true, false, false, false, false, share));
    }

    @Test
    void phaseToDestination() {
        assertEquals(Destination.WORKPLACE, this.dest(DayPhase.WORK, 0.1));
        assertEquals(Destination.MARKET, this.dest(DayPhase.NOON, 0.1));
        assertEquals(Destination.WORKPLACE, this.dest(DayPhase.NOON, 0.9));
        assertEquals(Destination.TAVERN, this.dest(DayPhase.EVENING, 0.2));
        assertEquals(Destination.HOME, this.dest(DayPhase.EVENING, 0.8), "evening: home (Ludnosc bed) unless at the tavern");
        assertEquals(Destination.BELL, new DailyPlan(0.3, 0.5, 0.8, 0.6, 0.7).destination(
            new DailyPlan.Situation(DayPhase.EVENING, true, false, false, false, false, 0.8)), "old configs: bell square");
        assertEquals(Destination.CHURCH, this.dest(DayPhase.MASS, 0.5));
        assertEquals(Destination.NONE, this.dest(DayPhase.MASS, 0.95));
        // vanilla keeps the morning, the bell meeting and the night (bed)
        assertEquals(Destination.NONE, this.dest(DayPhase.MORNING, 0.1));
        assertEquals(Destination.NONE, this.dest(DayPhase.MEET, 0.1));
        assertEquals(Destination.NONE, this.dest(DayPhase.NIGHT, 0.1));
    }

    @Test
    void specialSituationsWin() {
        // siege / enemy: hide (vanilla bell reaction), even babies
        assertEquals(Destination.SHELTER, this.plan.destination(new DailyPlan.Situation(DayPhase.WORK, false, true, false, false, false, 0.1)));
        // babies follow vanilla
        assertEquals(Destination.NONE, this.plan.destination(new DailyPlan.Situation(DayPhase.WORK, false, false, false, false, false, 0.1)));
        assertEquals(Destination.GATE, this.plan.destination(new DailyPlan.Situation(DayPhase.NIGHT, true, false, true, false, false, 0.1)));
        assertEquals(Destination.FESTIVAL, this.plan.destination(new DailyPlan.Situation(DayPhase.WORK, true, false, false, true, false, 0.1)));
        assertEquals(Destination.NONE, this.plan.destination(new DailyPlan.Situation(DayPhase.NIGHT, true, false, false, true, false, 0.1)),
            "festival nights belong to the bed");
        assertEquals(Destination.PROTEST, this.plan.destination(new DailyPlan.Situation(DayPhase.NOON, true, false, false, false, true, 0.1)));
        assertEquals(Destination.WORKPLACE, this.plan.destination(new DailyPlan.Situation(DayPhase.NOON, true, false, false, false, true, 0.7)),
            "not everybody protests (share above udzial-protest)");
        assertEquals(Destination.NONE, this.plan.destination(new DailyPlan.Situation(DayPhase.MORNING, true, false, false, false, true, 0.1)),
            "protests start after the morning");
    }

    @Test
    void placesFallBackToBellAndCentre() {
        final Places p = Places.defaults();
        final List<Places.Place> work = p.candidates(Destination.WORKPLACE, List.of("kuznia"));
        assertEquals(Places.Kind.LUDNOSC_WORK, work.getFirst().kind(), "the permanent workplace from Ludnosc first");
        assertEquals(List.of("kuznia"), work.get(1).tokens());
        assertEquals(List.of(Places.Kind.LUDNOSC_WORK, Places.Kind.BUILDINGS, Places.Kind.JOB_SITE, Places.Kind.BELL, Places.Kind.CENTER),
            work.stream().map(Places.Place::kind).toList());
        assertEquals(List.of(Places.Kind.BUILDINGS, Places.Kind.BELL, Places.Kind.CENTER),
            p.candidates(Destination.TAVERN, List.of()).stream().map(Places.Place::kind).toList());
        assertEquals(Places.Kind.BELL, p.candidates(Destination.BELL, List.of()).getFirst().kind());
        assertEquals(List.of(Places.Kind.LUDNOSC_HOME, Places.Kind.BELL),
            p.candidates(Destination.HOME, List.of()).stream().map(Places.Place::kind).toList());
        assertTrue(p.candidates(Destination.NONE, List.of()).isEmpty());
        assertTrue(p.candidates(Destination.SHELTER, List.of()).isEmpty());
    }

    @Test
    void steeringWindows() {
        final SteeringRules r = SteeringRules.defaults();
        assertEquals(SteeringRules.Decision.ARRIVED, r.decide(3, false, 1000));
        assertEquals(SteeringRules.Decision.MOVE, r.decide(30, false, 1000));
        assertEquals(SteeringRules.Decision.IDLE, r.decide(30, false, 10), "too soon after the last move");
        assertEquals(SteeringRules.Decision.IDLE, r.decide(15, true, 1000), "arrived: vanilla roams inside the leash");
        assertEquals(SteeringRules.Decision.MOVE, r.decide(25, true, 1000), "wandered off: steer back");
        assertEquals(1.0, r.stepFraction(20));
        assertEquals(0.5, r.stepFraction(64), 1e-9);
    }
}
