// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * The embassy of {@code guest} in {@code host} closed. Fired on the main thread after the change is applied (for
 * {@link Reason#DISBANDED} before the {@link CountryDisbandEvent}). Since API 1.5.
 */
public final class EmbassyClosedEvent extends Event {

    /** Why the embassy closed. */
    public enum Reason {
        /** The guest closed it ({@code /kraj ambasada zamknij}). */
        CLOSED,
        /** The host expelled it and the notice period ran out ({@code /kraj ambasada wydal}). */
        EXPELLED,
        /** The two countries went to war. */
        WAR,
        /** The chunk is no longer host land (conquest, admin). */
        LAND_LOST,
        /** The guest or the host disappeared. */
        DISBANDED,
        /** An admin or another plugin closed it through the API. */
        ADMIN
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID guest;
    private final UUID host;
    private final Reason reason;

    public EmbassyClosedEvent(final UUID guest, final UUID host, final Reason reason) {
        this.guest = guest;
        this.host = host;
        this.reason = reason;
    }

    public UUID guest() {
        return this.guest;
    }

    public UUID host() {
        return this.host;
    }

    public Reason reason() {
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
