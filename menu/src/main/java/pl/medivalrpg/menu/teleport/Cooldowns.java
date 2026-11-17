// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player cooldowns, one per kind ("spawn", "home"). Pure; main thread only. */
public final class Cooldowns {

    private record Key(UUID player, String kind) {
    }

    private final Map<Key, Long> until = new HashMap<>();

    /** Starts (or restarts) a cooldown of {@code seconds}; 0 or less clears it. */
    public void start(final UUID player, final String kind, final int seconds, final long nowMs) {
        if (seconds <= 0) {
            this.until.remove(new Key(player, kind));
        } else {
            this.until.put(new Key(player, kind), nowMs + seconds * 1000L);
        }
    }

    /** Milliseconds left, 0 when there is no cooldown. */
    public long remainingMs(final UUID player, final String kind, final long nowMs) {
        final Key key = new Key(player, kind);
        final Long end = this.until.get(key);
        if (end == null) {
            return 0;
        }
        if (end <= nowMs) {
            this.until.remove(key);
            return 0;
        }
        return end - nowMs;
    }

    public void clear(final UUID player) {
        this.until.keySet().removeIf(k -> k.player().equals(player));
    }

    /** Whole seconds, rounded up (for messages). */
    public static long seconds(final long ms) {
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }
}
