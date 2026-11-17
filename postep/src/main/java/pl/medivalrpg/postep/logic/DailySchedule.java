// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * When the daily research tick is due: once a day at a fixed server time; ticks missed while the server was offline
 * are caught up after a restart (at most {@code maxCatchUp}, the most recent ones). Pure functions.
 */
public final class DailySchedule {

    private DailySchedule() {
    }

    /** The most recent scheduled instant that is not after {@code now}. */
    public static long latestScheduled(final long now, final LocalTime time, final ZoneId zone) {
        final ZonedDateTime current = Instant.ofEpochMilli(now).atZone(zone);
        ZonedDateTime today = ZonedDateTime.of(current.toLocalDate(), time, zone);
        if (today.isAfter(current)) {
            today = ZonedDateTime.of(current.toLocalDate().minusDays(1), time, zone);
        }
        return today.toInstant().toEpochMilli();
    }

    /** The next scheduled instant strictly after {@code now}. */
    public static long nextScheduled(final long now, final LocalTime time, final ZoneId zone) {
        final ZonedDateTime latest = Instant.ofEpochMilli(latestScheduled(now, time, zone)).atZone(zone);
        return ZonedDateTime.of(latest.toLocalDate().plusDays(1), time, zone).toInstant().toEpochMilli();
    }

    /**
     * Scheduled instants in {@code (lastProcessed, now]}, oldest first, at most {@code maxCatchUp} (the newest).
     * Empty when {@code lastProcessed <= 0}: the very first run only starts the schedule.
     */
    public static List<Long> dueTicks(final long lastProcessed, final long now, final LocalTime time, final ZoneId zone,
                                      final int maxCatchUp) {
        if (lastProcessed <= 0 || maxCatchUp <= 0) {
            return List.of();
        }
        final List<Long> due = new ArrayList<>();
        LocalDate day = Instant.ofEpochMilli(latestScheduled(now, time, zone)).atZone(zone).toLocalDate();
        while (due.size() < maxCatchUp) {
            final long instant = ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli();
            if (instant <= lastProcessed) {
                break;
            }
            due.add(instant);
            day = day.minusDays(1);
        }
        Collections.reverse(due);
        return due;
    }

    /** Parses {@code "HH:mm"}; falls back to 06:00 when invalid. */
    public static LocalTime parseTime(final String text) {
        try {
            return LocalTime.parse(text.trim());
        } catch (final RuntimeException e) {
            return LocalTime.of(6, 0);
        }
    }
}
