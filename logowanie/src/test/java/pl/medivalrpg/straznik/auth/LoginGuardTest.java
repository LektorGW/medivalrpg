// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginGuardTest {

    private static final long MIN = 60_000L;

    @Test
    void locksNickAndIpAfterLimitInsideWindow() {
        final LoginGuard g = new LoginGuard(3, 15 * MIN, 15 * MIN);
        assertFalse(g.failure("Gracz", "1.2.3.4", 0));
        assertFalse(g.failure("Gracz", "1.2.3.4", 1000));
        assertTrue(g.failure("GRACZ", "1.2.3.4", 2000), "nick is case-insensitive");
        assertEquals(15 * MIN, g.blockedFor("gracz", null, 2000));
        assertEquals(15 * MIN, g.blockedFor("Inny", "1.2.3.4", 2000), "address locked as well");
        assertEquals(0, g.blockedFor("Inny", "5.6.7.8", 2000));
        assertEquals(0, g.blockedFor("Gracz", "1.2.3.4", 2000 + 15 * MIN));
    }

    @Test
    void oldFailuresLeaveTheWindow() {
        final LoginGuard g = new LoginGuard(3, 10 * MIN, 5 * MIN);
        g.failure("a", null, 0);
        g.failure("a", null, MIN);
        assertFalse(g.failure("a", null, 11 * MIN), "first failure expired");
        assertEquals(0, g.blockedFor("a", null, 11 * MIN));
    }

    @Test
    void successResetsNickAndAdminUnlocks() {
        final LoginGuard g = new LoginGuard(2, 10 * MIN, 5 * MIN);
        g.failure("a", "9.9.9.9", 0);
        g.success("a");
        assertFalse(g.failure("a", null, 1));
        assertTrue(g.failure("b", "9.9.9.9", 2), "address history survives the nick's success");
        assertTrue(g.blockedFor(null, "9.9.9.9", 3) > 0);
        assertTrue(g.unlock("9.9.9.9"));
        assertEquals(0, g.blockedFor(null, "9.9.9.9", 3));
        assertFalse(g.unlock("9.9.9.9"));
    }

    @Test
    void cleanupDropsExpired() {
        final LoginGuard g = new LoginGuard(1, MIN, MIN);
        g.failure("a", null, 0);
        g.cleanup(10 * MIN);
        assertEquals(0, g.blockedFor("a", null, 10 * MIN));
    }
}
