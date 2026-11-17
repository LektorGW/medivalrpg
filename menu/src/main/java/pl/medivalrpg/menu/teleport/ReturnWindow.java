// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Return to your last position" offers: opened when a returning player is sent to the spawn on join, valid for
 * {@code spawn.return-window-minutes}, usable once. Pure; main thread only.
 */
public final class ReturnWindow {

    private record Offer(StoredLocation target, long expiresAt) {
    }

    private final Map<UUID, Offer> offers = new HashMap<>();

    public void open(final UUID player, final StoredLocation target, final long nowMs, final int minutes) {
        if (minutes <= 0) {
            this.offers.remove(player);
            return;
        }
        this.offers.put(player, new Offer(target, nowMs + minutes * 60_000L));
    }

    /** The target while the window is open and unused. */
    public Optional<StoredLocation> get(final UUID player, final long nowMs) {
        final Offer o = this.offers.get(player);
        if (o == null) {
            return Optional.empty();
        }
        if (nowMs >= o.expiresAt()) {
            this.offers.remove(player);
            return Optional.empty();
        }
        return Optional.of(o.target());
    }

    public boolean canReturn(final UUID player, final long nowMs) {
        return this.get(player, nowMs).isPresent();
    }

    /** Milliseconds left, 0 when closed. */
    public long remainingMs(final UUID player, final long nowMs) {
        final Offer o = this.offers.get(player);
        return o == null ? 0 : Math.max(0, o.expiresAt() - nowMs);
    }

    /** One use: after a successful return (or on quit) the offer is gone. */
    public void consume(final UUID player) {
        this.offers.remove(player);
    }

    /**
     * Should the position at quit be remembered? Not when the player is still at the spawn (same world and within
     * {@code keepRadius} blocks horizontally of it) – otherwise joining, looking around and leaving would replace the
     * real last position with the spawn.
     */
    public static boolean worthRemembering(final StoredLocation at, final StoredLocation spawn, final double keepRadius) {
        if (at == null) {
            return false;
        }
        if (spawn == null || !spawn.world().equals(at.world())) {
            return true;
        }
        final double dx = at.x() - spawn.x();
        final double dz = at.z() - spawn.z();
        return dx * dx + dz * dz > keepRadius * keepRadius;
    }
}
