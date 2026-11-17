// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Sliding-window counter: at most {@code max} events per key within {@code windowMillis}. Thread-safe.
 * Used for login failures (per nick and per IP) and for mutations per panel user.
 */
public final class RateLimiter {

    private final int max;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> events = new HashMap<>();

    public RateLimiter(final int max, final long windowMillis) {
        this(max, windowMillis, System::currentTimeMillis);
    }

    public RateLimiter(final int max, final long windowMillis, final LongSupplier clock) {
        this.max = Math.max(1, max);
        this.windowMillis = Math.max(1, windowMillis);
        this.clock = clock;
    }

    private Deque<Long> prune(final String key, final long now) {
        final Deque<Long> q = this.events.get(key);
        if (q == null) {
            return null;
        }
        while (!q.isEmpty() && q.peekFirst() <= now - this.windowMillis) {
            q.removeFirst();
        }
        if (q.isEmpty()) {
            this.events.remove(key);
            return null;
        }
        return q;
    }

    /** True when the key reached the limit (the next event would exceed it). */
    public synchronized boolean blocked(final String key) {
        final Deque<Long> q = this.prune(key, this.clock.getAsLong());
        return q != null && q.size() >= this.max;
    }

    /** Records one event. */
    public synchronized void record(final String key) {
        final long now = this.clock.getAsLong();
        this.prune(key, now);
        this.events.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
    }

    /** Records an event if the limit allows it; false (nothing recorded) when blocked. */
    public synchronized boolean tryAcquire(final String key) {
        if (this.blocked(key)) {
            return false;
        }
        this.record(key);
        return true;
    }

    /** Milliseconds until the key is no longer blocked (0 when not blocked). */
    public synchronized long retryAfter(final String key) {
        final long now = this.clock.getAsLong();
        final Deque<Long> q = this.prune(key, now);
        if (q == null || q.size() < this.max) {
            return 0;
        }
        // The oldest events must leave the window until size < max.
        final Iterator<Long> it = q.iterator();
        long t = 0;
        for (int i = 0; i <= q.size() - this.max && it.hasNext(); i++) {
            t = it.next();
        }
        return Math.max(0, t + this.windowMillis - now);
    }

    public synchronized void reset(final String key) {
        this.events.remove(key);
    }

    /** Drops expired keys (call occasionally). */
    public synchronized void cleanup() {
        final long now = this.clock.getAsLong();
        for (final String key : this.events.keySet().toArray(String[]::new)) {
            this.prune(key, now);
        }
    }
}
