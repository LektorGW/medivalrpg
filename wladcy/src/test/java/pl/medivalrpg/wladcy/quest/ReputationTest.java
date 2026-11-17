// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Reputation tiers and the per-pair book (daily gain cap, clamping, admin changes, persistence). */
class ReputationTest {

    private static final UUID NPC = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID P = UUID.randomUUID();

    @Test
    void tierBoundaries() {
        assertEquals(RepTier.WROG, RepTier.of(-100));
        assertEquals(RepTier.WROG, RepTier.of(-60));
        assertEquals(RepTier.NIEUFNY, RepTier.of(-59));
        assertEquals(RepTier.NIEUFNY, RepTier.of(-20));
        assertEquals(RepTier.OBCY, RepTier.of(-19));
        assertEquals(RepTier.OBCY, RepTier.of(0));
        assertEquals(RepTier.OBCY, RepTier.of(19));
        assertEquals(RepTier.ZNAJOMY, RepTier.of(20));
        assertEquals(RepTier.ZNAJOMY, RepTier.of(49));
        assertEquals(RepTier.PRZYJACIEL, RepTier.of(50));
        assertEquals(RepTier.PRZYJACIEL, RepTier.of(79));
        assertEquals(RepTier.BOHATER, RepTier.of(80));
        assertEquals(RepTier.BOHATER, RepTier.of(500), "clamped");
        assertEquals(RepTier.WROG, RepTier.of(-500), "clamped");
        assertEquals("Wróg", RepTier.WROG.displayName());
        assertEquals("Bohater", RepTier.BOHATER.displayName());
        assertEquals(20, RepTier.toNext(0));
        assertEquals(1, RepTier.toNext(79));
        assertEquals(0, RepTier.toNext(90));
        assertTrue(RepTier.PRZYJACIEL.atLeast(RepTier.ZNAJOMY));
        assertFalse(RepTier.OBCY.atLeast(RepTier.ZNAJOMY));
    }

    @Test
    void dailyGainCapOnlyLimitsGains() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final ReputationBook b = new ReputationBook(clock, null);
        assertEquals(0, b.value(NPC, P));
        assertEquals(10, b.add(NPC, P, "Jan", 10, 15).applied());
        assertEquals(5, b.add(NPC, P, "Jan", 10, 15).applied(), "only 5 left of today's 15");
        assertEquals(0, b.add(NPC, P, "Jan", 4, 15).applied(), "cap reached");
        assertEquals(-4, b.add(NPC, P, "Jan", -4, 15).applied(), "losses are never capped");
        assertEquals(11, b.value(NPC, P));
        assertEquals(0, b.value(OTHER, P), "per country");
        clock.set(Instant.parse("2026-10-28T09:00:00Z"));
        assertEquals(9, b.add(NPC, P, "Jan", 9, 15).applied(), "new day, new cap");
        assertEquals(30, b.add(NPC, P, "Jan", 30, 0).applied(), "cap 0 = none");
    }

    @Test
    void clampingAndTierChange() {
        final ReputationBook b = new ReputationBook(new TestSupport.MutableClock(Instant.EPOCH), null);
        final ReputationBook.Change up = b.add(NPC, P, "Jan", 150, 0);
        assertEquals(100, up.value());
        assertEquals(100, up.applied());
        assertTrue(up.tierChanged());
        assertEquals(RepTier.OBCY, up.before());
        assertEquals(RepTier.BOHATER, up.after());
        final ReputationBook.Change down = b.add(NPC, P, "Jan", -500, 0);
        assertEquals(-100, down.value());
        assertEquals(-200, down.applied());
        assertEquals(RepTier.WROG, b.tier(NPC, P));
        final ReputationBook.Change same = b.add(NPC, P, "Jan", -1, 0);
        assertFalse(same.tierChanged());
        assertEquals(0, same.applied(), "already at the floor");
    }

    @Test
    void adminSetResetAndListing() {
        final ReputationBook b = new ReputationBook(new TestSupport.MutableClock(Instant.EPOCH), null);
        b.set(NPC, P, "Jan", 55);
        b.set(OTHER, P, "Jan", -30);
        final UUID q = UUID.randomUUID();
        b.set(NPC, q, "Ola", 90);
        assertEquals(Map.of(NPC, 55, OTHER, -30), b.ofPlayer(P));
        assertEquals("Ola", b.top(NPC, 5).get(0).getKey());
        assertEquals(1, b.reset(P, OTHER));
        assertEquals(Map.of(NPC, 55), b.ofPlayer(P));
        b.set(OTHER, P, "Jan", 5);
        assertEquals(2, b.reset(P, null), "all countries of the player");
        assertEquals(90, b.value(NPC, q), "others untouched");
        b.forgetCountry(NPC);
        assertEquals(0, b.size());
    }

    @Test
    void snapshotRoundTrip() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final ReputationBook b = new ReputationBook(clock, null);
        b.add(NPC, P, "Jan", 12, 15);
        b.countCompleted(NPC, P, "Jan");
        b.countFailed(NPC, P, "Jan");
        b.countAbandoned(NPC, P, "Jan");
        assertTrue(b.dirty());
        final Map<String, ReputationBook.Entry> snap = b.snapshot();
        assertFalse(b.dirty());
        final com.google.gson.Gson gson = new com.google.gson.Gson();
        final Map<String, ReputationBook.Entry> back = gson.fromJson(gson.toJson(snap),
            new com.google.gson.reflect.TypeToken<Map<String, ReputationBook.Entry>>() { }.getType());
        final ReputationBook copy = new ReputationBook(clock, back);
        final ReputationBook.Entry e = copy.peek(NPC, P);
        assertEquals(12, e.value);
        assertEquals(1, e.completed);
        assertEquals(1, e.failed);
        assertEquals(1, e.abandoned);
        assertEquals("Jan", e.playerName);
        assertEquals(3, copy.add(NPC, P, "Jan", 10, 15).applied(), "today's gains survive a restart");
    }
}
