// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * {@code from} proposed a personal union to {@code to}: the ruler of {@code from} would become the shared ruler of
 * both countries. For an NPC {@code to} the AI may answer with {@code KrajeApi#respondUnion(to, from, accept)} before
 * {@code expiresAt} — only when Kraje is configured to wait for the AI ({@code union.npc-auto-refuse: false});
 * by default Kraje refuses unions with NPC countries at once. Fired on the main thread. Since API 1.5.
 */
public final class UnionProposalEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID from;
    private final UUID to;
    private final boolean toNpc;
    private final long expiresAt;

    public UnionProposalEvent(final UUID from, final UUID to, final boolean toNpc, final long expiresAt) {
        this.from = from;
        this.to = to;
        this.toNpc = toNpc;
        this.expiresAt = expiresAt;
    }

    /** Proposing country (its ruler would rule both). */
    public UUID from() {
        return this.from;
    }

    public UUID to() {
        return this.to;
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
