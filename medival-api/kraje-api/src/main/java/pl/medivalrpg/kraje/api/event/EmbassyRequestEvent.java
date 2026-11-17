// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * {@code guest} asked {@code host} for permission to open an embassy on chunk ({@code chunkX}, {@code chunkZ}).
 * A player host's ruler gets a clickable prompt. For an NPC host Kraje decides by itself (config
 * {@code embassy.npc-auto-decide}); when that is off the AI answers with {@code KrajeApi#respondEmbassy(host, guest,
 * accept)} before {@code expiresAt}. Fired on the main thread. Since API 1.5.
 */
public final class EmbassyRequestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID guest;
    private final UUID host;
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final boolean toNpc;
    private final long expiresAt;

    public EmbassyRequestEvent(final UUID guest, final UUID host, final String world, final int chunkX, final int chunkZ,
                               final boolean toNpc, final long expiresAt) {
        this.guest = guest;
        this.host = host;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.toNpc = toNpc;
        this.expiresAt = expiresAt;
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

    public boolean toNpc() {
        return this.toNpc;
    }

    public long expiresAt() {
        return this.expiresAt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
