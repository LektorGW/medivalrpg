// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import java.util.UUID;

/** State of one "holding hands" session; the end-condition logic is pure (unit-tested). */
public final class HoldSession {

    public enum EndReason {
        TIMEOUT, DISTANCE, QUIT, RELEASE, SHUTDOWN
    }

    private final UUID first;
    private final UUID second;
    private final String interactionId;
    private final long endsAt;
    private final double maxDistance;

    public HoldSession(UUID first, UUID second, String interactionId, long startedAt, long durationMs,
                       double maxDistance) {
        this.first = first;
        this.second = second;
        this.interactionId = interactionId;
        this.endsAt = startedAt + durationMs;
        this.maxDistance = maxDistance;
    }

    /**
     * @param distance distance between the players, or {@code NaN} when they are in different worlds
     * @return why the session must end now, or {@code null} when it continues
     */
    public EndReason check(long now, boolean bothOnline, double distance) {
        if (!bothOnline) {
            return EndReason.QUIT;
        }
        if (Double.isNaN(distance) || distance > maxDistance) {
            return EndReason.DISTANCE;
        }
        if (now >= endsAt) {
            return EndReason.TIMEOUT;
        }
        return null;
    }

    public boolean involves(UUID player) {
        return first.equals(player) || second.equals(player);
    }

    public UUID other(UUID player) {
        return first.equals(player) ? second : first;
    }

    public UUID first() {
        return first;
    }

    public UUID second() {
        return second;
    }

    public String interactionId() {
        return interactionId;
    }
}
