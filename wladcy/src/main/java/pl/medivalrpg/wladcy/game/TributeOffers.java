// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Pending tribute demands (in memory; one per player). Main thread only. */
public final class TributeOffers {

    /** A demand the player may pay or refuse by clicking. */
    public record Offer(String id, UUID player, UUID npc, UUID payerCountry, long amount, long expiresAt) {
    }

    private final Map<String, Offer> offers = new HashMap<>();

    public Offer create(final UUID player, final UUID npc, final UUID payerCountry, final long amount, final long expiresAt) {
        this.offers.values().removeIf(o -> o.player().equals(player));
        String id;
        do {
            id = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36);
        } while (this.offers.containsKey(id));
        final Offer o = new Offer(id, player, npc, payerCountry, amount, expiresAt);
        this.offers.put(id, o);
        return o;
    }

    public boolean hasPending(final UUID player, final long now) {
        this.purge(now);
        return this.offers.values().stream().anyMatch(o -> o.player().equals(player));
    }

    /**
     * Removes and returns the offer when it belongs to {@code player} (a click is final whatever the outcome).
     * Someone else's or an expired id → empty, nothing removed.
     */
    public Optional<Offer> take(final String id, final UUID player, final long now) {
        this.purge(now);
        final Offer o = this.offers.get(id);
        if (o == null || !o.player().equals(player)) {
            return Optional.empty();
        }
        this.offers.remove(id);
        return Optional.of(o);
    }

    private void purge(final long now) {
        for (final Iterator<Offer> it = this.offers.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresAt() < now) {
                it.remove();
            }
        }
    }
}
