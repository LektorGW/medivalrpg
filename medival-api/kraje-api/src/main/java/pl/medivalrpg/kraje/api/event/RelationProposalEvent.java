// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.medivalrpg.kraje.model.Relation;

/** Country {@code from} proposed {@code proposed} (ALLIANCE or NEUTRAL = peace) to {@code to}. The NPC AI listens to this for NPC targets and answers via {@code KrajeApi#respondToProposal}. Fired on the main thread after the change is applied. */
public final class RelationProposalEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID from;
    private final UUID to;
    private final Relation proposed;

    public RelationProposalEvent(final UUID from, final UUID to, final Relation proposed) {
        this.from = from;
        this.to = to;
        this.proposed = proposed;
    }

    public UUID from() {
        return this.from;
    }

    public UUID to() {
        return this.to;
    }

    public Relation proposed() {
        return this.proposed;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
