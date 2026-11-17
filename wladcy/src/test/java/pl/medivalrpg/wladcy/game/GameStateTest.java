// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

class GameStateTest {

    @Test
    void approximation() {
        assertEquals(0, Approx.round(-3));
        assertEquals(57, Approx.round(57));
        assertEquals(1200, Approx.round(1234));
        assertEquals(99000, Approx.round(98765));
        assertEquals(100000, Approx.round(99999));
    }

    @Test
    void giftsResetDailyAndWarCooldownPersists() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        final RulerState s = new RulerState(clock, null);
        final UUID npc = UUID.randomUUID();
        final UUID p = UUID.randomUUID();
        s.gift(npc, p, 300);
        s.gift(npc, UUID.randomUUID(), 200);
        assertEquals(500, s.giftedToday(npc));
        assertEquals(300, s.giftedToday(npc, p));
        s.warDeclared(npc);
        final RulerState copy = new RulerState(clock, s.snapshot());
        assertEquals(clock.millis(), copy.lastWarAt(npc));
        assertEquals(500, copy.giftedToday(npc));
        clock.set(Instant.parse("2026-10-27T00:00:01Z"));
        assertEquals(0, copy.giftedToday(npc));
        assertEquals(Instant.parse("2026-10-26T10:00:00Z").toEpochMilli(), copy.lastWarAt(npc));
    }

    @Test
    void tributeOffersBelongToOnePlayerAndExpire() {
        final TributeOffers o = new TributeOffers();
        final UUID p = UUID.randomUUID();
        final TributeOffers.Offer offer = o.create(p, UUID.randomUUID(), UUID.randomUUID(), 500, 1000);
        assertTrue(o.hasPending(p, 0));
        assertTrue(o.take(offer.id(), UUID.randomUUID(), 0).isEmpty(), "someone else cannot click it");
        assertTrue(o.hasPending(p, 0), "and it stays for the owner");
        assertTrue(o.take(offer.id(), p, 0).isPresent());
        assertTrue(o.take(offer.id(), p, 0).isEmpty(), "a click is final");
        final TributeOffers.Offer late = o.create(p, UUID.randomUUID(), UUID.randomUUID(), 500, 1000);
        assertFalse(o.hasPending(p, 2000), "expired");
        assertTrue(o.take(late.id(), p, 2000).isEmpty());
    }
}
