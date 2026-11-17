// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * "Once a day at HH:mm" in a time zone (pure). Checked every minute: the job is due when today's slot has passed
 * and it has not run today. Without catch-up only the first {@link #GRACE_MINUTES} after the slot count (a server
 * that was off at the time skips the day). Zone rules (DST) come from {@link ZonedDateTime}.
 */
public final class DailyClock {

    public static final int GRACE_MINUTES = 10;

    private DailyClock() {
    }

    public static LocalTime parse(final String hhmm, final LocalTime fallback) {
        try {
            return LocalTime.parse(hhmm == null ? "" : hhmm.strip());
        } catch (final DateTimeParseException e) {
            return fallback;
        }
    }

    public static boolean due(final ZonedDateTime now, final LocalTime at, final LocalDate lastDone, final boolean catchUp) {
        final LocalDate today = now.toLocalDate();
        if (today.equals(lastDone)) {
            return false;
        }
        final ZonedDateTime slot = ZonedDateTime.of(today, at, now.getZone());
        if (now.isBefore(slot)) {
            return false;
        }
        return catchUp || now.isBefore(slot.plusMinutes(GRACE_MINUTES));
    }

    /** Next run after {@code now} (for /straz status). */
    public static ZonedDateTime next(final ZonedDateTime now, final LocalTime at, final LocalDate lastDone) {
        final ZonedDateTime slot = ZonedDateTime.of(now.toLocalDate(), at, now.getZone());
        if (now.toLocalDate().equals(lastDone) || !now.isBefore(slot)) {
            return ZonedDateTime.of(now.toLocalDate().plusDays(1), at, now.getZone());
        }
        return slot;
    }
}
