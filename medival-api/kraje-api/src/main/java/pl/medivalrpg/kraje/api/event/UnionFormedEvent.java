// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A personal union was formed: {@code monarch} (ruler of {@code home}) now also rules {@code partner}; the two are
 * allied. Fired on the main thread after the change is applied. Since API 1.5.
 */
public final class UnionFormedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID home;
    private final UUID partner;
    private final UUID monarch;

    public UnionFormedEvent(final UUID home, final UUID partner, final UUID monarch) {
        this.home = home;
        this.partner = partner;
        this.monarch = monarch;
    }

    public UUID home() {
        return this.home;
    }

    public UUID partner() {
        return this.partner;
    }

    public UUID monarch() {
        return this.monarch;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
