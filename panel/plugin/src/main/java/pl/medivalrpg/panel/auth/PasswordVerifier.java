// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

/**
 * Checks a server password (Straznik, fallback nLogin). Implementations may block; they are called off the main
 * thread.
 */
public interface PasswordVerifier {

    /** Result of a check. */
    enum Result { OK, WRONG, NOT_REGISTERED, UNAVAILABLE }

    Result verify(String name, String password);

    /** Nick with the registered letter case (offline UUIDs depend on it); the typed name when unknown. */
    default String canonicalName(final String name) {
        return name;
    }

    /** Used when no login plugin is installed. */
    PasswordVerifier NONE = (name, password) -> Result.UNAVAILABLE;
}
