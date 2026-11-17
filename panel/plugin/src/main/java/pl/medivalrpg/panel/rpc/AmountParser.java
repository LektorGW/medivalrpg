// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money amounts from the panel, in copper (1 sr = 10 md, 1 zł = 100 md). Accepts a JSON integer (copper) or text:
 * {@code "150"}, {@code "3zł"}, {@code "3 zl 5 sr"}, {@code "1zł5sr2md"}, {@code "2,5 zł"} is NOT accepted (no fractions).
 * Signed amounts ({@code "-2zł"}, {@code "+50"}) only where allowed (admin adjustments). Pure, no Bukkit.
 */
public final class AmountParser {

    /** Hard upper bound: 10 000 000 zł. */
    public static final long MAX = 1_000_000_000L;

    private static final Pattern PART = Pattern.compile("(\\d{1,12})\\s*(zł|zl|z|sr|s|md|m)?");

    private AmountParser() {
    }

    /** Parses text; empty when invalid, zero, out of range, or signed while {@code signed} is false. */
    public static OptionalLong parse(final String input, final boolean signed) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(' ', ' ');
        if (s.isEmpty() || s.length() > 40) {
            return OptionalLong.empty();
        }
        int sign = 1;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            if (!signed) {
                return OptionalLong.empty();
            }
            sign = s.charAt(0) == '-' ? -1 : 1;
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        final Matcher m = PART.matcher(s);
        int pos = 0;
        long total = 0;
        boolean anyUnitless = false;
        int parts = 0;
        while (pos < s.length()) {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
            if (pos >= s.length()) {
                break;
            }
            if (!m.find(pos) || m.start() != pos) {
                return OptionalLong.empty();
            }
            final long value = Long.parseLong(m.group(1));
            final String unit = m.group(2);
            final long mult;
            if (unit == null) {
                if (anyUnitless) {
                    return OptionalLong.empty(); // "1 2"
                }
                anyUnitless = true;
                mult = 1;
            } else {
                mult = switch (unit) {
                    case "zł", "zl", "z" -> 100;
                    case "sr", "s" -> 10;
                    default -> 1;
                };
            }
            if (value > MAX / mult) {
                return OptionalLong.empty();
            }
            total += value * mult;
            if (total > MAX) {
                return OptionalLong.empty();
            }
            parts++;
            pos = m.end();
            // A number directly followed by a letter that is not a unit ("5x") was rejected by find(); a unit must
            // end at a boundary.
            if (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                return OptionalLong.empty();
            }
        }
        if (parts == 0 || total == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(sign * total);
    }

    /** Copper from a JSON number (must be an integer, non-zero, within range; negative only if {@code signed}). */
    public static OptionalLong ofNumber(final double value, final boolean signed) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
            return OptionalLong.empty();
        }
        if (value == 0 || Math.abs(value) > MAX || (value < 0 && !signed)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of((long) value);
    }

    /** Polish text, e.g. {@code "3 zł 4 sr 5 md"}, {@code "0 md"}; negative with a leading minus. */
    public static String format(final long amount) {
        if (amount == 0) {
            return "0 md";
        }
        final long a = Math.abs(amount);
        final java.util.List<String> parts = new java.util.ArrayList<>();
        if (a / 100 > 0) {
            parts.add(a / 100 + " zł");
        }
        if (a % 100 / 10 > 0) {
            parts.add(a % 100 / 10 + " sr");
        }
        if (a % 10 > 0) {
            parts.add(a % 10 + " md");
        }
        return (amount < 0 ? "-" : "") + String.join(" ", parts);
    }
}
