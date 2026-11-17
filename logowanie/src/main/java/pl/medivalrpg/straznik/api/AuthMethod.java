// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.api;

/** How a player got authenticated. Names are stable. */
public enum AuthMethod {
    /** {@code /login} with the password. */
    LOGIN,
    /** {@code /register}: new account. */
    REGISTER,
    /** Same address within the session time after the last logout. */
    SESSION
}
