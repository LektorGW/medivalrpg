// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.StoredInvite;

/** Persistent invitations of citizens of a fallen country: expiry, replacement, cleanup. */
class PendingInvitesTest {

    private static final long HOUR = 3_600_000L;

    private static StoredInvite invite(final UUID player, final UUID country, final long createdAt, final long expiresAt) {
        return new StoredInvite(player, "Gracz", country, createdAt, expiresAt, StoredInvite.CONQUEST);
    }

    @Test
    void expiresAfter24Hours() {
        final PendingInvites invites = new PendingInvites();
        final UUID player = UUID.randomUUID();
        final UUID country = UUID.randomUUID();
        final long t0 = 1_000_000L;
        invites.add(invite(player, country, t0, t0 + 24 * HOUR));
        assertTrue(invites.has(player, country, t0));
        assertTrue(invites.has(player, country, t0 + 24 * HOUR - 1));
        assertFalse(invites.has(player, country, t0 + 24 * HOUR), "valid strictly before expiresAt");
        assertEquals(1, invites.of(player, t0 + HOUR).size());
        assertTrue(invites.of(player, t0 + 25 * HOUR).isEmpty());
        assertFalse(invites.has(UUID.randomUUID(), country, t0), "other player");
        assertFalse(invites.has(player, UUID.randomUUID(), t0), "other country");
        assertTrue(invites.prune(t0 + 24 * HOUR));
        assertEquals(0, invites.size());
        assertFalse(invites.prune(t0 + 48 * HOUR), "nothing more to prune");
    }

    @Test
    void sameCountryReplacesOtherCountriesStay() {
        final PendingInvites invites = new PendingInvites();
        final UUID player = UUID.randomUUID();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        invites.add(invite(player, a, 0, 10));
        invites.add(invite(player, a, 5, 100));
        invites.add(invite(player, b, 0, 100));
        assertEquals(2, invites.size());
        assertTrue(invites.has(player, a, 50), "the newer invitation replaced the old one");
        assertTrue(invites.remove(player, a));
        assertFalse(invites.remove(player, a));
        assertTrue(invites.has(player, b, 50));
        assertTrue(invites.removeCountry(b));
        assertEquals(0, invites.size());
    }

    @Test
    void loadSkipsExpired() {
        final PendingInvites invites = new PendingInvites();
        final long now = System.currentTimeMillis();
        final UUID p = UUID.randomUUID();
        invites.load(List.of(invite(p, UUID.randomUUID(), now - 2 * HOUR, now - HOUR),
            invite(p, UUID.randomUUID(), now, now + HOUR)), now);
        assertEquals(1, invites.size());
    }

    @Test
    void managerDropsInvitationsWhenPlayerJoinsOrCountryDisappears() {
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(), 0L);
        final ChunkPos cap = new ChunkPos("world", 0, 0);
        final Country winner = m.create(UUID.randomUUID(), "Krol", "Aldoria", 1, cap,
            new SpawnPoint("world", 8, 64, 8, 0f, 0f), ClaimCause.PLAYER);
        final ChunkPos cap2 = new ChunkPos("world", 20, 0);
        final Country other = m.create(UUID.randomUUID(), "Inny", "Borsk", 2, cap2,
            new SpawnPoint("world", 328, 64, 8, 0f, 0f), ClaimCause.PLAYER);
        final UUID p1 = UUID.randomUUID();
        final UUID p2 = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        m.addStoredInvite(invite(p1, winner.id(), now, now + 24 * HOUR));
        m.addStoredInvite(invite(p1, other.id(), now, now + 24 * HOUR));
        m.addStoredInvite(invite(p2, winner.id(), now, now + 24 * HOUR));
        assertTrue(m.hasStoredInvite(p1, winner.id()));
        assertEquals(2, m.storedInvitesOf(p1).size());

        m.addMember(winner, p1, "P1", Rank.CITIZEN);
        assertTrue(m.storedInvitesOf(p1).isEmpty(), "joined a country: all invitations gone");
        m.disband(winner, ClaimCause.ADMIN);
        assertFalse(m.hasStoredInvite(p2, winner.id()), "country gone: its invitations gone");
        assertEquals(0, m.storedInvites().size());
    }

    @Test
    void managerLoadFiltersUnknownCountriesAndMembers() {
        final CountryManager first = new CountryManager(Logger.getAnonymousLogger());
        first.loadAll(List.of(), 0L);
        final ChunkPos cap = new ChunkPos("world", 0, 0);
        final UUID ruler = UUID.randomUUID();
        final Country c = first.create(ruler, "Krol", "Aldoria", 1, cap, new SpawnPoint("world", 8, 64, 8, 0f, 0f), ClaimCause.PLAYER);
        final long now = System.currentTimeMillis();
        final UUID free = UUID.randomUUID();
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(c), 0L, List.of(
            invite(free, c.id(), now, now + HOUR),
            invite(free, UUID.randomUUID(), now, now + HOUR),   // unknown country
            invite(ruler, c.id(), now, now + HOUR),             // already a member
            invite(UUID.randomUUID(), c.id(), now - 2 * HOUR, now - HOUR))); // expired
        assertEquals(1, m.storedInvites().size());
        assertTrue(m.hasStoredInvite(free, c.id()));
    }
}
