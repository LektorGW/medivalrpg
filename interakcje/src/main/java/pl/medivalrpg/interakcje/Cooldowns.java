// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-sender cooldowns: one per (sender, interaction) and a global anti-spam gap between any two
 * interactions. Time is passed in explicitly (ms), so the class is deterministic in tests.
 * Main thread only.
 */
public final class Cooldowns {
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    /** @return milliseconds left on the interaction cooldown, {@code 0} when ready. */
    public long remaining(UUID sender, String interactionId, long now) {
        Map<String, Long> m = readyAt.get(sender);
        if (m == null) {
            return 0;
        }
        Long at = m.get(interactionId);
        return at == null ? 0 : Math.max(0, at - now);
    }

    /** @return milliseconds left on the global anti-spam gap, {@code 0} when ready. */
    public long globalRemaining(UUID sender, long globalGapMs, long now) {
        Long last = lastUse.get(sender);
        return last == null ? 0 : Math.max(0, last + globalGapMs - now);
    }

    /** Records a use of {@code interactionId} at {@code now}. */
    public void mark(UUID sender, String interactionId, long cooldownMs, long now) {
        lastUse.put(sender, now);
        if (cooldownMs > 0) {
            readyAt.computeIfAbsent(sender, k -> new HashMap<>()).put(interactionId, now + cooldownMs);
        }
    }

    public void clear(UUID sender) {
        readyAt.remove(sender);
        lastUse.remove(sender);
    }

    /** Drops expired entries (call occasionally to keep the maps small). */
    public void purge(long now, long globalGapMs) {
        readyAt.values().forEach(m -> m.values().removeIf(at -> at <= now));
        readyAt.values().removeIf(Map::isEmpty);
        lastUse.values().removeIf(at -> at + globalGapMs <= now);
    }

    /** Whole seconds to show the player (rounded up, at least 1). */
    public static long secondsLeft(long remainingMs) {
        return Math.max(1, (remainingMs + 999) / 1000);
    }
}
