// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.money;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Formatting and parsing of amounts (pure Java, no Bukkit).
 *
 * <p>Parsing accepts any combination of {@code <number><unit>} parts separated by spaces, commas or "i"
 * ({@code "3zl"}, {@code "1zł 5sr"}, {@code "2 sr 3 md"}, {@code "1 korona, 2 srebrniki i 5 miedziaków"}),
 * every unit at most once, plus one bare number meaning copper ({@code "150"}). Case and Polish diacritics are
 * ignored. Negative numbers, decimals, unknown units and overflow are rejected.</p>
 */
public final class Money {

    private static final Pattern PART = Pattern.compile("\\G\\s*(?:,|\\+|\\bi\\b)?\\s*(\\d{1,19})\\s*(?:([a-z]+)\\.?)?");

    private Money() {
    }

    /** {@code "3 zł 4 sr 5 md"}; zero parts omitted, {@code "0 md"} for zero, leading {@code "-"} when negative. */
    public static String format(final long amount) {
        if (amount == 0) {
            return "0 md";
        }
        final boolean negative = amount < 0;
        // Unsigned arithmetic so that Long.MIN_VALUE (whose negation overflows) still formats correctly.
        final long abs = negative ? -amount : amount;
        final long gold = Long.divideUnsigned(abs, SkarbiecApi.COPPER_PER_GOLD);
        final long rest = Long.remainderUnsigned(abs, SkarbiecApi.COPPER_PER_GOLD);
        final long silver = rest / SkarbiecApi.COPPER_PER_SILVER;
        final long copper = rest % SkarbiecApi.COPPER_PER_SILVER;
        final StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        append(sb, gold, "zł");
        append(sb, silver, "sr");
        append(sb, copper, "md");
        return sb.toString();
    }

    private static void append(final StringBuilder sb, final long value, final String unit) {
        if (value == 0) {
            return;
        }
        if (sb.length() > 1 || (sb.length() == 1 && sb.charAt(0) != '-')) {
            sb.append(' ');
        }
        sb.append(value).append(' ').append(unit);
    }

    /** See class javadoc. Empty when invalid or negative; {@code "0"} gives 0. */
    public static Optional<Long> parse(final String input) {
        if (input == null) {
            return Optional.empty();
        }
        final String text = normalize(input).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        final Matcher m = PART.matcher(text);
        final Set<Denomination> used = EnumSet.noneOf(Denomination.class);
        boolean bare = false;
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.end() == m.start()) {
                break;
            }
            end = m.end();
            final long number;
            try {
                number = Long.parseLong(m.group(1));
            } catch (final NumberFormatException e) {
                return Optional.empty();
            }
            final String unit = m.group(2);
            final Denomination d;
            if (unit == null) {
                if (bare) {
                    return Optional.empty();
                }
                bare = true;
                d = Denomination.COPPER;
            } else {
                d = unit(unit);
                if (d == null || !used.add(d)) {
                    return Optional.empty();
                }
            }
            try {
                total = Math.addExact(total, Math.multiplyExact(number, d.value()));
            } catch (final ArithmeticException e) {
                return Optional.empty();
            }
        }
        if (end != text.length() || (used.isEmpty() && !bare)) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    private static Denomination unit(final String unit) {
        return switch (unit) {
            case "md", "m", "mdz", "miedziak", "miedziaki", "miedziakow", "miedz" -> Denomination.COPPER;
            case "sr", "s", "srebrnik", "srebrniki", "srebrnikow", "srebro" -> Denomination.SILVER;
            case "zl", "z", "zk", "zlote", "zlotych", "zloty", "korona", "korony", "koron", "zlota", "zloto" -> Denomination.GOLD;
            default -> null;
        };
    }

    /** Lower case, Polish letters → ASCII (ł → l and the decomposable ones). */
    static String normalize(final String s) {
        final String lower = s.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
