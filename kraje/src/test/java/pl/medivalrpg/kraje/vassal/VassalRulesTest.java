// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.vassal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.vassal.VassalRules.Verdict;

/**
 * Restrictions of vassals and overlords. World used by every case:
 * OVERLORD has vassals VASSAL and VASSAL2; OVERLORD is allied with ALLY and at war with ENEMY; NEUTRAL is unrelated.
 */
class VassalRulesTest {

    private static final UUID OVERLORD = UUID.randomUUID();
    private static final UUID VASSAL = UUID.randomUUID();
    private static final UUID VASSAL2 = UUID.randomUUID();
    private static final UUID ALLY = UUID.randomUUID();
    private static final UUID ENEMY = UUID.randomUUID();
    private static final UUID NEUTRAL = UUID.randomUUID();

    private static final VassalRules.Settings DEFAULTS = new VassalRules.Settings(true, true, true, true, 3);
    private static final VassalRules.Settings LAX = new VassalRules.Settings(false, false, false, false, 3);

    private static final class World implements VassalRules.State {
        final Map<UUID, UUID> overlords = new HashMap<>();
        final Map<Set<UUID>, Relation> relations = new HashMap<>();
        final Map<UUID, Integer> debtDays = new HashMap<>();

        @Override
        public UUID overlordOf(final UUID country) {
            return this.overlords.get(country);
        }

        @Override
        public Relation relation(final UUID a, final UUID b) {
            return this.relations.getOrDefault(Set.of(a, b), Relation.NEUTRAL);
        }

        @Override
        public int debtDays(final UUID vassal) {
            return this.debtDays.getOrDefault(vassal, 0);
        }
    }

    private static World world() {
        final World w = new World();
        w.overlords.put(VASSAL, OVERLORD);
        w.overlords.put(VASSAL2, OVERLORD);
        w.relations.put(Set.of(OVERLORD, ALLY), Relation.ALLIANCE);
        w.relations.put(Set.of(OVERLORD, ENEMY), Relation.WAR);
        return w;
    }

    @Test
    void warMatrixWithDefaults() {
        final World w = world();
        // from → to → expected
        final Object[][] cases = {
            {VASSAL, OVERLORD, Verdict.DENIED_OVERLORD},
            {VASSAL, ALLY, Verdict.DENIED_OVERLORD_ALLY},
            {VASSAL, ENEMY, Verdict.ALLOWED},
            {VASSAL, NEUTRAL, Verdict.ALLOWED},
            {VASSAL, VASSAL2, Verdict.ALLOWED},
            {OVERLORD, VASSAL, Verdict.DENIED_OWN_VASSAL},
            {OVERLORD, NEUTRAL, Verdict.ALLOWED},
            {ALLY, VASSAL, Verdict.ALLOWED},
            {ENEMY, VASSAL, Verdict.ALLOWED},
            {NEUTRAL, ALLY, Verdict.ALLOWED},
            {NEUTRAL, OVERLORD, Verdict.ALLOWED},
        };
        for (final Object[] c : cases) {
            assertEquals(c[2], VassalRules.war(DEFAULTS, w, (UUID) c[0], (UUID) c[1]), name((UUID) c[0]) + " -> " + name((UUID) c[1]));
        }
    }

    @Test
    void warMatrixWithEverythingAllowed() {
        final World w = world();
        assertEquals(Verdict.ALLOWED, VassalRules.war(LAX, w, VASSAL, OVERLORD));
        assertEquals(Verdict.ALLOWED, VassalRules.war(LAX, w, VASSAL, ALLY));
        assertEquals(Verdict.ALLOWED, VassalRules.war(LAX, w, OVERLORD, VASSAL));
    }

    @Test
    void eachSwitchOnItsOwn() {
        final World w = world();
        final VassalRules.Settings onlyOverlord = new VassalRules.Settings(true, false, false, false, 3);
        assertEquals(Verdict.DENIED_OVERLORD, VassalRules.war(onlyOverlord, w, VASSAL, OVERLORD));
        assertEquals(Verdict.ALLOWED, VassalRules.war(onlyOverlord, w, VASSAL, ALLY));
        final VassalRules.Settings onlyAllies = new VassalRules.Settings(false, true, false, false, 3);
        assertEquals(Verdict.ALLOWED, VassalRules.war(onlyAllies, w, VASSAL, OVERLORD));
        assertEquals(Verdict.DENIED_OVERLORD_ALLY, VassalRules.war(onlyAllies, w, VASSAL, ALLY));
    }

    @Test
    void overlordMayPunishDebtorsForFree() {
        final World w = world();
        w.debtDays.put(VASSAL, 2);
        assertEquals(Verdict.DENIED_OWN_VASSAL, VassalRules.war(DEFAULTS, w, OVERLORD, VASSAL), "2 < 3 days");
        assertEquals(Verdict.ALLOWED, VassalRules.war(LAX, w, OVERLORD, VASSAL), "allowed, but with cost and cooldown");
        w.debtDays.put(VASSAL, 3);
        assertEquals(Verdict.ALLOWED_FREE, VassalRules.war(DEFAULTS, w, OVERLORD, VASSAL));
        assertEquals(Verdict.ALLOWED_FREE, VassalRules.war(LAX, w, OVERLORD, VASSAL));
        assertEquals(Verdict.DENIED_OVERLORD, VassalRules.war(DEFAULTS, w, VASSAL, OVERLORD), "debt does not free the vassal");
        assertEquals(Verdict.ALLOWED, VassalRules.war(DEFAULTS, w, NEUTRAL, VASSAL), "only the overlord gets the free war");
        assertTrue(Verdict.ALLOWED_FREE.allowed());
        assertFalse(Verdict.DENIED_OWN_VASSAL.allowed());
    }

    @Test
    void allianceMatrix() {
        final World w = world();
        final Object[][] cases = {
            {VASSAL, ENEMY, Verdict.DENIED_OVERLORD_ENEMY},
            {ENEMY, VASSAL, Verdict.DENIED_OVERLORD_ENEMY},
            {VASSAL, OVERLORD, Verdict.ALLOWED},
            {VASSAL, ALLY, Verdict.ALLOWED},
            {VASSAL, NEUTRAL, Verdict.ALLOWED},
            {VASSAL, VASSAL2, Verdict.ALLOWED},
            {NEUTRAL, ENEMY, Verdict.ALLOWED},
            {OVERLORD, NEUTRAL, Verdict.ALLOWED},
        };
        for (final Object[] c : cases) {
            assertEquals(c[2], VassalRules.alliance(DEFAULTS, w, (UUID) c[0], (UUID) c[1]), name((UUID) c[0]) + " + " + name((UUID) c[1]));
        }
        assertEquals(Verdict.ALLOWED, VassalRules.alliance(LAX, w, VASSAL, ENEMY));
    }

    @Test
    void independentCountriesAreNotRestricted() {
        final World w = new World();
        assertEquals(Verdict.ALLOWED, VassalRules.war(DEFAULTS, w, NEUTRAL, ENEMY));
        assertEquals(Verdict.ALLOWED, VassalRules.alliance(DEFAULTS, w, NEUTRAL, ENEMY));
    }

    private static String name(final UUID id) {
        if (id.equals(OVERLORD)) {
            return "OVERLORD";
        }
        if (id.equals(VASSAL)) {
            return "VASSAL";
        }
        if (id.equals(VASSAL2)) {
            return "VASSAL2";
        }
        if (id.equals(ALLY)) {
            return "ALLY";
        }
        return id.equals(ENEMY) ? "ENEMY" : "NEUTRAL";
    }
}
