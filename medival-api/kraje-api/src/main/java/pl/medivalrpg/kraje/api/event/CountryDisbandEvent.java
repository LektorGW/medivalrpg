// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A country was disbanded or deleted. Fired on the main thread after the change is applied. */
public final class CountryDisbandEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID countryId;
    private final String name;
    private final boolean npc;

    public CountryDisbandEvent(final UUID countryId, final String name, final boolean npc) {
        this.countryId = countryId;
        this.name = name;
        this.npc = npc;
    }

    public UUID countryId() {
        return this.countryId;
    }

    public String name() {
        return this.name;
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
