// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Colour parsing: {@code #RRGGBB}, {@code RRGGBB}, Polish colour names (with or without diacritics)
 * and Minecraft colour names.
 */
public final class Colors {

    private static final Map<String, Integer> POLISH = new LinkedHashMap<>();

    static {
        POLISH.put("czerwony", 0xE53935);
        POLISH.put("bordowy", 0x8E1B2B);
        POLISH.put("pomaranczowy", 0xF57C00);
        POLISH.put("zolty", 0xFDD835);
        POLISH.put("zloty", 0xD4A017);
        POLISH.put("limonkowy", 0x9CCC65);
        POLISH.put("zielony", 0x43A047);
        POLISH.put("ciemnozielony", 0x1B5E20);
        POLISH.put("turkusowy", 0x26A69A);
        POLISH.put("blekitny", 0x4FC3F7);
        POLISH.put("niebieski", 0x1E88E5);
        POLISH.put("granatowy", 0x1A237E);
        POLISH.put("fioletowy", 0x8E24AA);
        POLISH.put("purpurowy", 0x6A1B9A);
        POLISH.put("rozowy", 0xF06292);
        POLISH.put("brazowy", 0x795548);
        POLISH.put("bezowy", 0xD7CCC8);
        POLISH.put("bialy", 0xF5F5F5);
        POLISH.put("szary", 0x9E9E9E);
        POLISH.put("grafitowy", 0x424242);
        POLISH.put("czarny", 0x212121);
    }

    private Colors() {
    }

    /** Parses {@code #RRGGBB} or {@code RRGGBB}; returns {@code null} if invalid. */
    public static Integer parseHex(final String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(s, 16);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Parses a hex value or a colour name; returns {@code null} if unknown. */
    public static Integer parse(final String input) {
        final Integer hex = parseHex(input);
        if (hex != null) {
            return hex;
        }
        final String normalized = normalize(input);
        final Integer polish = POLISH.get(normalized);
        if (polish != null) {
            return polish;
        }
        final NamedTextColor named = NamedTextColor.NAMES.value(normalized);
        return named == null ? null : named.value();
    }

    /** Names offered in tab completion. */
    public static Set<String> names() {
        return Collections.unmodifiableSet(POLISH.keySet());
    }

    /** Lower-case, strips Polish diacritics. */
    public static String normalize(final String input) {
        final String lower = input.trim().toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
