// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last activity of each online player (move, look, chat, command, interaction) for the AFK mark in the tab list.
 * Thread-safe: the chat event is asynchronous. Pure logic, no Bukkit.
 */
public final class ActivityTracker {

    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

    /** Records activity at {@code now} (epoch millis). */
    public void touch(final UUID player, final long now) {
        this.lastActive.put(player, now);
    }

    /**
     * AFK when the last activity is at least {@code thresholdMillis} old. Unknown players (never touched) are not AFK;
     * a threshold of 0 or less switches AFK detection off.
     */
    public boolean isAfk(final UUID player, final long now, final long thresholdMillis) {
        if (thresholdMillis <= 0) {
            return false;
        }
        final Long last = this.lastActive.get(player);
        return last != null && now - last >= thresholdMillis;
    }

    /** Milliseconds since the last activity, -1 when unknown. */
    public long idleMillis(final UUID player, final long now) {
        final Long last = this.lastActive.get(player);
        return last == null ? -1L : Math.max(0L, now - last);
    }

    public void remove(final UUID player) {
        this.lastActive.remove(player);
    }

    public void clear() {
        this.lastActive.clear();
    }

    public int size() {
        return this.lastActive.size();
    }
}
