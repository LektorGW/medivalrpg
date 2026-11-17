// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PvP combat tag: when did a player last hit or get hit by another player. Teleports are blocked for
 * {@code teleport.combat-tag-seconds} after that. Pure; main thread only.
 */
public final class CombatTracker {

    private final Map<UUID, Long> lastCombat = new HashMap<>();

    /** Tags both players of a PvP hit (self-damage is ignored). */
    public void hit(final UUID attacker, final UUID victim, final long nowMs) {
        if (attacker == null || victim == null || attacker.equals(victim)) {
            return;
        }
        this.lastCombat.put(attacker, nowMs);
        this.lastCombat.put(victim, nowMs);
    }

    /** Milliseconds the player stays tagged, 0 when not in combat. */
    public long remainingMs(final UUID player, final long tagMs, final long nowMs) {
        final Long last = this.lastCombat.get(player);
        if (last == null || tagMs <= 0) {
            return 0;
        }
        final long left = last + tagMs - nowMs;
        if (left <= 0) {
            this.lastCombat.remove(player);
            return 0;
        }
        return left;
    }

    public boolean inCombat(final UUID player, final long tagMs, final long nowMs) {
        return this.remainingMs(player, tagMs, nowMs) > 0;
    }

    public void forget(final UUID player) {
        this.lastCombat.remove(player);
    }
}
