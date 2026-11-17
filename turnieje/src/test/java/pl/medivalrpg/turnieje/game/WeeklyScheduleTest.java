// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

    private static final ZoneId WAW = ZoneId.of("Europe/Warsaw");
    private static final WeeklySchedule SAT_20 = new WeeklySchedule(DayOfWeek.SATURDAY, LocalTime.of(20, 0), WAW);

    private static Instant at(final int y, final int mo, final int d, final int h, final int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, WAW).toInstant();
    }

    @Test
    void nextSaturdayFromMidweek() {
        // Tuesday 2026-10-27 12:00 → Saturday 2026-10-31 20:00
        final ZonedDateTime next = SAT_20.nextStart(at(2026, 10, 27, 12, 0));
        assertEquals(ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, WAW), next);
    }

    @Test
    void sameDayBeforeAndAfterStart() {
        assertEquals(ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, WAW), SAT_20.nextStart(at(2026, 10, 31, 19, 59)));
        assertEquals(ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, WAW), SAT_20.nextStart(at(2026, 10, 31, 20, 0)),
            "exactly at the start → next week (strictly after)");
    }

    @Test
    void daylightSavingWeekKeepsLocalTime() {
        // DST ends in Poland on Sunday 2026-10-25; Saturday 24 is CEST, Saturday 31 is CET — both 20:00 local
        final ZonedDateTime before = SAT_20.nextStart(at(2026, 10, 24, 10, 0));
        final ZonedDateTime after = SAT_20.nextStart(before.toInstant());
        assertEquals(20, before.getHour());
        assertEquals(20, after.getHour());
        assertEquals(7 * 24 + 1, java.time.Duration.between(before.toInstant(), after.toInstant()).toHours(),
            "the DST week is one hour longer");
    }

    @Test
    void gapTimeMovesForward() {
        // 02:30 does not exist on 2026-03-29 in Warsaw (clocks jump 02:00 → 03:00)
        final WeeklySchedule s = new WeeklySchedule(DayOfWeek.SUNDAY, LocalTime.of(2, 30), WAW);
        final ZonedDateTime next = s.nextStart(at(2026, 3, 28, 12, 0));
        assertEquals(3, next.getHour());
    }

    @Test
    void signupWindow() {
        final ZonedDateTime start = ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, WAW);
        assertFalse(WeeklySchedule.inSignupWindow(at(2026, 10, 31, 19, 29), start, 30));
        assertTrue(WeeklySchedule.inSignupWindow(at(2026, 10, 31, 19, 30), start, 30));
        assertTrue(WeeklySchedule.inSignupWindow(at(2026, 10, 31, 19, 59), start, 30));
        assertFalse(WeeklySchedule.inSignupWindow(start.toInstant(), start, 30));
    }

    @Test
    void occurrenceKeyIsTheDate() {
        assertEquals("2026-10-31", WeeklySchedule.occurrenceKey(ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, WAW)));
    }

    @Test
    void parsesPolishAndEnglishDays() {
        assertEquals(Optional.of(DayOfWeek.SATURDAY), WeeklySchedule.parseDay("sobota"));
        assertEquals(Optional.of(DayOfWeek.SATURDAY), WeeklySchedule.parseDay("SATURDAY"));
        assertEquals(Optional.of(DayOfWeek.SATURDAY), WeeklySchedule.parseDay("sat"));
        assertEquals(Optional.of(DayOfWeek.WEDNESDAY), WeeklySchedule.parseDay("Środa"));
        assertEquals(Optional.of(DayOfWeek.FRIDAY), WeeklySchedule.parseDay("piątek"));
        assertEquals(Optional.of(DayOfWeek.MONDAY), WeeklySchedule.parseDay("poniedziałek"));
        assertEquals(Optional.of(DayOfWeek.SUNDAY), WeeklySchedule.parseDay("niedz."));
        assertEquals(Optional.of(DayOfWeek.SUNDAY), WeeklySchedule.parseDay("7"));
        assertTrue(WeeklySchedule.parseDay("weekend").isEmpty());
        assertTrue(WeeklySchedule.parseDay("8").isEmpty());
        assertTrue(WeeklySchedule.parseDay("").isEmpty());
    }

    @Test
    void parsesTimesAndZones() {
        assertEquals(Optional.of(LocalTime.of(20, 0)), WeeklySchedule.parseTime("20:00"));
        assertEquals(Optional.of(LocalTime.of(8, 30)), WeeklySchedule.parseTime("8:30"));
        assertTrue(WeeklySchedule.parseTime("25:00").isEmpty());
        assertTrue(WeeklySchedule.parseTime("x").isEmpty());
        assertEquals(WAW, WeeklySchedule.parseZone("Europe/Warsaw"));
        assertEquals(ZoneId.systemDefault(), WeeklySchedule.parseZone(""));
        assertEquals(ZoneId.systemDefault(), WeeklySchedule.parseZone("Mars/Olympus"));
    }

    @Test
    void polishDayNames() {
        assertEquals("sobota", WeeklySchedule.polishDay(DayOfWeek.SATURDAY));
        assertEquals("środa", WeeklySchedule.polishDay(DayOfWeek.WEDNESDAY));
    }
}
