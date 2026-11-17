// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.Locale;

/** How the space between the lowest template layer and uneven terrain is filled. */
public enum FoundationMode {
    /** Every column under the floor is filled down to solid ground (stone plinth). */
    FILL,
    /** Only corner and every third edge column (stilts / posts). */
    PILLARS,
    /** Nothing (floating if the terrain is lower). */
    NONE;

    public static FoundationMode parse(final String value) {
        if (value == null || value.isBlank()) {
            return FILL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Nieznany typ fundamentu: " + value + " (fill, pillars, none)");
        }
    }
}
