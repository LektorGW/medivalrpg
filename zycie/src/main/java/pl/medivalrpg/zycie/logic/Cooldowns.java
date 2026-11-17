// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Expiring keys (e.g. "villager|player" reaction cooldowns). Not thread-safe: main thread only. */
public final class Cooldowns<K> {

    private final Map<K, Long> until = new HashMap<>();

    /** True (and starts the cooldown) when {@code key} is free at {@code now}. */
    public boolean tryUse(final K key, final long now, final long length) {
        final Long u = this.until.get(key);
        if (u != null && u > now) {
            return false;
        }
        this.until.put(key, now + length);
        return true;
    }

    public boolean ready(final K key, final long now) {
        final Long u = this.until.get(key);
        return u == null || u <= now;
    }

    public void set(final K key, final long untilTime) {
        this.until.put(key, untilTime);
    }

    /** Drops expired entries. */
    public void prune(final long now) {
        final Iterator<Map.Entry<K, Long>> it = this.until.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    public void clear() {
        this.until.clear();
    }

    public int size() {
        return this.until.size();
    }
}
