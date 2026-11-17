// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.medivalrpg.kraje.api.ClaimCause;

/** A chunk changed owner. {@code oldOwner}/{@code newOwner} are null for wilderness. Fired on the main thread after the change is applied. */
public final class ChunkOwnerChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID oldOwner;
    private final UUID newOwner;
    private final String world;
    private final int cx;
    private final int cz;
    private final ClaimCause cause;

    public ChunkOwnerChangeEvent(final UUID oldOwner, final UUID newOwner, final String world, final int cx, final int cz, final ClaimCause cause) {
        this.oldOwner = oldOwner;
        this.newOwner = newOwner;
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.cause = cause;
    }

    public UUID oldOwner() {
        return this.oldOwner;
    }

    public UUID newOwner() {
        return this.newOwner;
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

    public ClaimCause cause() {
        return this.cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
