// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.api.event.CountryCreateEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.api.event.RelationChangeEvent;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Events, indexes and snapshots of the manager (no server needed). */
class CountryManagerEventsTest {

    private final List<Event> events = new ArrayList<>();

    private CountryManager manager() {
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(), 0L);
        m.setEventSink(this.events::add);
        return m;
    }

    private static SpawnPoint spawn(final ChunkPos p) {
        return new SpawnPoint(p.world(), p.centerBlockX(), 64, p.centerBlockZ(), 0f, 0f);
    }

    @Test
    void createClaimAndRelationFireEventsAfterTheChange() {
        final CountryManager m = this.manager();
        final ChunkPos capA = new ChunkPos("world", 0, 0);
        final Country a = m.create(UUID.randomUUID(), "Gracz", "Aldoria", 0xFF0000, capA, spawn(capA), ClaimCause.PLAYER);
        assertInstanceOf(CountryCreateEvent.class, this.events.get(0));
        final ChunkOwnerChangeEvent capital = (ChunkOwnerChangeEvent) this.events.get(1);
        assertNull(capital.oldOwner());
        assertEquals(a.id(), capital.newOwner());

        final ChunkPos capB = new ChunkPos("world", 10, 0);
        final Country b = m.createNpc("Norvik", 0x00FF00, capB, spawn(capB), "Król", ClaimCause.NPC);
        assertTrue(((CountryCreateEvent) this.events.get(2)).npc());
        assertTrue(m.info(b.id()).npc());
        assertEquals("Król", m.info(b.id()).rulerName());

        m.claim(a, "world", ChunkKeys.key(1, 0), ClaimCause.PLAYER);
        assertEquals(a.id(), m.ownerAt("world", ChunkKeys.key(1, 0)));
        assertEquals(2, m.info(a.id()).chunkCount(), "snapshot already updated when the event fires");

        this.events.clear();
        m.setRelation(a, b, Relation.WAR);
        final RelationChangeEvent rel = (RelationChangeEvent) this.events.get(0);
        assertEquals(Relation.NEUTRAL, rel.oldRelation());
        assertEquals(Relation.WAR, rel.newRelation());
        assertTrue(a.warSinceMap().containsKey(b.id()));
        m.setRelation(a, b, Relation.WAR);
        assertEquals(1, this.events.size(), "no event when nothing changes");
        m.setRelation(a, b, Relation.NEUTRAL);
        assertTrue(a.warEndedAtMap().containsKey(b.id()));
        assertTrue(b.warEndedAtMap().containsKey(a.id()));
    }

    @Test
    void conquestTransfersOneChunkAndKeepsIndexesConsistent() {
        final CountryManager m = this.manager();
        final ChunkPos capA = new ChunkPos("world", 0, 0);
        final Country a = m.create(UUID.randomUUID(), "A", "Aldoria", 1, capA, spawn(capA), ClaimCause.PLAYER);
        final ChunkPos capB = new ChunkPos("world", 3, 0);
        final Country b = m.create(UUID.randomUUID(), "B", "Borsk", 2, capB, spawn(capB), ClaimCause.PLAYER);
        m.claim(b, "world", ChunkKeys.key(2, 0), ClaimCause.PLAYER);
        m.claim(a, "world", ChunkKeys.key(1, 0), ClaimCause.PLAYER);
        this.events.clear();

        m.transferChunk(b, a, "world", ChunkKeys.key(2, 0), ClaimCause.CONQUEST);
        final ChunkOwnerChangeEvent e = (ChunkOwnerChangeEvent) this.events.get(0);
        assertEquals(b.id(), e.oldOwner());
        assertEquals(a.id(), e.newOwner());
        assertEquals(ClaimCause.CONQUEST, e.cause());
        assertEquals(2, e.cx());
        assertEquals(a.id(), m.ownerAt("world", ChunkKeys.key(2, 0)));
        assertTrue(a.claimsIn("world").contains(ChunkKeys.key(2, 0)));
        assertTrue(!b.claimsIn("world").contains(ChunkKeys.key(2, 0)));
        assertTrue(ChunkGraph.isConnected(a.claimsIn("world")));
        assertTrue(ChunkGraph.isConnected(b.claimsIn("world")));
        assertEquals(3, m.info(a.id()).chunkCount());
        assertEquals(1, m.info(b.id()).chunkCount());
        assertEquals(Set3.of(ChunkKeys.key(0, 0), ChunkKeys.key(1, 0), ChunkKeys.key(2, 0)), m.claimKeysOf(a.id(), "world"));
    }

    @Test
    void disbandFiresChunkEventsThenDisbandEvent() {
        final CountryManager m = this.manager();
        final ChunkPos cap = new ChunkPos("world", 0, 0);
        final Country c = m.create(UUID.randomUUID(), "A", "Aldoria", 1, cap, spawn(cap), ClaimCause.PLAYER);
        m.claim(c, "world", ChunkKeys.key(0, 1), ClaimCause.PLAYER);
        this.events.clear();
        m.disband(c, ClaimCause.CONQUEST);
        assertEquals(3, this.events.size());
        assertEquals(ClaimCause.CONQUEST, ((ChunkOwnerChangeEvent) this.events.get(0)).cause());
        assertNull(((ChunkOwnerChangeEvent) this.events.get(1)).newOwner());
        assertEquals("Aldoria", ((CountryDisbandEvent) this.events.get(2)).name());
        assertNull(m.info(c.id()));
        assertNull(m.ownerAt("world", cap.key()));
    }

    @Test
    void claimAllTakesOnlyFreeChunksAndFiresAdminEvents() {
        final CountryManager m = this.manager();
        final ChunkPos capA = new ChunkPos("world", 0, 0);
        final Country a = m.create(UUID.randomUUID(), "A", "Aldoria", 1, capA, spawn(capA), ClaimCause.PLAYER);
        final ChunkPos capB = new ChunkPos("world", 2, 0);
        final Country b = m.createNpc("Norvik", 2, capB, spawn(capB), "Król", ClaimCause.NPC);
        this.events.clear();
        final List<Long> claimed = m.claimAll(b, "world",
            List.of(ChunkKeys.key(0, 0), ChunkKeys.key(1, 0), ChunkKeys.key(2, 0), ChunkKeys.key(3, 0)), ClaimCause.ADMIN);
        assertEquals(List.of(ChunkKeys.key(1, 0), ChunkKeys.key(3, 0)), claimed);
        assertEquals(a.id(), m.ownerAt("world", ChunkKeys.key(0, 0)), "a foreign chunk is never taken");
        assertEquals(b.id(), m.ownerAt("world", ChunkKeys.key(1, 0)));
        assertEquals(3, m.info(b.id()).chunkCount());
        assertEquals(2, this.events.size());
        for (final Event e : this.events) {
            final ChunkOwnerChangeEvent change = (ChunkOwnerChangeEvent) e;
            assertNull(change.oldOwner());
            assertEquals(b.id(), change.newOwner());
            assertEquals(ClaimCause.ADMIN, change.cause());
        }
        this.events.clear();
        assertTrue(m.claimAll(b, "world", List.of(ChunkKeys.key(1, 0)), ClaimCause.ADMIN).isEmpty());
        assertTrue(this.events.isEmpty());
    }

    /** Tiny helper (java.util.Set.of with a readable name in assertions). */
    private static final class Set3 {
        static java.util.Set<Long> of(final Long... keys) {
            return java.util.Set.of(keys);
        }
    }
}
