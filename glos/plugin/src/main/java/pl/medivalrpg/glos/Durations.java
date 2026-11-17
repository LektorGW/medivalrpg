// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsowanie i formatowanie czasów w stylu {@code 10m}, {@code 2h}, {@code 1d}, {@code 1h30m}. */
public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");

    private Durations() {
    }

    /** @return liczba milisekund albo -1, gdy tekst jest nieprawidłowy. */
    public static long parseMillis(String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return -1;
        }
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return -1;
            }
            end = m.end();
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            long unit = switch (m.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 0L;
            };
            if (n > Long.MAX_VALUE / 4 / unit) {
                return -1;
            }
            total += n * unit;
        }
        if (end != s.length() || total <= 0) {
            return -1;
        }
        return total;
    }

    /** Krótki zapis, np. {@code 1d 2h 5m}. */
    public static String format(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long d = seconds / 86_400;
        long h = (seconds % 86_400) / 3_600;
        long m = (seconds % 3_600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0 && h == 0) sb.append(s).append("s ");
        return sb.toString().trim();
    }
}
