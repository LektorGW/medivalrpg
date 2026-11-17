// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.service.LudnoscData;

class QueueRulesTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static QueuedOrder order(final String type, final int x, final long cost) {
        return QueuedOrder.of(C, type, "world", x, 64, 0, 0, cost, "Gracz");
    }

    @Test
    void admissionStartsQueuesOrRefuses() {
        assertEquals(QueueRules.Admission.START, QueueRules.admit(0, 0, 2, 5));
        assertEquals(QueueRules.Admission.START, QueueRules.admit(1, 0, 2, 5));
        assertEquals(QueueRules.Admission.QUEUE, QueueRules.admit(2, 0, 2, 5), "slots full → queue");
        assertEquals(QueueRules.Admission.QUEUE, QueueRules.admit(1, 1, 2, 5), "nobody jumps the queue");
        assertEquals(QueueRules.Admission.QUEUE, QueueRules.admit(2, 4, 2, 5));
        assertEquals(QueueRules.Admission.FULL, QueueRules.admit(2, 5, 2, 5), "queue limit");
        assertEquals(QueueRules.Admission.FULL, QueueRules.admit(2, 0, 2, 0), "queue disabled");
    }

    @Test
    void startableFollowsFreeSlots() {
        assertEquals(0, QueueRules.startable(2, 3, 2));
        assertEquals(1, QueueRules.startable(1, 3, 2), "one site finished → one queued order starts");
        assertEquals(2, QueueRules.startable(0, 3, 2));
        assertEquals(1, QueueRules.startable(0, 1, 2));
        assertEquals(0, QueueRules.startable(3, 1, 2), "config lowered below running sites");
    }

    @Test
    void reservationCollisions() {
        final BlockBox a = new BlockBox("world", 0, 60, 0, 10, 70, 10);
        final List<BlockBox> taken = new ArrayList<>(List.of(a));
        taken.add(null); // order whose template vanished
        assertTrue(QueueRules.collides(new BlockBox("world", 10, 60, 10, 20, 70, 20), taken), "shared corner");
        assertFalse(QueueRules.collides(new BlockBox("world", 11, 60, 0, 20, 70, 10), taken), "neighbour");
        assertFalse(QueueRules.collides(new BlockBox("world", 11, 71, 0, 20, 80, 10), taken));
        assertFalse(QueueRules.collides(new BlockBox("nether", 0, 60, 0, 10, 70, 10), taken), "other world");
        assertFalse(QueueRules.collides(a, List.of()));
    }

    @Test
    void refunds() {
        assertEquals(4000, QueueRules.refund(4000, true, 0.5), "queued orders: 100 %");
        assertEquals(2000, QueueRules.refund(4000, false, 0.5), "running site: refund-on-cancel");
        assertEquals(4000, QueueRules.refund(4000, false, 1.0), "obstructed site: full");
        assertEquals(0, QueueRules.refund(4000, false, 0));
        assertEquals(0, QueueRules.refund(0, true, 1), "free NPC order");
        assertEquals(4000, QueueRules.refund(4000, false, 7), "fraction clamped");
    }

    @Test
    void firstValidStartsAndInvalidOnesAreRejected() {
        final QueuedOrder a = order("dom", 0, 4000);
        final QueuedOrder b = order("chata", 50, 1500);
        final QueuedOrder c = order("kuznia", 100, 3500);
        final List<QueuedOrder> q = new ArrayList<>(List.of(a, b, c));
        final List<QueuedOrder> rejected = new ArrayList<>();
        // "a" lost its land meanwhile → skipped (refund), "b" starts, "c" keeps waiting
        final QueuedOrder next = QueueRules.pollFirstValid(q, o -> o != a, rejected::add);
        assertEquals(b, next);
        assertEquals(List.of(a), rejected);
        assertEquals(List.of(c), q);
        assertNull(QueueRules.pollFirstValid(new ArrayList<>(List.of(c)), o -> false, rejected::add));
        assertEquals(2, rejected.size());
    }

    @Test
    void moveSwapsNeighbours() {
        final List<String> l = new ArrayList<>(List.of("a", "b", "c"));
        assertTrue(QueueRules.move(l, 2, -1));
        assertEquals(List.of("a", "c", "b"), l);
        assertTrue(QueueRules.move(l, 0, 1));
        assertEquals(List.of("c", "a", "b"), l);
        assertFalse(QueueRules.move(l, 0, -1), "already first");
        assertFalse(QueueRules.move(l, 2, 1), "already last");
        assertFalse(QueueRules.move(l, 5, -1));
    }

    @Test
    void registryQueueKeepsOrder() {
        final LudnoscData data = new LudnoscData();
        final QueuedOrder a = order("dom", 0, 4000);
        final QueuedOrder b = order("chata", 50, 1500);
        final QueuedOrder c = order("kuznia", 100, 3500);
        data.enqueue(a);
        data.enqueue(b);
        data.enqueue(c);
        assertEquals(3, data.queueSize(C));
        assertEquals(List.of(a, b, c), data.queue(C));
        assertTrue(data.moveQueued(C, 2, -1));
        assertEquals(List.of(a, c, b), data.queue(C));
        assertEquals(c, data.removeQueuedAt(C, 1));
        assertNull(data.removeQueuedAt(C, 7));
        assertTrue(data.removeQueued(a));
        assertFalse(data.removeQueued(a));
        assertEquals(List.of(b), data.queue(C));
        assertTrue(data.queuedCountries().contains(C));
        assertEquals(List.of(b), data.clearQueue(C));
        assertEquals(0, data.queueSize(C));
        assertTrue(data.queuedCountries().isEmpty());
        assertEquals(List.of(), data.queue(null));
    }
}
