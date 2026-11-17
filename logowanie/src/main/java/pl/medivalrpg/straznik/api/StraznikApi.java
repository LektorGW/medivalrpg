// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Login plugin API, registered in Bukkit's {@code ServicesManager} by Straznik. Other MEDIVALRPG plugins reach it by
 * reflection (class name {@value #CLASS_NAME}), so they need no compile-time dependency.
 *
 * <p>All methods are thread-safe. {@link #verifyPassword} computes Argon2 and blocks for a fraction of a second:
 * never call it on the main thread.
 */
public interface StraznikApi {

    String CLASS_NAME = "pl.medivalrpg.straznik.api.StraznikApi";

    /** The online player has logged in (or registered) in this connection. Offline players: {@code false}. */
    boolean isAuthenticated(UUID player);

    /** An account exists for the nick (case-insensitive). */
    boolean isRegistered(String nick);

    /** Nick with the registered letter case, if registered. */
    Optional<String> registeredName(String nick);

    /** Offline UUID of the registered nick, if registered. */
    Optional<UUID> registeredUuid(String nick);

    /** Password check for other services (web panel). Unknown nick → {@link VerifyResult#NOT_REGISTERED}. */
    VerifyResult verifyPassword(String nick, String password);

    /** Seconds left to log in for a waiting player, 0 when logged in / offline. */
    int remainingSeconds(UUID player);
}
