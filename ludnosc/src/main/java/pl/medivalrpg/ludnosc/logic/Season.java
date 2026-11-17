// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.text.Normalizer;
import java.util.Locale;

/** The four seasons of the server-wide cycle (pure). */
public enum Season {
    WIOSNA("wiosna", "Wiosna"),
    LATO("lato", "Lato"),
    JESIEN("jesien", "Jesień"),
    ZIMA("zima", "Zima");

    private final String id;
    private final String displayName;

    Season(final String id, final String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Config / command id without Polish letters ({@code jesien}). */
    public String id() {
        return this.id;
    }

    /** Polish name ({@code Jesień}). */
    public String displayName() {
        return this.displayName;
    }

    public Season next() {
        return values()[(this.ordinal() + 1) % 4];
    }

    /** Season {@code steps} after this one (negative = before). */
    public Season plus(final long steps) {
        return values()[(int) Math.floorMod(this.ordinal() + steps, 4L)];
    }

    /** {@code jesień}, {@code JESIEN}, {@code autumn}... → season; null when unknown. */
    public static Season parse(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String plain = Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replace('ł', 'l');
        return switch (plain) {
            case "wiosna", "spring" -> WIOSNA;
            case "lato", "summer" -> LATO;
            case "jesien", "autumn", "fall" -> JESIEN;
            case "zima", "winter" -> ZIMA;
            default -> null;
        };
    }
}
