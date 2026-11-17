// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback money formatting / parsing in copper (1 sr = 10 md, 1 zł = 100 md), used only when Skarbiec is
 * not installed (Skarbiec's own {@code format} / {@code parse} are used otherwise).
 */
public final class Money {

    public static final long COPPER_PER_SILVER = 10;
    public static final long COPPER_PER_GOLD = 100;
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(zł|zl|sr|md)?");

    private Money() {
    }

    /** {@code "3 zł 4 sr 5 md"}, zero parts omitted, {@code "0 md"} for zero. */
    public static String format(final long amount) {
        final long abs = Math.abs(amount);
        final long gold = abs / COPPER_PER_GOLD;
        final long silver = abs % COPPER_PER_GOLD / COPPER_PER_SILVER;
        final long copper = abs % COPPER_PER_SILVER;
        final StringBuilder sb = new StringBuilder();
        if (gold > 0) {
            sb.append(gold).append(" zł");
        }
        if (silver > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append(silver).append(" sr");
        }
        if (copper > 0 || sb.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : " ").append(copper).append(" md");
        }
        return (amount < 0 ? "-" : "") + sb;
    }

    /** {@code "150"}, {@code "3zl"}, {@code "1zł 5sr"}, {@code "2 sr 3 md"}; empty when invalid or not positive. */
    public static Optional<Long> parse(final String input) {
        if (input == null) {
            return Optional.empty();
        }
        final String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        final Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (!s.substring(end, m.start()).isBlank()) {
                return Optional.empty();
            }
            final long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (final NumberFormatException e) {
                return Optional.empty();
            }
            final String unit = m.group(2);
            final long mult = unit == null || unit.equals("md") ? 1 : unit.equals("sr") ? COPPER_PER_SILVER : COPPER_PER_GOLD;
            total = Math.addExact(total, Math.multiplyExact(value, mult));
            end = m.end();
        }
        if (end == 0 || !s.substring(end).isBlank() || total <= 0) {
            return Optional.empty();
        }
        return Optional.of(total);
    }
}
