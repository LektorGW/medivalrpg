// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

/** Result of a company operation: ok or a Polish reason (MiniMessage-safe plain text). */
public record Outcome(boolean ok, String message, String id) {

    public static Outcome ok(final String message) {
        return new Outcome(true, message, null);
    }

    public static Outcome ok(final String message, final String id) {
        return new Outcome(true, message, id);
    }

    public static Outcome fail(final String message) {
        return new Outcome(false, message, null);
    }
}
