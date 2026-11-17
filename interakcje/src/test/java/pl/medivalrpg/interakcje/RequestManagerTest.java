// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RequestManagerTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @Test
    void onePendingRequestPerPair() {
        RequestManager m = new RequestManager();
        assertNotNull(m.create(alice, bob, "kiss", 0, 30_000));
        assertNull(m.create(alice, bob, "hug", 1000, 30_000), "second request of the same pair is refused");
        assertNotNull(m.create(bob, alice, "hug", 1000, 30_000), "reverse direction is a different pair");
        assertNotNull(m.create(alice, carol, "kiss", 1000, 30_000));
        assertEquals(3, m.size());
    }

    @Test
    void findChecksTargetAndExpiry() {
        RequestManager m = new RequestManager();
        RequestManager.Request r = m.create(alice, bob, "kiss", 0, 30_000);
        assertSame(r, m.find(r.id(), bob, 29_999));
        assertNull(m.find(r.id(), alice, 1000), "only the target may answer");
        assertNull(m.find(r.id(), bob, 30_000), "expired");
        assertNull(m.find(r.id() + 100, bob, 1000), "unknown id");
    }

    @Test
    void expireRemovesAndReturnsExpired() {
        RequestManager m = new RequestManager();
        RequestManager.Request old = m.create(alice, bob, "kiss", 0, 30_000);
        RequestManager.Request fresh = m.create(carol, bob, "hug", 20_000, 30_000);
        List<RequestManager.Request> expired = m.expire(30_000);
        assertEquals(List.of(old), expired);
        assertEquals(1, m.size());
        assertSame(fresh, m.find(fresh.id(), bob, 30_000));
        assertNotNull(m.create(alice, bob, "kiss", 30_000, 30_000), "pair is free again after expiry");
    }

    @Test
    void expiredButNotPurgedDoesNotBlockNewRequest() {
        RequestManager m = new RequestManager();
        m.create(alice, bob, "kiss", 0, 30_000);
        RequestManager.Request again = m.create(alice, bob, "hug", 31_000, 30_000);
        assertNotNull(again);
        assertEquals(1, m.size(), "stale request of the same pair is replaced");
    }

    @Test
    void latestForReturnsNewest() {
        RequestManager m = new RequestManager();
        m.create(alice, bob, "kiss", 0, 30_000);
        RequestManager.Request newer = m.create(carol, bob, "hug", 1000, 30_000);
        assertSame(newer, m.latestFor(bob, 2000));
        assertNull(m.latestFor(alice, 2000));
    }

    @Test
    void removeInvolvingDropsBothDirections() {
        RequestManager m = new RequestManager();
        m.create(alice, bob, "kiss", 0, 30_000);
        m.create(bob, carol, "hug", 0, 30_000);
        m.create(carol, alice, "hold", 0, 30_000);
        assertEquals(2, m.removeInvolving(bob).size());
        assertEquals(1, m.size());
    }

    @Test
    void idsAreUnique() {
        RequestManager m = new RequestManager();
        RequestManager.Request r1 = m.create(alice, bob, "kiss", 0, 30_000);
        RequestManager.Request r2 = m.create(alice, carol, "kiss", 0, 30_000);
        assertNotNull(r1);
        assertNotNull(r2);
        org.junit.jupiter.api.Assertions.assertNotEquals(r1.id(), r2.id());
    }
}
