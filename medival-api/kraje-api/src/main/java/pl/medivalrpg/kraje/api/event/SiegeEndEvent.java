// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A siege ended; {@code conquered} = chunk moved to the attacker. Fired on the main thread after the change is applied. */
public final class SiegeEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID siegeId;
    private final UUID attacker;
    private final UUID defender;
    private final String world;
    private final int cx;
    private final int cz;
    private final boolean conquered;

    public SiegeEndEvent(final UUID siegeId, final UUID attacker, final UUID defender, final String world, final int cx, final int cz, final boolean conquered) {
        this.siegeId = siegeId;
        this.attacker = attacker;
        this.defender = defender;
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.conquered = conquered;
    }

    public UUID siegeId() {
        return this.siegeId;
    }

    public UUID attacker() {
        return this.attacker;
    }

    public UUID defender() {
        return this.defender;
    }

    public String world() {
        return this.world;
    }

    public int cx() {
        return this.cx;
    }

    public int cz() {
        return this.cz;
    }

    public boolean conquered() {
        return this.conquered;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
