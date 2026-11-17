// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A vassalage ended. Fired on the main thread after the change is applied (for {@link Reason#FALL} and
 * {@link Reason#DISBANDED} before the {@link CountryDisbandEvent} of the country that disappears).
 */
public final class VassalageEndEvent extends Event {

    /** Why the vassalage ended. */
    public enum Reason {
        /** The agreed time ran out. */
        EXPIRED,
        /** The vassal bought its freedom ({@code /kraj wykup}). */
        BOUGHT_OUT,
        /** The overlord released the vassal ({@code /kraj zwolnij}). */
        RELEASED,
        /** The vassal or the overlord fell (last chunk conquered). */
        FALL,
        /** The vassal or the overlord was disbanded or deleted without a war. */
        DISBANDED,
        /** The two countries went to war (e.g. the overlord punished unpaid tribute, or an admin/AI set war). */
        WAR
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID vassal;
    private final UUID overlord;
    private final Reason reason;

    public VassalageEndEvent(final UUID vassal, final UUID overlord, final Reason reason) {
        this.vassal = vassal;
        this.overlord = overlord;
        this.reason = reason;
    }

    public UUID vassal() {
        return this.vassal;
    }

    public UUID overlord() {
        return this.overlord;
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
