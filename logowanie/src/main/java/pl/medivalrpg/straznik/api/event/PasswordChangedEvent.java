// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * The password of an account was changed or the account was removed (other services should end their sessions,
 * e.g. the web panel). Called on the main thread. Never carries the password or the hash.
 */
public final class PasswordChangedEvent extends Event {

    /** Why the event was called. */
    public enum Reason { CHANGED_BY_PLAYER, CHANGED_BY_ADMIN, UNREGISTERED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final Reason reason;

    public PasswordChangedEvent(final UUID playerId, final String playerName, final Reason reason) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.reason = reason;
    }

    public UUID getPlayerId() {
        return this.playerId;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public Reason getReason() {
        return this.reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
