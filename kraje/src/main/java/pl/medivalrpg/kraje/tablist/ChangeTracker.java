// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Remembers what was last sent to each player (tab-list name, header + footer) so that a packet is sent only when
 * the content really changed. Main thread only. Pure logic, no Bukkit.
 */
public final class ChangeTracker<T> {

    private final Map<UUID, T> last = new HashMap<>();

    /**
     * True (and remembers {@code value}) when {@code value} differs from what was last recorded for the player;
     * false when it is the same — then nothing needs to be sent.
     */
    public boolean update(final UUID player, final T value) {
        final T previous = this.last.get(player);
        if (previous != null && Objects.equals(previous, value)) {
            return false;
        }
        this.last.put(player, value);
        return true;
    }

    public T get(final UUID player) {
        return this.last.get(player);
    }

    public void forget(final UUID player) {
        this.last.remove(player);
    }

    public void clear() {
        this.last.clear();
    }

    public int size() {
        return this.last.size();
    }
}
