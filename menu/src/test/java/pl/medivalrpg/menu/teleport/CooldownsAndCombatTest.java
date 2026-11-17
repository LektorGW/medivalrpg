// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooldownsAndCombatTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long T0 = 50_000L;

    // ------------------------------------------------------------------ cooldown

    @Test
    void cooldownRunsOutAfterItsSeconds() {
        final Cooldowns c = new Cooldowns();
        assertEquals(0, c.remainingMs(A, "spawn", T0));
        c.start(A, "spawn", 30, T0);
        assertEquals(30_000, c.remainingMs(A, "spawn", T0));
        assertEquals(1, c.remainingMs(A, "spawn", T0 + 29_999));
        assertEquals(0, c.remainingMs(A, "spawn", T0 + 30_000));
        assertEquals(0, c.remainingMs(A, "spawn", T0 + 10), "expired entry was removed");
    }

    @Test
    void cooldownIsPerPlayerAndKind() {
        final Cooldowns c = new Cooldowns();
        c.start(A, "spawn", 30, T0);
        assertEquals(0, c.remainingMs(B, "spawn", T0), "other player");
        assertEquals(0, c.remainingMs(A, "home", T0), "/dom has its own cooldown");
        c.start(A, "home", 10, T0);
        c.clear(A);
        assertEquals(0, c.remainingMs(A, "spawn", T0));
        assertEquals(0, c.remainingMs(A, "home", T0));
    }

    @Test
    void zeroCooldownClears() {
        final Cooldowns c = new Cooldowns();
        c.start(A, "spawn", 30, T0);
        c.start(A, "spawn", 0, T0);
        assertEquals(0, c.remainingMs(A, "spawn", T0));
    }

    @Test
    void secondsRoundUp() {
        assertEquals(0, Cooldowns.seconds(0));
        assertEquals(1, Cooldowns.seconds(1));
        assertEquals(1, Cooldowns.seconds(1000));
        assertEquals(2, Cooldowns.seconds(1001));
        assertEquals(30, Cooldowns.seconds(30_000));
    }

    // ------------------------------------------------------------------ combat tag

    @Test
    void pvpHitTagsBothPlayersForTenSeconds() {
        final CombatTracker c = new CombatTracker();
        c.hit(A, B, T0);
        assertTrue(c.inCombat(A, 10_000, T0));
        assertTrue(c.inCombat(B, 10_000, T0 + 9_999));
        assertEquals(5_000, c.remainingMs(A, 10_000, T0 + 5_000));
        assertFalse(c.inCombat(A, 10_000, T0 + 10_000));
        assertFalse(c.inCombat(B, 10_000, T0 + 20_000));
    }

    @Test
    void newHitRefreshesTheTag() {
        final CombatTracker c = new CombatTracker();
        c.hit(A, B, T0);
        c.hit(B, A, T0 + 8_000);
        assertEquals(8_000, c.remainingMs(A, 10_000, T0 + 10_000));
    }

    @Test
    void selfDamageAndDisabledTagDoNothing() {
        final CombatTracker c = new CombatTracker();
        c.hit(A, A, T0);
        c.hit(null, B, T0);
        assertFalse(c.inCombat(A, 10_000, T0));
        assertFalse(c.inCombat(B, 10_000, T0));
        c.hit(A, B, T0);
        assertFalse(c.inCombat(A, 0, T0), "combat-tag-seconds: 0 disables the tag");
        c.forget(A);
        assertFalse(c.inCombat(A, 10_000, T0));
        assertTrue(c.inCombat(B, 10_000, T0));
    }
}
