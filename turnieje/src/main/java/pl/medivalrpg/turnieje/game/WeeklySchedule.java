// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * Weekly tournament time, e.g. Saturday 20:00 in the server time zone. Pure logic (the clock is a parameter).
 * Daylight-saving gaps move the start forward like {@link ZonedDateTime#of}.
 */
public record WeeklySchedule(DayOfWeek day, LocalTime time, ZoneId zone) {

    /** First start strictly after {@code now}. */
    public ZonedDateTime nextStart(final Instant now) {
        final ZonedDateTime local = now.atZone(this.zone);
        LocalDate date = local.toLocalDate().with(TemporalAdjusters.nextOrSame(this.day));
        ZonedDateTime candidate = ZonedDateTime.of(date, this.time, this.zone);
        while (!candidate.toInstant().isAfter(now)) {
            date = date.plusWeeks(1);
            candidate = ZonedDateTime.of(date, this.time, this.zone);
        }
        return candidate;
    }

    /** True when sign-ups for {@code start} should be open at {@code now} (window = minutes before the start). */
    public static boolean inSignupWindow(final Instant now, final ZonedDateTime start, final int signupMinutes) {
        final Instant open = start.toInstant().minusSeconds(Math.max(0, signupMinutes) * 60L);
        return !now.isBefore(open) && now.isBefore(start.toInstant());
    }

    /** Stable id of one weekly occurrence, used to never run the same week twice. */
    public static String occurrenceKey(final ZonedDateTime start) {
        return start.toLocalDate().toString();
    }

    /** {@code SATURDAY}, {@code sat}, {@code sobota}, {@code sob.}, {@code 6} … */
    public static Optional<DayOfWeek> parseDay(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String s = Texts.normalize(raw).replace(".", "");
        try {
            final int n = Integer.parseInt(s);
            return n >= 1 && n <= 7 ? Optional.of(DayOfWeek.of(n)) : Optional.empty();
        } catch (final NumberFormatException ignored) {
            // a name
        }
        for (final DayOfWeek d : DayOfWeek.values()) {
            final String en = d.name().toLowerCase(Locale.ROOT);
            if (en.equals(s) || en.substring(0, 3).equals(s)) {
                return Optional.of(d);
            }
        }
        final String[] pl = {"poniedzialek", "wtorek", "sroda", "czwartek", "piatek", "sobota", "niedziela"};
        final String[] plShort = {"pon", "wt", "sr", "czw", "pt", "sob", "nd"};
        for (int i = 0; i < 7; i++) {
            if (pl[i].equals(s) || plShort[i].equals(s) || (i == 6 && "niedz".equals(s))) {
                return Optional.of(DayOfWeek.of(i + 1));
            }
        }
        return Optional.empty();
    }

    /** {@code "20:00"} / {@code "8:30"}. */
    public static Optional<LocalTime> parseTime(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.length() == 4 && s.charAt(1) == ':') {
            s = "0" + s;
        }
        try {
            return Optional.of(LocalTime.parse(s));
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Empty / unknown = system default. */
    public static ZoneId parseZone(final String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (final RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Polish name of the day in the nominative, e.g. {@code sobota}. */
    public static String polishDay(final DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "poniedziałek";
            case TUESDAY -> "wtorek";
            case WEDNESDAY -> "środa";
            case THURSDAY -> "czwartek";
            case FRIDAY -> "piątek";
            case SATURDAY -> "sobota";
            case SUNDAY -> "niedziela";
        };
    }
}
