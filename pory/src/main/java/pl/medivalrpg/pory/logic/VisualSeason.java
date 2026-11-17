// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.text.Normalizer;
import java.util.Locale;

/** The four seasons as shown in the world. Order and ids match the Ludnosc season clock (index 0 = spring). */
public enum VisualSeason {
    WIOSNA("wiosna", "Wiosna"),
    LATO("lato", "Lato"),
    JESIEN("jesien", "Jesień"),
    ZIMA("zima", "Zima");

    private final String id;
    private final String displayName;

    VisualSeason(final String id, final String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Id without Polish letters, also the datapack folder of the variant biomes ({@code pory:jesien/plains}). */
    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public VisualSeason next() {
        return values()[(this.ordinal() + 1) % 4];
    }

    /** Season by Ludnosc index (0 spring … 3 winter); null when out of range. */
    public static VisualSeason byIndex(final int index) {
        return index < 0 || index > 3 ? null : values()[index];
    }

    /** {@code jesień}, {@code JESIEN}, {@code autumn}… → season; null when unknown. */
    public static VisualSeason parse(final String text) {
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
