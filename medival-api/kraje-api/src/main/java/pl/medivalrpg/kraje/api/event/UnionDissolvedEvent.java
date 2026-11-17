// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A personal union ended. Fired on the main thread after the change is applied (for {@link Reason#FALL} and
 * {@link Reason#DISBANDED} before the {@link CountryDisbandEvent} of the country that disappears). Since API 1.5.
 */
public final class UnionDissolvedEvent extends Event {

    /** Why the union ended. */
    public enum Reason {
        /** The shared ruler broke it ({@code /kraj unia zerwij}). */
        BROKEN,
        /** The regent of the partner country seceded ({@code /kraj unia zerwij}). */
        SECEDED,
        /** The shared ruler is no longer ruler of one of the countries (transfer of power, admin). */
        RULER_CHANGED,
        /** The two countries went to war with each other (admin / AI). */
        WAR,
        /** One of the countries became a vassal. */
        VASSALAGE,
        /** A country of the union fell (last chunk conquered). */
        FALL,
        /** A country of the union was disbanded or deleted. */
        DISBANDED,
        /** An admin or another plugin dissolved it through the API. */
        ADMIN
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID home;
    private final UUID partner;
    private final UUID monarch;
    private final Reason reason;

    public UnionDissolvedEvent(final UUID home, final UUID partner, final UUID monarch, final Reason reason) {
        this.home = home;
        this.partner = partner;
        this.monarch = monarch;
        this.reason = reason;
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
