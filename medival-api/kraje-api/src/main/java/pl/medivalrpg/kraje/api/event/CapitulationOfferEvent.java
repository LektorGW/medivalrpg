// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Country {@code from} (losing a war) offered to capitulate to {@code to}: on acceptance the war ends and
 * {@code from} becomes the vassal of {@code to} for {@code days} days, paying a daily tribute.
 *
 * <p>Fired on the main thread for every offer, after it was stored. The NPC AI listens to it for NPC targets
 * ({@link #toNpc()}) and answers with {@code KrajeApi#respondCapitulation(to, from, accept)} (immediately or later,
 * while the offer is valid). Offers to player countries are answered by their ruler with a clickable prompt.</p>
 */
public final class CapitulationOfferEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID from;
    private final UUID to;
    private final int days;
    private final double tributeShare;
    private final long tributeMin;
    private final long estimatedTribute;
    private final boolean toNpc;
    private final long expiresAt;

    public CapitulationOfferEvent(final UUID from, final UUID to, final int days, final double tributeShare,
                                  final long tributeMin, final long estimatedTribute, final boolean toNpc, final long expiresAt) {
        this.from = from;
        this.to = to;
        this.days = days;
        this.tributeShare = tributeShare;
        this.tributeMin = tributeMin;
        this.estimatedTribute = estimatedTribute;
        this.toNpc = toNpc;
        this.expiresAt = expiresAt;
    }

    /** The country that capitulates (future vassal). */
    public UUID from() {
        return this.from;
    }

    /** The country the offer is made to (future overlord). */
    public UUID to() {
        return this.to;
    }

    /** Length of the vassalage in days. */
    public int days() {
        return this.days;
    }

    /** Share of the vassal's daily tax income paid as tribute (0..1). */
    public double tributeShare() {
        return this.tributeShare;
    }

    /** Minimum daily tribute in copper. */
    public long tributeMin() {
        return this.tributeMin;
    }

    /** Daily tribute expected from the vassal's current taxes, copper (0 without Skarbiec). */
    public long estimatedTribute() {
        return this.estimatedTribute;
    }

    /** Whether {@link #to()} is an NPC country (then the AI has to answer). */
    public boolean toNpc() {
        return this.toNpc;
    }

    /** Epoch millis after which the offer can no longer be accepted. */
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
