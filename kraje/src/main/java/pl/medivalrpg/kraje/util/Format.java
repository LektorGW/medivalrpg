// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Polish text formatting helpers.
 */
public final class Format {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private Format() {
    }

    /** Polish plural: 1 → one, 2–4 (not 12–14) → few, otherwise many. */
    public static String plural(final long n, final String one, final String few, final String many) {
        final long abs = Math.abs(n);
        if (abs == 1) {
            return one;
        }
        final long mod10 = abs % 10;
        final long mod100 = abs % 100;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }

    /** Groups thousands with a space: 6144 → "6 144". */
    public static String number(final long n) {
        final String digits = Long.toString(Math.abs(n));
        final StringBuilder sb = new StringBuilder();
        final int len = digits.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }

    /** "24 chunki • 6 144 bl²". */
    public static String area(final int chunks) {
        return number(chunks) + " " + plural(chunks, "chunk", "chunki", "chunków") + " • " + number(chunks * 256L) + " bl²";
    }

    public static String chunks(final int chunks) {
        return number(chunks) + " " + plural(chunks, "chunk", "chunki", "chunków");
    }

    /** Remaining time, e.g. {@code "1 h 05 min"}, {@code "12 min"}, {@code "40 s"} (rounded up to whole units). */
    public static String duration(final long millis) {
        final long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + " s";
        }
        final long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        final long hours = minutes / 60;
        final long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + String.format("%02d", rest) + " min";
    }

    /** Countdown {@code m:ss}. */
    public static String clock(final long millis) {
        final long seconds = Math.max(0, millis / 1000);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** {@code "25.10 18:00"} (server time zone). */
    public static String dateTime(final long epochMillis) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    public static String date(final long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** 0..1 → "52%" (rounded down, so 99.9 % is not shown as ready). */
    public static String percent(final double fraction) {
        final double f = Double.isNaN(fraction) ? 0.0 : Math.max(0.0, Math.min(1.0, fraction));
        return (int) Math.floor(f * 100.0 + 1e-9) + "%";
    }

    public static String html(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (final char ch : text.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
