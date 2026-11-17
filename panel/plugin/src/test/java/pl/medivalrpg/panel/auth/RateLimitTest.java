// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    private static final long MIN = 60_000L;

    @Test
    void slidingWindow() {
        final AtomicLong now = new AtomicLong(1_000_000);
        final RateLimiter r = new RateLimiter(3, 10 * MIN, now::get);
        assertTrue(r.tryAcquire("a"));
        now.addAndGet(MIN);
        assertTrue(r.tryAcquire("a"));
        now.addAndGet(MIN);
        assertTrue(r.tryAcquire("a"));
        assertFalse(r.tryAcquire("a"), "4th within the window");
        assertTrue(r.tryAcquire("b"), "keys are independent");
        // the first event leaves the window 10 min after it happened: 8 min from now
        assertEquals(8 * MIN, r.retryAfter("a"));
        now.addAndGet(8 * MIN - 1);
        assertTrue(r.blocked("a"));
        now.addAndGet(1);
        assertFalse(r.blocked("a"));
        assertTrue(r.tryAcquire("a"));
        assertFalse(r.tryAcquire("a"));
    }

    @Test
    void resetAndCleanup() {
        final AtomicLong now = new AtomicLong(0);
        final RateLimiter r = new RateLimiter(1, MIN, now::get);
        assertTrue(r.tryAcquire("x"));
        assertFalse(r.tryAcquire("x"));
        r.reset("x");
        assertTrue(r.tryAcquire("x"));
        now.addAndGet(2 * MIN);
        r.cleanup();
        assertEquals(0, r.retryAfter("x"));
    }

    @Test
    void loginGuardCountsFailuresPerNickAndPerIp() {
        final AtomicLong now = new AtomicLong(0);
        final LoginGuard g = new LoginGuard(5, 15 * MIN, now::get);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, g.blockedFor("Gracz", "1.2.3.4"), "attempt " + i);
            g.failure("Gracz", "1.2.3.4");
        }
        assertTrue(g.blockedFor("gracz", "9.9.9.9") > 0, "nick blocked from any IP, case-insensitive");
        assertTrue(g.blockedFor("Inny", "1.2.3.4") > 0, "IP blocked for any nick");
        assertEquals(0, g.blockedFor("Inny", "9.9.9.9"));
        now.addAndGet(15 * MIN);
        assertEquals(0, g.blockedFor("Gracz", "1.2.3.4"), "window passed");
    }

    @Test
    void successClearsTheNickButNotTheIp() {
        final AtomicLong now = new AtomicLong(0);
        final LoginGuard g = new LoginGuard(2, 15 * MIN, now::get);
        g.failure("A", "ip");
        g.success("A");
        g.failure("A", "ip");
        assertEquals(0, g.blockedFor("A", "other"), "nick counter was reset by the success");
        assertTrue(g.blockedFor("B", "ip") > 0, "the IP still has 2 failures");
    }

    @Test
    void spreadAttackFromOneIpIsStopped() {
        final LoginGuard g = new LoginGuard(5, 15 * MIN, () -> 0L);
        for (int i = 0; i < 5; i++) {
            g.failure("nick" + i, "6.6.6.6");
        }
        assertTrue(g.blockedFor("nick99", "6.6.6.6") > 0);
    }

    /** AuthService asks this both when the message arrives and on the auth thread right before the password check. */
    @Test
    void blockedMessageAfterFailuresQueuedInABurst() {
        final AtomicLong now = new AtomicLong(5_000_000);
        final LoginGuard g = new LoginGuard(2, 15 * MIN, now::get);
        // two attempts passed the first check before either failed; the third, already queued, must be refused
        assertNull(AuthService.blockedMessage(g, "Gracz", "1.2.3.4"));
        g.failure("Gracz", "1.2.3.4");
        assertNull(AuthService.blockedMessage(g, "Gracz", "1.2.3.4"));
        g.failure("Gracz", "1.2.3.4");
        assertEquals("Za dużo nieudanych prób logowania. Spróbuj ponownie za 15 min.", AuthService.blockedMessage(g, "gracz", "9.9.9.9"));
        assertEquals("Za dużo nieudanych prób logowania. Spróbuj ponownie za 15 min.", AuthService.blockedMessage(g, "Inny", "1.2.3.4"));
        assertNull(AuthService.blockedMessage(g, "Inny", "9.9.9.9"));
        now.addAndGet(14 * MIN + 1);
        assertEquals("Za dużo nieudanych prób logowania. Spróbuj ponownie za 1 min.", AuthService.blockedMessage(g, "Gracz", "?"));
    }
}
