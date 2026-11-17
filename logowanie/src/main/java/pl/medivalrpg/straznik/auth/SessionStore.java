// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short IP sessions: after a logged-in player leaves, the same UUID from the same address within
 * {@code sesja.minuty} skips {@code /login}. Memory only — a restart ends every session. Thread-safe.
 */
public final class SessionStore {

    private record Session(String ip, long expires) {
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Opened when a logged-in player quits. */
    public void open(final UUID player, final String ip, final long now, final long durationMillis) {
        if (ip != null && durationMillis > 0) {
            this.sessions.put(player, new Session(ip, now + durationMillis));
        }
    }

    /** Valid session for this address? It is consumed either way (one use). */
    public boolean consume(final UUID player, final String ip, final long now) {
        final Session s = this.sessions.remove(player);
        return s != null && ip != null && s.ip().equals(ip) && now < s.expires();
    }

    /** Read-only check (used before the join, when the result must not consume it yet). */
    public boolean valid(final UUID player, final String ip, final long now) {
        final Session s = this.sessions.get(player);
        return s != null && ip != null && s.ip().equals(ip) && now < s.expires();
    }

    public void invalidate(final UUID player) {
        this.sessions.remove(player);
    }

    public void clear() {
        this.sessions.clear();
    }

    public void cleanup(final long now) {
        this.sessions.values().removeIf(s -> s.expires() <= now);
    }
}
