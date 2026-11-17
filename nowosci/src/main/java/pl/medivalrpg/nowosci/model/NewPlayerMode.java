// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.util.Locale;

/** What a player joining the server for the very first time sees ({@code new-player} in config.yml). */
public enum NewPlayerMode {
    /** Short welcome with a button to {@code /nowosci}; the whole history is marked as read. */
    WELCOME,
    /** Only the newest entry; older ones are marked as read. */
    NEWEST,
    /** Like any other player: every entry is unread. */
    ALL,
    /** Nothing; everything is marked as read. */
    NONE;

    public static NewPlayerMode parse(final String value, final NewPlayerMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return fallback;
        }
    }
}
