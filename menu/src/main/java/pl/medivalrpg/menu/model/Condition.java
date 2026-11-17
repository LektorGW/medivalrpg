// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.Locale;

/** Extra visibility condition of a button ({@code condition:} in {@code menus.yml}). */
public enum Condition {
    /** Always visible (plugin and permission checks still apply). */
    NONE,
    /** Only when the player belongs to a country (Kraje). */
    HAS_COUNTRY,
    /** Only when the player has no country (or Kraje is missing). */
    NO_COUNTRY,
    /** Only while the one-time {@code /powrot} after joining is available. */
    CAN_RETURN;

    /** @throws IllegalArgumentException for unknown values */
    public static Condition parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none", "brak" -> NONE;
            case "has-country", "ma-kraj" -> HAS_COUNTRY;
            case "no-country", "bez-kraju" -> NO_COUNTRY;
            case "can-return", "moze-wrocic" -> CAN_RETURN;
            default -> throw new IllegalArgumentException("nieznany warunek '" + raw
                + "' (has-country, no-country, can-return)");
        };
    }
}
