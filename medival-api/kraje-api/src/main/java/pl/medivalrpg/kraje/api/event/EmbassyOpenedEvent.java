// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * {@code guest} opened an embassy on chunk ({@code chunkX}, {@code chunkZ}) of {@code host}. Fired on the main thread
 * after the change is applied. Since API 1.5.
 */
public final class EmbassyOpenedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID guest;
    private final UUID host;
    private final String world;
    private final int chunkX;
    private final int chunkZ;

    public EmbassyOpenedEvent(final UUID guest, final UUID host, final String world, final int chunkX, final int chunkZ) {
        this.guest = guest;
        this.host = host;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public UUID guest() {
        return this.guest;
    }

    public UUID host() {
        return this.host;
    }

    public String world() {
        return this.world;
    }

    public int chunkX() {
        return this.chunkX;
    }

    public int chunkZ() {
        return this.chunkZ;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
