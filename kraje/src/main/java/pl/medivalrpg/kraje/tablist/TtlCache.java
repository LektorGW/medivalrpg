// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Values that are expensive to read (balance, era, season, news) kept for a few seconds. Main thread only (not
 * synchronised). {@code null} values are cached too. Pure logic, no Bukkit.
 */
public final class TtlCache<K, V> {

    private record Slot<V>(V value, long expires) {
    }

    private final Map<K, Slot<V>> slots = new HashMap<>();
    private long ttlMillis;

    public TtlCache(final long ttlMillis) {
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    public void ttl(final long ttlMillis) {
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    /** Cached value of {@code key}, loaded again when older than the TTL. */
    public V get(final K key, final long now, final Supplier<V> loader) {
        final Slot<V> slot = this.slots.get(key);
        if (slot != null && now < slot.expires()) {
            return slot.value();
        }
        final V value = loader.get();
        this.slots.put(key, new Slot<>(value, now + this.ttlMillis));
        return value;
    }

    public void invalidate(final K key) {
        this.slots.remove(key);
    }

    public void clear() {
        this.slots.clear();
    }

    public int size() {
        return this.slots.size();
    }
}
