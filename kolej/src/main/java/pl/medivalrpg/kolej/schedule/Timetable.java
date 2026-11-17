// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Timetable (rozkład jazdy) of a line: departures from the first station, either every {@code everyMinutes} within
 * service hours ({@code fromMinute}..{@code toMinute} of the day) or at fixed times of day. Times are local times of
 * the configured zone (default Europe/Warsaw).
 */
public final class Timetable {

    public enum Mode { EVERY, TIMES, NONE }

    public Mode mode = Mode.NONE;
    public int everyMinutes = 30;
    public int fromMinute = 6 * 60;
    public int toMinute = 22 * 60;
    public final TreeSet<Integer> times = new TreeSet<>();
    /** Stop time at intermediate stations (s). */
    public int dwellSeconds = 40;
    /** Waiting time at the terminus before the return trip (s). */
    public int turnaroundSeconds = 120;

    public static Timetable every(final int minutes, final int from, final int to) {
        final Timetable t = new Timetable();
        t.mode = Mode.EVERY;
        t.everyMinutes = Math.max(1, minutes);
        t.fromMinute = from;
        t.toMinute = to;
        return t;
    }

    public static Timetable at(final List<Integer> minutesOfDay) {
        final Timetable t = new Timetable();
        t.mode = Mode.TIMES;
        for (final int m : minutesOfDay) {
            t.times.add(Math.floorMod(m, 24 * 60));
        }
        return t;
    }

    /** Departure minutes of one day in order. */
    public List<Integer> minutesOfDay() {
        final List<Integer> out = new ArrayList<>();
        switch (this.mode) {
            case EVERY -> {
                for (int m = this.fromMinute; m <= this.toMinute && m < 24 * 60; m += this.everyMinutes) {
                    out.add(m);
                }
            }
            case TIMES -> out.addAll(this.times);
            case NONE -> { }
        }
        return out;
    }

    /** Departures in {@code (after, until]}. */
    public List<LocalDateTime> between(final LocalDateTime after, final LocalDateTime until) {
        final List<LocalDateTime> out = new ArrayList<>();
        final List<Integer> day = this.minutesOfDay();
        if (day.isEmpty() || !until.isAfter(after)) {
            return out;
        }
        for (LocalDate d = after.toLocalDate(); !d.isAfter(until.toLocalDate()); d = d.plusDays(1)) {
            for (final int m : day) {
                final LocalDateTime t = LocalDateTime.of(d, LocalTime.of(m / 60, m % 60));
                if (t.isAfter(after) && !t.isAfter(until)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** First departure strictly after {@code after} (within the next 8 days). */
    public Optional<LocalDateTime> next(final LocalDateTime after) {
        final List<LocalDateTime> list = this.between(after, after.plusDays(8));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /** Parses {@code "HH:MM"} to a minute of the day; -1 when invalid. */
    public static int parseTime(final String s) {
        final String[] p = s.trim().split("[:.]");
        if (p.length != 2) {
            return -1;
        }
        try {
            final int h = Integer.parseInt(p[0]);
            final int m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    public static String formatTime(final int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** Polish one-line description. */
    public String describe() {
        return switch (this.mode) {
            case EVERY -> "co " + this.everyMinutes + " min, " + formatTime(this.fromMinute) + "–" + formatTime(this.toMinute);
            case TIMES -> this.times.isEmpty() ? "brak odjazdów"
                : String.join(", ", this.times.stream().map(Timetable::formatTime).toList());
            case NONE -> "brak rozkładu";
        };
    }
}
