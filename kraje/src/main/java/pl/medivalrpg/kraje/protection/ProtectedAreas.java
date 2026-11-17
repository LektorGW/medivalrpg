// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.medivalrpg.kraje.model.ProtectedArea;

/**
 * Registry of {@link ProtectedArea}s (no Bukkit). Writes replace an immutable snapshot, so reads are lock free and
 * safe from any thread; areas are kept in insertion order.
 */
public final class ProtectedAreas {

    private volatile Map<String, ProtectedArea> areas = Map.of();

    /** Adds or replaces (same id). Returns the previous area with that id, if any. */
    public synchronized Optional<ProtectedArea> put(final ProtectedArea area) {
        final Map<String, ProtectedArea> copy = new LinkedHashMap<>(this.areas);
        final ProtectedArea old = copy.put(area.id(), area);
        this.areas = java.util.Collections.unmodifiableMap(copy);
        return Optional.ofNullable(old);
    }

    /** Removes the area; empty when there was none. */
    public synchronized Optional<ProtectedArea> remove(final String id) {
        if (id == null || !this.areas.containsKey(id)) {
            return Optional.empty();
        }
        final Map<String, ProtectedArea> copy = new LinkedHashMap<>(this.areas);
        final ProtectedArea old = copy.remove(id);
        this.areas = java.util.Collections.unmodifiableMap(copy);
        return Optional.ofNullable(old);
    }

    /** Replaces everything (loading from disk). */
    public synchronized void setAll(final Collection<ProtectedArea> all) {
        final Map<String, ProtectedArea> copy = new LinkedHashMap<>();
        for (final ProtectedArea a : all) {
            copy.put(a.id(), a);
        }
        this.areas = java.util.Collections.unmodifiableMap(copy);
    }

    public Optional<ProtectedArea> get(final String id) {
        return Optional.ofNullable(id == null ? null : this.areas.get(id));
    }

    /** First area whose rectangle + buffer covers chunk ({@code cx}, {@code cz}) of {@code world}. */
    public Optional<ProtectedArea> at(final String world, final int cx, final int cz) {
        if (world == null) {
            return Optional.empty();
        }
        for (final ProtectedArea a : this.areas.values()) {
            if (a.containsChunk(world, cx, cz)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    public boolean isProtected(final String world, final int cx, final int cz) {
        return this.at(world, cx, cz).isPresent();
    }

    /** Snapshot in insertion order. */
    public List<ProtectedArea> all() {
        return List.copyOf(this.areas.values());
    }
}
