// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection: failed password checks are counted per nick and per address in a sliding window; after
 * {@code limit} failures within the window the key is locked for {@code lockMillis}. Thread-safe.
 */
public final class LoginGuard {

    private final int limit;
    private final long windowMillis;
    private final long lockMillis;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public LoginGuard(final int limit, final long windowMillis, final long lockMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.lockMillis = lockMillis;
    }

    private static String nickKey(final String nick) {
        return "n:" + nick.toLowerCase(Locale.ROOT);
    }

    private static String ipKey(final String ip) {
        return "i:" + ip;
    }

    /** Remaining lock in ms (0 = allowed), the longer of nick and address. */
    public long blockedFor(final String nick, final String ip, final long now) {
        long wait = 0;
        if (nick != null) {
            wait = Math.max(wait, this.remaining(nickKey(nick), now));
        }
        if (ip != null) {
            wait = Math.max(wait, this.remaining(ipKey(ip), now));
        }
        return wait;
    }

    private long remaining(final String key, final long now) {
        final Long until = this.lockedUntil.get(key);
        if (until == null) {
            return 0;
        }
        if (until <= now) {
            this.lockedUntil.remove(key, until);
            return 0;
        }
        return until - now;
    }

    /** Records a failure; {@code true} when it caused a lock. */
    public boolean failure(final String nick, final String ip, final long now) {
        boolean locked = false;
        if (nick != null) {
            locked |= this.count(nickKey(nick), now);
        }
        if (ip != null) {
            locked |= this.count(ipKey(ip), now);
        }
        return locked;
    }

    private boolean count(final String key, final long now) {
        final Deque<Long> q = this.failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - this.windowMillis) {
                q.pollFirst();
            }
            q.addLast(now);
            if (q.size() >= this.limit) {
                q.clear();
                this.lockedUntil.put(key, now + this.lockMillis);
                return true;
            }
        }
        return false;
    }

    /** Successful login: the nick's counter is reset (the address keeps its history). */
    public void success(final String nick) {
        this.failures.remove(nickKey(nick));
    }

    /** Admin unlock of a nick or an address. */
    public boolean unlock(final String nickOrIp) {
        final boolean a = this.lockedUntil.remove(nickKey(nickOrIp)) != null;
        final boolean b = this.lockedUntil.remove(ipKey(nickOrIp)) != null;
        this.failures.remove(nickKey(nickOrIp));
        this.failures.remove(ipKey(nickOrIp));
        return a || b;
    }

    /** Drops expired data (called every few minutes). */
    public void cleanup(final long now) {
        this.lockedUntil.values().removeIf(until -> until <= now);
        this.failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast() <= now - this.windowMillis;
            }
        });
    }
}
