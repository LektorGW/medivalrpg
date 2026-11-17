// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import pl.medivalrpg.straznik.api.AuthMethod;

/**
 * The player is logged in: called once per connection on the main thread, AFTER Straznik has given back the
 * player's real position and inventory. Not cancellable.
 */
public final class PlayerAuthenticatedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AuthMethod method;

    public PlayerAuthenticatedEvent(final Player player, final AuthMethod method) {
        super(player);
        this.method = method;
    }

    public AuthMethod getMethod() {
        return this.method;
    }

    /** {@link AuthMethod#name()} for consumers using reflection. */
    public String getMethodName() {
        return this.method.name();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
