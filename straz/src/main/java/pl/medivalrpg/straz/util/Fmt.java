// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.util;

import java.util.Locale;

/** Polish number formatting for reports (pure). Money is a long amount of copper coins (md); 100 md = 1 zł. */
public final class Fmt {

    private Fmt() {
    }

    /** {@code 100000000 → "100 000 000"} (plain space as the thousands separator, minus sign "−"). */
    public static String groups(final long value) {
        final String digits = Long.toString(Math.abs(value));
        final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        final int head = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - head) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "−" : "") + out;
    }

    /** {@code 512587 → "512 587 md"}. */
    public static String md(final long value) {
        return groups(value) + " md";
    }

    /** {@code 512587 → "512 587 md (5 125,87 zł)"}; whole złote without the fraction. */
    public static String mdZl(final long value) {
        final long abs = Math.abs(value);
        final long zl = abs / 100;
        final long rest = abs % 100;
        final String zloty = (value < 0 ? "−" : "") + groups(zl) + (rest == 0 ? "" : String.format(Locale.ROOT, ",%02d", rest)) + " zł";
        return md(value) + " (" + zloty + ")";
    }

    /** Signed amount: {@code +12 300} / {@code −15 800} / {@code 0}. */
    public static String signed(final long value) {
        return value > 0 ? "+" + groups(value) : groups(value);
    }

    /** One decimal with a Polish comma: {@code 19.84 → "19,8"}. */
    public static String dec1(final double value) {
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }

    /** Percent of {@code part} in {@code whole}, rounded: {@code "23 %"}; {@code "—"} when whole is 0. */
    public static String percent(final long part, final long whole) {
        if (whole <= 0) {
            return "—";
        }
        return Math.round(part * 100.0 / whole) + " %";
    }

    /** Bytes as {@code "8,4 MB"} / {@code "512 kB"} / {@code "900 B"} (decimal units). */
    public static String bytes(final long bytes) {
        if (bytes >= 1_000_000_000L) {
            return dec1(bytes / 1e9) + " GB";
        }
        if (bytes >= 1_000_000L) {
            return dec1(bytes / 1e6) + " MB";
        }
        if (bytes >= 1_000L) {
            return Math.round(bytes / 1e3) + " kB";
        }
        return bytes + " B";
    }

    /**
     * Polish plural: {@code plural(1,"błąd","błędy","błędów") → "błąd"}, 2–4 (but not 12–14) → second, else third.
     */
    public static String plural(final long n, final String one, final String few, final String many) {
        final long a = Math.abs(n);
        if (a == 1) {
            return one;
        }
        final long d = a % 10;
        final long t = a % 100;
        return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
    }

    /** {@code "5 błędów"}. */
    public static String count(final long n, final String one, final String few, final String many) {
        return groups(n) + " " + plural(n, one, few, many);
    }

    /** Minutes as {@code "2 h 05 min"} / {@code "45 min"}. */
    public static String minutes(final long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + String.format(Locale.ROOT, "%02d", minutes % 60) + " min";
    }

    /** Shortens text to {@code max} characters with an ellipsis. */
    public static String clip(final String text, final int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
