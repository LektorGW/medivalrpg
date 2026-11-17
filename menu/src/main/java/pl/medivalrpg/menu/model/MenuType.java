// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.Locale;

/** Kind of menu: plain buttons, or buttons plus generated content in {@code content-slots}. */
public enum MenuType {
    /** Only the buttons from {@code menus.yml}. */
    STATIC,
    /** Buttons plus one icon per interaction read at runtime from the Interakcje plugin. */
    INTERACTIONS,
    /**
     * Buttons plus one banner per country read at runtime from Kraje ({@code KrajeApi.getCountries()}), paged
     * by {@code page:prev} / {@code page:next} buttons; a click runs {@code /kraj admin tp <kraj>}.
     */
    COUNTRIES,
    /**
     * Buttons plus one icon per dance and emote read at runtime from the Interakcje plugin ({@code dances.list},
     * {@code emotes.list}); a click runs {@code /taniec <nr>} / {@code /emotka <nazwa>}, right click on an emote
     * with an optional target picks a player nearby.
     */
    MOTIONS;

    /** @throws IllegalArgumentException for unknown values */
    public static MenuType parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return STATIC;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "static", "zwykle" -> STATIC;
            case "interactions", "interakcje" -> INTERACTIONS;
            case "countries", "panstwa", "kraje" -> COUNTRIES;
            case "motions", "ruchy", "emotki" -> MOTIONS;
            default -> throw new IllegalArgumentException("nieznany typ menu '" + raw
                + "' (static, interactions, countries, motions)");
        };
    }
}
