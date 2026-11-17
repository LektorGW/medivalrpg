// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A capitulation was accepted: the war between the two countries ended and {@code vassal} is now the vassal of
 * {@code overlord} until {@code until}. Fired on the main thread after the change is applied.
 */
public final class VassalageStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID vassal;
    private final UUID overlord;
    private final long until;
    private final double tributeShare;
    private final long tributeMin;

    public VassalageStartEvent(final UUID vassal, final UUID overlord, final long until, final double tributeShare, final long tributeMin) {
        this.vassal = vassal;
        this.overlord = overlord;
        this.until = until;
        this.tributeShare = tributeShare;
        this.tributeMin = tributeMin;
    }

    public UUID vassal() {
        return this.vassal;
    }

    public UUID overlord() {
        return this.overlord;
    }

    /** Epoch millis when the vassalage ends by itself. */
    public long until() {
        return this.until;
    }

    public double tributeShare() {
        return this.tributeShare;
    }

    public long tributeMin() {
        return this.tributeMin;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
