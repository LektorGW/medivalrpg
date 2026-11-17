// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalTime SIX = LocalTime.of(6, 0);

    private static ZonedDateTime at(final int day, final int hour) {
        return ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, ZONE);
    }

    @Test
    void lastPassedDependsOnTheTime() {
        assertEquals(LocalDate.of(2026, 10, 24), DailySchedule.lastPassed(at(25, 5), SIX));
        assertEquals(LocalDate.of(2026, 10, 25), DailySchedule.lastPassed(at(25, 6), SIX));
    }

    @Test
    void firstStartSchedulesNothing() {
        assertEquals(List.of(), DailySchedule.due(null, at(25, 12), SIX, 3));
    }

    @Test
    void oneTickPerDay() {
        final LocalDate d24 = LocalDate.of(2026, 10, 24);
        assertEquals(List.of(), DailySchedule.due(d24, at(25, 5), SIX, 3));
        assertEquals(List.of(LocalDate.of(2026, 10, 25)), DailySchedule.due(d24, at(25, 7), SIX, 3));
        assertEquals(List.of(), DailySchedule.due(LocalDate.of(2026, 10, 25), at(25, 23), SIX, 3));
    }

    @Test
    void catchUpIsLimitedToTheMostRecentDays() {
        final List<LocalDate> due = DailySchedule.due(LocalDate.of(2026, 10, 10), at(25, 7), SIX, 3);
        assertEquals(List.of(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25)), due);
    }
}
