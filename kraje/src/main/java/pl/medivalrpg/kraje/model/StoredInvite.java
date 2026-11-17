// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.UUID;

/**
 * A long-lived invitation that survives restarts and works for offline players (e.g. citizens of a fallen country
 * invited to the conqueror). Shown when the player joins; accepted with {@code /kraj dolacz}.
 *
 * @param player     invited player
 * @param playerName last known name (for logs / panels)
 * @param country    inviting country
 * @param createdAt  epoch millis
 * @param expiresAt  epoch millis
 * @param reason     why it exists, e.g. {@code conquest}
 */
public record StoredInvite(UUID player, String playerName, UUID country, long createdAt, long expiresAt, String reason) {

    public static final String CONQUEST = "conquest";

    public boolean valid(final long now) {
        return now < this.expiresAt;
    }
}
