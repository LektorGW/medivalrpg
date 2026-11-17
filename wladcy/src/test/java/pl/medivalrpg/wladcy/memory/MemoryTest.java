// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryTest {

    @Test
    void exchangesAndNotesAreTrimmedOldestFirst() {
        final PairMemory m = new PairMemory();
        for (int i = 1; i <= 10; i++) {
            m.addExchange(new PairMemory.Exchange(i, "p" + i, "r" + i), 6);
            m.addNote("n" + i, 3);
        }
        assertEquals(6, m.exchanges.size());
        assertEquals("p5", m.exchanges.get(0).player);
        assertEquals("p10", m.exchanges.get(5).player);
        assertEquals(java.util.List.of("n8", "n9", "n10"), m.notes);
        m.addExchange(new PairMemory.Exchange(11, "x", "y"), 0);
        assertTrue(m.exchanges.isEmpty(), "0 = remember no exchanges");
    }

    @Test
    void sympathyIsClamped() {
        final PairMemory m = new PairMemory();
        m.addSympathy(500);
        assertEquals(100, m.sympathy);
        m.addSympathy(-1000);
        assertEquals(-100, m.sympathy);
        assertEquals("wrogie", PairMemory.attitudeWord(m.sympathy));
        assertEquals("obojętne", PairMemory.attitudeWord(0));
        assertEquals("serdeczne", PairMemory.attitudeWord(80));
    }

    @Test
    void summary() {
        final PairMemory m = new PairMemory();
        assertEquals("pierwsze spotkanie", m.summary(v -> v + " md"));
        m.tributesPaid = 2;
        m.tributePaidTotal = 700;
        m.tributesRefused = 1;
        final String s = m.summary(v -> v + " md");
        assertTrue(s.contains("zapłacił trybut 2 razy (razem 700 md)"), s);
        assertTrue(s.contains("odmówił trybutu 1 raz"), s);
    }

    @Test
    void storeSnapshotIsIndependentAndNormalizesLoaded() {
        final UUID c = UUID.randomUUID();
        final UUID p = UUID.randomUUID();
        final PairMemory broken = new PairMemory();
        broken.exchanges = null;
        broken.notes = null;
        broken.sympathy = 999;
        final Map<String, PairMemory> loaded = new HashMap<>();
        loaded.put(MemoryStore.key(c, p), broken);
        loaded.put("junk", null);
        final MemoryStore store = new MemoryStore(loaded);
        assertEquals(1, store.size());
        assertEquals(100, store.peek(c, p).sympathy);
        assertTrue(store.peek(c, p).exchanges.isEmpty());

        store.get(c, p).addNote("obraził władcę", 5);
        assertTrue(store.dirty());
        final Map<String, PairMemory> snap = store.snapshot();
        assertFalse(store.dirty());
        store.get(c, p).addNote("drugi raz", 5);
        assertEquals(1, snap.get(MemoryStore.key(c, p)).notes.size(), "snapshot not affected by later changes");

        store.forgetCountry(c);
        assertNull(store.peek(c, p));
    }
}
