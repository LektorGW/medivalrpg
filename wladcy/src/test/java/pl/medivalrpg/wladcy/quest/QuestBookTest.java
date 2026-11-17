// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Quest state: daily boards, acceptance, completion/failure counters, cooldowns, expiry, persistence. */
class QuestBookTest {

    private static final UUID NPC = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID P = UUID.randomUUID();

    private static QuestData.Offer offer(final String id, final QuestType type, final int minutes) {
        final QuestData.Offer o = new QuestData.Offer();
        o.id = id;
        o.country = NPC.toString();
        o.type = type.key();
        o.material = "WHEAT";
        o.itemName = "pszenica";
        o.amount = 32;
        o.baseReward = 120;
        o.reputation = 4;
        o.timeLimitMinutes = minutes;
        if (type == QuestType.TRANSPORT) {
            o.targetCountry = TARGET.toString();
            o.targetName = "Gondor";
        }
        return o;
    }

    @Test
    void boardsLiveOneDay() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T22:00:00Z"));
        final QuestBook b = new QuestBook(clock, null);
        assertTrue(b.board(NPC).isEmpty(), "generated on first use");
        b.putBoard(NPC, List.of(offer("ab1", QuestType.DOSTAWA, 60)));
        assertEquals(1, b.board(NPC).orElseThrow().size());
        assertTrue(b.offer("AB1").isPresent(), "ids are case-insensitive");
        clock.set(Instant.parse("2026-10-28T00:00:01Z"));
        assertTrue(b.board(NPC).isEmpty(), "new day → new board");
        assertTrue(b.offer("ab1").isEmpty(), "yesterday's offers cannot be taken");
        final String id = b.newOfferId();
        assertTrue(id.length() >= 4 && id.matches("[a-z0-9]+"));
    }

    @Test
    void acceptCompleteAndDailyCounters() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final QuestBook b = new QuestBook(clock, null);
        final QuestData.Offer o = offer("ab1", QuestType.DOSTAWA, 120);
        b.putBoard(NPC, List.of(o));
        final QuestData.Active a = b.accept(P, "Jan", o, "Velmaria", 150, true, "uid1");
        assertEquals(clock.millis() + 120 * 60_000L, a.deadline);
        assertEquals(150, a.reward);
        assertTrue(a.escrow);
        assertTrue(b.used(P, "ab1"));
        assertEquals(clock.millis(), b.lastAcceptAt(P));
        assertEquals(1, b.active(P).size());
        assertTrue(b.active(P, "AB1").isPresent());
        assertTrue(b.active(P, "uid1").isPresent());
        assertTrue(b.isActive("uid1"));
        assertTrue(b.complete(a, 150));
        assertFalse(b.complete(a, 150), "only once");
        assertEquals(0, b.active(P).size());
        assertEquals(1, b.completedToday(P));
        assertEquals(1, b.completedToday(P, NPC));
        assertEquals(150, b.paidToday(NPC));
        assertEquals(1, b.totalCompleted(P));
        clock.set(Instant.parse("2026-10-28T08:00:00Z"));
        assertEquals(0, b.completedToday(P), "daily counters reset");
        assertEquals(0, b.paidToday(NPC));
        assertFalse(b.used(P, "ab1"));
        assertEquals(1, b.totalCompleted(P), "totals stay");
    }

    @Test
    void failureCooldownAndExpiry() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final QuestBook b = new QuestBook(clock, null);
        final QuestData.Offer o = offer("ab1", QuestType.DOSTAWA, 30);
        b.putBoard(NPC, List.of(o));
        final QuestData.Active a = b.accept(P, "Jan", o, "Velmaria", 100, true, "uid1");
        assertTrue(b.expired().isEmpty());
        clock.advanceMillis(30 * 60_000L);
        assertEquals(List.of("uid1"), b.expired().stream().map(x -> x.uid).toList());
        assertTrue(b.fail(a, 30));
        assertTrue(b.expired().isEmpty());
        assertEquals(1, b.totalFailed(P));
        assertEquals(1, b.completedToday(P, NPC), "a failure uses one of the country's daily slots (anti-farm)");
        assertEquals(0, b.completedToday(P), "but is not a completed quest");
        assertEquals(clock.millis() + 30 * 60_000L, b.cooldownUntil(P, NPC));
        clock.advanceMillis(30 * 60_000L);
        assertEquals(0, b.cooldownUntil(P, NPC), "cooldown over");
    }

    @Test
    void disbandCancelsQuestsOfAndToTheCountry() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final QuestBook b = new QuestBook(clock, null);
        final QuestData.Offer d = offer("ab1", QuestType.DOSTAWA, 30);
        final QuestData.Offer t = offer("ab2", QuestType.TRANSPORT, 30);
        b.putBoard(NPC, List.of(d, t));
        b.accept(P, "Jan", d, "Velmaria", 100, true, "u1");
        final QuestData.Active tr = b.accept(P, "Jan", t, "Velmaria", 100, true, "u2");
        assertEquals(List.of("u2"), b.forgetCountry(TARGET).stream().map(x -> x.uid).toList(), "transport to a fallen country");
        assertTrue(b.cancel(tr));
        assertEquals(0, b.totalFailed(P), "cancel is nobody's fault");
        assertEquals(1, b.forgetCountry(NPC).size());
        assertTrue(b.board(NPC).isEmpty());
    }

    @Test
    void noticesAndPersistence() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        final QuestBook b = new QuestBook(clock, null);
        final QuestData.Offer o = offer("ab1", QuestType.TRANSPORT, 60);
        b.putBoard(NPC, List.of(o));
        b.accept(P, "Jan", o, "Velmaria", 300, false, "u1");
        b.refuse(P, "zz9");
        b.addNotice(P, "Czas minął.");
        assertTrue(b.dirty());
        final Gson gson = new Gson();
        final String json = gson.toJson(b.snapshot());
        assertFalse(b.dirty());
        final QuestBook copy = new QuestBook(clock, gson.fromJson(json, QuestData.class));
        final QuestData.Active a = copy.active(P, "ab1").orElseThrow();
        assertEquals(QuestType.TRANSPORT, a.type());
        assertEquals("Gondor", a.targetName);
        assertEquals(300, a.reward);
        assertFalse(a.escrow);
        assertTrue(copy.used(P, "zz9"));
        assertTrue(copy.board(NPC).isPresent());
        assertEquals(List.of("Czas minął."), copy.takeNotices(P));
        assertTrue(copy.takeNotices(P).isEmpty(), "shown once");
        final QuestBook broken = new QuestBook(clock, gson.fromJson("{\"boards\":null,\"active\":{\"x\":[null]},\"players\":{\"y\":{}}}", QuestData.class));
        assertEquals(0, broken.activeCount(), "hand-edited or old files are normalised");
        assertEquals(0, broken.completedToday(UUID.randomUUID()));
    }
}
