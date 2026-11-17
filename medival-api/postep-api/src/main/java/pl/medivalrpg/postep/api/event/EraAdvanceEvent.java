// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A country entered a new era (fired on the main thread after the change is saved). */
public final class EraAdvanceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID countryId;
    private final int oldEra;
    private final int newEra;

    public EraAdvanceEvent(final UUID countryId, final int oldEra, final int newEra) {
        this.countryId = countryId;
        this.oldEra = oldEra;
        this.newEra = newEra;
    }

    public UUID countryId() {
        return this.countryId;
    }

    public int oldEra() {
        return this.oldEra;
    }

    public int newEra() {
        return this.newEra;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
