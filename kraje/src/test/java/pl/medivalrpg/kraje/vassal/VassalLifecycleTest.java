// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.vassal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.VassalInfo;
import pl.medivalrpg.kraje.economy.TributeMath;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.RequestManager;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.Vassalage;

/** Start and end of a vassalage as seen by the manager and the API snapshots (no server needed). */
class VassalLifecycleTest {

    private static final long DAY = 86_400_000L;

    private static Country found(final CountryManager m, final String name, final int x) {
        final ChunkPos cap = new ChunkPos("world", x, 0);
        return m.create(UUID.randomUUID(), "R" + name, name, 0x112233, cap,
            new SpawnPoint("world", cap.centerBlockX(), 64, cap.centerBlockZ(), 0f, 0f), ClaimCause.PLAYER);
    }

    private static CountryManager manager() {
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(), 0L);
        return m;
    }

    @Test
    void startUpdatesSnapshotsAndIndexes() {
        final CountryManager m = manager();
        final Country overlord = found(m, "Aldoria", 0);
        final Country vassal = found(m, "Borsk", 20);
        m.setTributeEstimator(c -> 42L);
        final long now = System.currentTimeMillis();
        m.setVassalage(vassal, new Vassalage(overlord.id(), now, now + 7 * DAY, 0.25, 20));

        assertEquals(overlord.id(), m.overlordOf(vassal.id()));
        assertEquals(Set.of(vassal.id()), m.vassalIdsOf(overlord.id()));
        assertEquals(List.of(vassal), m.vassalsOf(overlord.id()));
        final VassalInfo info = m.vassalInfo(vassal.id());
        assertEquals(0.25, info.tributeShare());
        assertEquals(20, info.tributeMin());
        assertEquals(42, info.dailyTribute(), "estimate from the plugin's estimator");
        assertEquals(now + 7 * DAY, info.until());
        assertNull(m.overlordOf(overlord.id()));
        assertTrue(m.vassalIdsOf(vassal.id()).isEmpty());
    }

    @Test
    void invalidOverlordIsRejected() {
        final CountryManager m = manager();
        final Country a = found(m, "Aldoria", 0);
        assertThrows(IllegalStateException.class, () -> m.setVassalage(a, new Vassalage(a.id(), 0, DAY, 0.25, 20)));
        assertThrows(IllegalStateException.class, () -> m.setVassalage(a, new Vassalage(UUID.randomUUID(), 0, DAY, 0.25, 20)));
    }

    @Test
    void endsByTime() {
        final CountryManager m = manager();
        final Country overlord = found(m, "Aldoria", 0);
        final Country v1 = found(m, "Borsk", 20);
        final Country v2 = found(m, "Celin", 40);
        final long now = 10 * DAY;
        m.setVassalage(v1, new Vassalage(overlord.id(), now - 7 * DAY, now - 1, 0.25, 20));
        m.setVassalage(v2, new Vassalage(overlord.id(), now, now + DAY, 0.25, 20));
        assertEquals(List.of(v1), VassalService.expired(m.allVassals(), now));
        assertEquals(List.of(v1, v2), VassalService.expired(m.allVassals(), now + DAY));
        assertTrue(VassalService.expired(m.allVassals(), now - 2).isEmpty());
        assertTrue(v1.vassalage().expired(now));
        assertEquals(0, v1.vassalage().remainingMillis(now));
        assertEquals(DAY, v2.vassalage().remainingMillis(now));
        // ending = vassalage removed
        m.setVassalage(v1, null);
        assertNull(m.overlordOf(v1.id()));
        assertEquals(Set.of(v2.id()), m.vassalIdsOf(overlord.id()));
    }

    @Test
    void buyoutPriceFromTheRemainingTime() {
        final long now = 100 * DAY;
        final Vassalage v = new Vassalage(UUID.randomUUID(), now - 2 * DAY, now + 5 * DAY - 1000, 0.25, 20);
        v.debt(70);
        final long days = TributeMath.remainingDays(now, v.until());
        assertEquals(5, days, "a started day counts");
        assertEquals(370L, TributeMath.buyout(1.5, 40, days, v.debt()), "1.5 x 40 x 5 + 70");
    }

    @Test
    void releaseAndBuyoutClearTheVassalage() {
        final CountryManager m = manager();
        final Country overlord = found(m, "Aldoria", 0);
        final Country vassal = found(m, "Borsk", 20);
        final long now = System.currentTimeMillis();
        m.setVassalage(vassal, new Vassalage(overlord.id(), now, now + DAY, 0.25, 20));
        // VassalService.release / buyout end the vassalage through the manager like this:
        m.setVassalage(vassal, null);
        assertNull(vassal.vassalage());
        assertNull(m.vassalInfo(vassal.id()));
        assertTrue(m.vassalsOf(overlord.id()).isEmpty());
    }

    @Test
    void disbandingTheOverlordFreesItsVassals() {
        final CountryManager m = manager();
        final Country overlord = found(m, "Aldoria", 0);
        final Country vassal = found(m, "Borsk", 20);
        final long now = System.currentTimeMillis();
        m.setVassalage(vassal, new Vassalage(overlord.id(), now, now + DAY, 0.25, 20));
        m.disband(overlord, ClaimCause.CONQUEST);
        assertNull(vassal.vassalage());
        assertNull(m.overlordOf(vassal.id()));
    }

    @Test
    void loadDropsVassalageToMissingCountriesAndCycles() {
        final CountryManager first = manager();
        final Country a = found(first, "Aldoria", 0);
        final Country b = found(first, "Borsk", 20);
        final Country c = found(first, "Celin", 40);
        a.vassalage(new Vassalage(b.id(), 0, Long.MAX_VALUE, 0.25, 20));
        b.vassalage(new Vassalage(a.id(), 0, Long.MAX_VALUE, 0.25, 20)); // cycle a <-> b
        c.vassalage(new Vassalage(UUID.randomUUID(), 0, Long.MAX_VALUE, 0.25, 20)); // unknown overlord
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(a, b, c), 0L);
        assertNull(c.vassalage());
        assertTrue(a.vassalage() == null || b.vassalage() == null, "cycle broken");
    }

    @Test
    void capitulationOffersExpireAndAreConsumed() {
        final RequestManager r = new RequestManager();
        final UUID loser = UUID.randomUUID();
        final UUID winner = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        r.offerCapitulation(new RequestManager.CapitulationOffer(loser, winner, 7, 0.25, 20, now + 60_000));
        assertNull(r.capitulation(winner, loser, false), "direction matters");
        assertEquals(7, r.capitulation(loser, winner, false).days());
        assertEquals(1, r.capitulationsTo(winner).size());
        assertEquals(7, r.capitulation(loser, winner, true).days());
        assertNull(r.capitulation(loser, winner, false), "consumed");
        r.offerCapitulation(new RequestManager.CapitulationOffer(loser, winner, 3, 0.25, 20, now - 1));
        assertNull(r.capitulation(loser, winner, false), "expired");
        r.offerCapitulation(new RequestManager.CapitulationOffer(loser, winner, 3, 0.25, 20, now + 60_000));
        r.clearProposalsBetween(winner, loser);
        assertNull(r.capitulation(loser, winner, false), "peace or war change voids the offer");
        r.offerCapitulation(new RequestManager.CapitulationOffer(loser, winner, 3, 0.25, 20, now + 60_000));
        r.forgetCountry(winner);
        assertTrue(r.capitulationsTo(winner).isEmpty());
    }

    @Test
    void percentWithoutFloatNoise() {
        assertEquals("25", VassalService.percent(0.25));
        assertEquals("29", VassalService.percent(0.29));
        assertEquals("12.5", VassalService.percent(0.125));
        assertEquals("0", VassalService.percent(0));
    }
}
