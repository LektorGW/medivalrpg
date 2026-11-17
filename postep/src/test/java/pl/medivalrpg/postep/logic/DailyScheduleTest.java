// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalTime SIX = LocalTime.of(6, 0);

    private static long at(final int day, final int hour) {
        return ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void nothingDueTheSameDay() {
        assertTrue(DailySchedule.dueTicks(at(20, 6), at(20, 23), SIX, ZONE, 7).isEmpty());
    }

    @Test
    void oneTickAfterTheHour() {
        assertEquals(List.of(at(21, 6)), DailySchedule.dueTicks(at(20, 6), at(21, 7), SIX, ZONE, 7));
    }

    @Test
    void catchesUpMissedDaysWithLimit() {
        // offline from the 10th to the 20th (DST change on the 25th is not involved)
        assertEquals(List.of(at(18, 6), at(19, 6), at(20, 6)), DailySchedule.dueTicks(at(10, 6), at(20, 12), SIX, ZONE, 3));
        assertEquals(10, DailySchedule.dueTicks(at(10, 6), at(20, 12), SIX, ZONE, 30).size());
    }

    @Test
    void firstRunStartsTheSchedule() {
        assertTrue(DailySchedule.dueTicks(0, at(20, 12), SIX, ZONE, 7).isEmpty());
        assertEquals(at(20, 6), DailySchedule.latestScheduled(at(20, 12), SIX, ZONE));
        assertEquals(at(19, 6), DailySchedule.latestScheduled(at(20, 5), SIX, ZONE));
        assertEquals(at(21, 6), DailySchedule.nextScheduled(at(20, 12), SIX, ZONE));
    }
}
