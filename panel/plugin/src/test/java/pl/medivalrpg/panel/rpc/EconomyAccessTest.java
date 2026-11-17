// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.access.Requirement;

/** {@code country.economy}: who sees the economy of which country (server-side, every call). */
class EconomyAccessTest {

    static final UUID OWNER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");
    static final UUID POLANIE = UUID.randomUUID();
    static final UUID WISLANIE = UUID.randomUUID();
    static final UUID MAZOWSZE = UUID.randomUUID(); // NPC
    static final Predicate<UUID> OWNER_ONLY = OWNER::equals;

    private static Actor actor(final String name, final UUID country, final Rank rank) {
        return new Actor(UUID.randomUUID(), name, false, false, Set.of(), country, rank);
    }

    @Test
    void rulerAndAdvisorSeeTheirOwnCountry() {
        assertEquals("RULER", EconomyMethods.access(actor("Bolko", POLANIE, Rank.RULER), POLANIE, false, OWNER_ONLY));
        assertEquals("ADVISOR", EconomyMethods.access(actor("Dobrawa", POLANIE, Rank.ADVISOR), POLANIE, false, OWNER_ONLY));
    }

    @Test
    void citizenAndOutsidersAreRefused() {
        assertNull(EconomyMethods.access(actor("Siemowit", POLANIE, Rank.CITIZEN), POLANIE, false, OWNER_ONLY), "obywatel");
        assertNull(EconomyMethods.access(actor("Wedrowiec", null, null), POLANIE, false, OWNER_ONLY), "bez kraju");
        // the ruler of another country — the case the panel must never leak
        assertNull(EconomyMethods.access(actor("Maciej", WISLANIE, Rank.RULER), POLANIE, false, OWNER_ONLY), "władca obcego kraju");
        assertNull(EconomyMethods.access(actor("Dobrawa", POLANIE, Rank.ADVISOR), WISLANIE, false, OWNER_ONLY), "doradca obcego kraju");
        assertNull(EconomyMethods.access(null, POLANIE, false, OWNER_ONLY));
        assertNull(EconomyMethods.access(actor("Bolko", POLANIE, Rank.RULER), null, false, OWNER_ONLY));
    }

    @Test
    void adminSeesEveryCountry() {
        final Actor admin = new Actor(UUID.randomUUID(), "Admin", true, false, Set.of(), null, null);
        assertEquals("ADMIN", EconomyMethods.access(admin, POLANIE, false, OWNER_ONLY));
        assertEquals("ADMIN", EconomyMethods.access(admin, MAZOWSZE, true, OWNER_ONLY));
    }

    @Test
    void ownerSeesEveryCountryButOnlyByUuid() {
        final Actor owner = new Actor(OWNER, "WorldSteward", false, false, Set.of(), null, null);
        assertEquals("OWNER", EconomyMethods.access(owner, POLANIE, false, OWNER_ONLY));
        assertEquals("OWNER", EconomyMethods.access(owner, MAZOWSZE, true, OWNER_ONLY));
        // same nick, other UUID (offline mode): nothing
        assertNull(EconomyMethods.access(new Actor(UUID.randomUUID(), "WorldSteward", false, false, Set.of(), null, null), POLANIE, false, OWNER_ONLY));
        // Skarbiec missing → the owner check is false → refused
        assertNull(EconomyMethods.access(owner, POLANIE, false, u -> false));
    }

    @Test
    void npcManagersOnlyForTheirNpcCountry() {
        final Actor assigned = new Actor(UUID.randomUUID(), "Ziemowit", false, false, Set.of(MAZOWSZE), null, null);
        assertEquals("NPC_MANAGER", EconomyMethods.access(assigned, MAZOWSZE, true, OWNER_ONLY));
        assertNull(EconomyMethods.access(assigned, POLANIE, false, OWNER_ONLY), "zarządca NPC nie widzi kraju gracza");
        final Actor global = new Actor(UUID.randomUUID(), "Zarzadca", false, true, Set.of(), null, null);
        assertEquals("NPC_MANAGER", EconomyMethods.access(global, MAZOWSZE, true, OWNER_ONLY));
        assertNull(EconomyMethods.access(global, POLANIE, false, OWNER_ONLY));
    }

    @Test
    void ownerCheckIsOnlyAskedWhenNeeded() {
        final AtomicInteger asked = new AtomicInteger();
        final Predicate<UUID> counting = u -> {
            asked.incrementAndGet();
            return false;
        };
        EconomyMethods.access(actor("Bolko", POLANIE, Rank.RULER), POLANIE, false, counting);
        EconomyMethods.access(new Actor(UUID.randomUUID(), "Admin", true, false, Set.of(), null, null), POLANIE, false, counting);
        assertEquals(0, asked.get(), "władca i admin nie pytają Skarbca");
        EconomyMethods.access(actor("Siemowit", POLANIE, Rank.CITIZEN), POLANIE, false, counting);
        assertEquals(1, asked.get());
    }

    @Test
    void methodIsRegisteredAsReadOfOneCountry() {
        final MethodSpec spec = MethodSpec.of("country.economy");
        assertNotNull(spec);
        assertFalse(spec.mutation(), "odczyt — bez audytu i limitu zmian");
        assertTrue(spec.targetsId(), "params.id = kraj");
        assertFalse(spec.npcOnly());
        assertEquals(Requirement.LOGGED_IN, spec.requirement(), "brama ogólna; dostęp rozstrzyga EconomyMethods.access");
    }

    @Test
    void deniedMessageIsPolishAndNamesNoData() {
        assertTrue(EconomyMethods.DENIED.startsWith("Brak dostępu"));
        final RpcException e = new RpcException(EconomyMethods.DENIED);
        assertEquals(EconomyMethods.DENIED, e.getMessage());
    }
}
