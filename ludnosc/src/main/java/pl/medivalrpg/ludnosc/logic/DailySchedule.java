// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * When the daily economy runs (pure): once per calendar day at a configured time, catching up missed days after a
 * restart (limited to the {@code maxCatchUp} most recent days).
 */
public final class DailySchedule {

    private DailySchedule() {
    }

    /** The most recent day whose tick time has already passed at {@code now}. */
    public static LocalDate lastPassed(final ZonedDateTime now, final LocalTime time) {
        final LocalDate today = now.toLocalDate();
        return now.toLocalTime().isBefore(time) ? today.minusDays(1) : today;
    }

    /**
     * Days that still have to be processed, oldest first. {@code lastProcessed == null} (first start) → none; the
     * caller then stores {@link #lastPassed} as processed so the first tick happens at the next scheduled time.
     */
    public static List<LocalDate> due(final LocalDate lastProcessed, final ZonedDateTime now, final LocalTime time,
                                      final int maxCatchUp) {
        final List<LocalDate> out = new ArrayList<>();
        if (lastProcessed == null) {
            return out;
        }
        final LocalDate last = lastPassed(now, time);
        for (LocalDate d = lastProcessed.plusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
            out.add(d);
        }
        final int max = Math.max(1, maxCatchUp);
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }
}
