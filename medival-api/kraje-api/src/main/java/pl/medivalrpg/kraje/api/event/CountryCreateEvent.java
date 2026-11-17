// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A country was founded (by a player, an admin or as an NPC country). Fired on the main thread after the change is applied. */
public final class CountryCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID countryId;
    private final boolean npc;

    public CountryCreateEvent(final UUID countryId, final boolean npc) {
        this.countryId = countryId;
        this.npc = npc;
    }

    public UUID countryId() {
        return this.countryId;
    }

    public boolean npc() {
        return this.npc;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
