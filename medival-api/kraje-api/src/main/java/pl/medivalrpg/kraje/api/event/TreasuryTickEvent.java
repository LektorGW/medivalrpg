// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Daily economy tick of one country finished (taxes collected, upkeep paid). Amounts in copper. Fired on the main thread after the change is applied. */
public final class TreasuryTickEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID countryId;
    private final long taxesCollected;
    private final long upkeepPaid;
    private final boolean bankrupt;

    public TreasuryTickEvent(final UUID countryId, final long taxesCollected, final long upkeepPaid, final boolean bankrupt) {
        this.countryId = countryId;
        this.taxesCollected = taxesCollected;
        this.upkeepPaid = upkeepPaid;
        this.bankrupt = bankrupt;
    }

    public UUID countryId() {
        return this.countryId;
    }

    public long taxesCollected() {
        return this.taxesCollected;
    }

    public long upkeepPaid() {
        return this.upkeepPaid;
    }

    public boolean bankrupt() {
        return this.bankrupt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
