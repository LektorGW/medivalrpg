// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** How news are shown after login ({@code show.mode} in config.yml, any combination). */
public enum ShowMode {
    /** Title cards in the middle of the screen. */
    TITLE,
    /** The {@code /nowosci} book opens by itself (after the title cards). */
    BOOK,
    /** The compact block on the chat. */
    CHAT;

    /**
     * Parses a list of names (case-insensitive); unknown names are reported to {@code unknown}.
     * An empty list means "show nothing".
     */
    public static Set<ShowMode> parse(final Collection<String> names, final List<String> unknown) {
        final Set<ShowMode> modes = EnumSet.noneOf(ShowMode.class);
        for (final String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                modes.add(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (final IllegalArgumentException e) {
                unknown.add(raw.trim());
            }
        }
        return modes;
    }
}
