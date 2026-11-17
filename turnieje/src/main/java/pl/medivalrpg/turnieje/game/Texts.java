// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small pure text helpers: Polish normalisation, durations, countdown thresholds. */
public final class Texts {

    private Texts() {
    }

    /** Lower case, Polish diacritics removed, trimmed. */
    public static String normalize(final String s) {
        final StringBuilder b = new StringBuilder();
        for (final char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(switch (ch) {
                case 'ą' -> 'a';
                case 'ć' -> 'c';
                case 'ę' -> 'e';
                case 'ł' -> 'l';
                case 'ń' -> 'n';
                case 'ó' -> 'o';
                case 'ś' -> 's';
                case 'ź', 'ż' -> 'z';
                default -> ch;
            });
        }
        return b.toString().trim();
    }

    /** {@code 3725} → {@code "1 h 2 min"}, {@code 125} → {@code "2 min 5 s"}, {@code 9} → {@code "9 s"}, days for long spans. */
    public static String duration(final long seconds) {
        long s = Math.max(0, seconds);
        final long days = s / 86_400;
        s %= 86_400;
        final long hours = s / 3600;
        s %= 3600;
        final long minutes = s / 60;
        final long secs = s % 60;
        if (days > 0) {
            return days + " d" + (hours > 0 ? " " + hours + " h" : "");
        }
        if (hours > 0) {
            return hours + " h" + (minutes > 0 ? " " + minutes + " min" : "");
        }
        if (minutes > 0) {
            return minutes + " min" + (secs > 0 ? " " + secs + " s" : "");
        }
        return secs + " s";
    }

    /** {@code 95} → {@code "1:35"}. */
    public static String clock(final long seconds) {
        final long s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /**
     * Announcement thresholds crossed when the remaining time went from {@code before} to {@code now} seconds
     * ({@code before > t >= now}), largest first. The first tick of a countdown uses {@code before = Long.MAX_VALUE}
     * only for thresholds equal to the full time; callers pass the real previous value.
     */
    public static List<Integer> crossed(final long before, final long now, final List<Integer> thresholdsSeconds) {
        final List<Integer> out = new ArrayList<>();
        for (final int t : thresholdsSeconds) {
            if (before > t && t >= now) {
                out.add(t);
            }
        }
        out.sort((x, y) -> Integer.compare(y, x));
        return out;
    }

    /** Valid arena id: {@code [a-z0-9_-]{1,24}}. */
    public static boolean validId(final String id) {
        return id != null && id.matches("[a-z0-9_-]{1,24}");
    }
}
