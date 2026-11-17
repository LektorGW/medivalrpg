// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Company names: allowed characters, length, normalization for uniqueness and the word filter. Pure. */
public final class Names {

    /** Letters (also Polish), digits, space and a few punctuation marks. */
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}0-9 '&.\\-]+");

    private Names() {
    }

    /** Lower case, without diacritics, single spaces; used for uniqueness and the word filter. */
    public static String normalize(final String s) {
        if (s == null) {
            return "";
        }
        final String lower = s.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("\\s+", " ").strip();
    }

    /**
     * Free text of players (contract titles, dispute reasons, item names in logs) made safe inside MiniMessage: tag
     * brackets become ‹ › (the same escaping as the chat output of the commands).
     */
    public static String plain(final String s) {
        return s == null ? "" : s.replace("<", "‹").replace(">", "›");
    }

    /** Name as stored (trimmed, single spaces). */
    public static String clean(final String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    /** Empty when valid, else a Polish reason. */
    public static Optional<String> problem(final String raw, final Settings s) {
        final String name = clean(raw);
        if (name.length() < s.nameMin || name.length() > s.nameMax) {
            return Optional.of("Nazwa firmy musi mieć " + s.nameMin + "–" + s.nameMax + " znaków.");
        }
        if (!ALLOWED.matcher(name).matches()) {
            return Optional.of("Nazwa może zawierać litery, cyfry, spacje i znaki ' & . -");
        }
        if (!Character.isLetter(name.codePointAt(0))) {
            return Optional.of("Nazwa musi zaczynać się literą.");
        }
        final String n = normalize(name);
        final String squeezed = n.replaceAll("[^a-z0-9]", "");
        for (final String banned : s.bannedWords) {
            final String b = normalize(banned);
            if (!b.isEmpty() && (n.contains(b) || squeezed.contains(b.replaceAll("[^a-z0-9]", "")))) {
                return Optional.of("Ta nazwa jest zastrzeżona albo niedozwolona.");
            }
        }
        return Optional.empty();
    }
}
