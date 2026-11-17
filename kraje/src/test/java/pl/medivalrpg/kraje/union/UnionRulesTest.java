// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.union.UnionRules.Verdict;

/**
 * Personal union rules. World: A and B are free player countries; NPC is an NPC country; VASSAL is a vassal of
 * OVERLORD; U1+U2 are already in a union; ENEMY is at war with A.
 */
class UnionRulesTest {

    private static final long HOUR = 3_600_000L;
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID NPC = UUID.randomUUID();
    private static final UUID VASSAL = UUID.randomUUID();
    private static final UUID OVERLORD = UUID.randomUUID();
    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();
    private static final UUID ENEMY = UUID.randomUUID();

    private static final UnionRules.Settings DEFAULTS = new UnionRules.Settings(true, true, 72 * HOUR, 48 * HOUR, true, false);

    private static final class World implements UnionRules.State {
        final Set<UUID> countries = new HashSet<>(Set.of(A, B, NPC, VASSAL, OVERLORD, U1, U2, ENEMY));
        final Set<UUID> npcs = new HashSet<>(Set.of(NPC));
        final Map<UUID, UUID> overlords = new HashMap<>(Map.of(VASSAL, OVERLORD));
        final Map<UUID, UUID> unions = new HashMap<>(Map.of(U1, U2, U2, U1));
        final Map<Set<UUID>, Relation> relations = new HashMap<>(Map.of(Set.of(A, ENEMY), Relation.WAR,
            Set.of(U1, U2), Relation.ALLIANCE));
        final Map<Set<UUID>, Long> ended = new HashMap<>();

        @Override
        public boolean exists(final UUID country) {
            return this.countries.contains(country);
        }

        @Override
        public boolean npc(final UUID country) {
            return this.npcs.contains(country);
        }

        @Override
        public UUID overlordOf(final UUID country) {
            return this.overlords.get(country);
        }

        @Override
        public UUID unionPartnerOf(final UUID country) {
            return this.unions.get(country);
        }

        @Override
        public Relation relation(final UUID a, final UUID b) {
            return this.relations.getOrDefault(Set.of(a, b), Relation.NEUTRAL);
        }

        @Override
        public long unionEndedAt(final UUID a, final UUID b) {
            return this.ended.getOrDefault(Set.of(a, b), 0L);
        }
    }

    @Test
    void formationMatrix() {
        final World w = new World();
        final long now = 1_000 * HOUR;
        assertEquals(Verdict.ALLOWED, UnionRules.form(DEFAULTS, w, A, B, now));
        assertEquals(Verdict.ALLOWED, UnionRules.form(DEFAULTS, w, A, NPC, now));
        assertEquals(Verdict.SAME_COUNTRY, UnionRules.form(DEFAULTS, w, A, A, now));
        assertEquals(Verdict.NOT_FOUND, UnionRules.form(DEFAULTS, w, A, UUID.randomUUID(), now));
        assertEquals(Verdict.NPC_HOME, UnionRules.form(DEFAULTS, w, NPC, A, now));
        assertEquals(Verdict.HOME_IN_UNION, UnionRules.form(DEFAULTS, w, U1, A, now));
        assertEquals(Verdict.PARTNER_IN_UNION, UnionRules.form(DEFAULTS, w, A, U2, now));
        assertEquals(Verdict.AT_WAR, UnionRules.form(DEFAULTS, w, A, ENEMY, now));
        assertEquals(Verdict.HOME_VASSAL, UnionRules.form(DEFAULTS, w, VASSAL, B, now));
        assertEquals(Verdict.PARTNER_VASSAL, UnionRules.form(DEFAULTS, w, B, VASSAL, now));
        // an overlord cannot absorb its own vassal through a union either
        assertEquals(Verdict.PARTNER_VASSAL, UnionRules.form(DEFAULTS, w, OVERLORD, VASSAL, now));

        final UnionRules.Settings noNpc = new UnionRules.Settings(true, false, 0, 0, true, false);
        assertEquals(Verdict.NPC_PARTNER, UnionRules.form(noNpc, w, A, NPC, now));
        final UnionRules.Settings off = new UnionRules.Settings(false, true, 0, 0, true, false);
        assertEquals(Verdict.DISABLED, UnionRules.form(off, w, A, B, now));
    }

    @Test
    void reformCooldownAfterTheEndOfAUnion() {
        final World w = new World();
        final long ended = 500 * HOUR;
        w.ended.put(Set.of(A, B), ended);
        assertEquals(Verdict.COOLDOWN, UnionRules.form(DEFAULTS, w, A, B, ended + 47 * HOUR));
        assertEquals(Verdict.COOLDOWN, UnionRules.form(DEFAULTS, w, B, A, ended + 47 * HOUR));
        assertEquals(Verdict.ALLOWED, UnionRules.form(DEFAULTS, w, A, B, ended + 48 * HOUR));
        assertEquals(HOUR, UnionRules.reformAvailableIn(DEFAULTS, ended, ended + 47 * HOUR));
        assertEquals(0, UnionRules.reformAvailableIn(DEFAULTS, 0, ended));
    }

    @Test
    void minimumDurationBeforeBreaking() {
        final long since = 10 * HOUR;
        assertEquals(since + 72 * HOUR, UnionRules.breakableAt(DEFAULTS, since));
        assertFalse(UnionRules.canBreak(DEFAULTS, since, since + 71 * HOUR));
        assertTrue(UnionRules.canBreak(DEFAULTS, since, since + 72 * HOUR));
    }

    @Test
    void partnersCannotFightAndJoinWars() {
        final World w = new World();
        assertTrue(UnionRules.warForbidden(w, U1, U2));
        assertTrue(UnionRules.warForbidden(w, U2, U1));
        assertFalse(UnionRules.warForbidden(w, U1, A));

        // A attacks U1: U2 defends (neutral to A)
        assertEquals(U2, UnionRules.joiningDefender(DEFAULTS, w, A, U1));
        // offensive join is off by default
        assertNull(UnionRules.joiningAttacker(DEFAULTS, w, U1, A));
        final UnionRules.Settings offensive = new UnionRules.Settings(true, true, 0, 0, true, true);
        assertEquals(U2, UnionRules.joiningAttacker(offensive, w, U1, A));
        // no defence when switched off
        final UnionRules.Settings noDefence = new UnionRules.Settings(true, true, 0, 0, false, false);
        assertNull(UnionRules.joiningDefender(noDefence, w, A, U1));
        // the partner allied with the aggressor (or already at war) stays out
        w.relations.put(Set.of(U2, B), Relation.ALLIANCE);
        assertNull(UnionRules.joiningDefender(DEFAULTS, w, B, U1));
        w.relations.put(Set.of(U2, ENEMY), Relation.WAR);
        assertNull(UnionRules.joiningDefender(DEFAULTS, w, ENEMY, U1));
        // vassal rules win: a partner that is the vassal of the aggressor does not fight it
        w.overlords.put(U2, OVERLORD);
        assertNull(UnionRules.joiningDefender(DEFAULTS, w, OVERLORD, U1));
        // a country without a union has nobody to join
        assertNull(UnionRules.joiningDefender(DEFAULTS, w, U1, A));
    }

    @Test
    void successorIsRegentThenHighestRankThenOldest() {
        final ChunkPos cap = new ChunkPos("world", 0, 0);
        final UUID monarch = UUID.randomUUID();
        final UUID regent = UUID.randomUUID();
        final UUID advisor = UUID.randomUUID();
        final UUID citizen = UUID.randomUUID();
        final Country c = new Country(UUID.randomUUID(), "Borsk", 0, "", monarch, 0L, cap,
            new SpawnPoint("world", 0, 64, 0, 0f, 0f));
        c.membersMap().put(monarch, new Member(monarch, "Krol", Rank.RULER, 50L));
        c.membersMap().put(citizen, new Member(citizen, "Chlop", Rank.CITIZEN, 1L));
        c.membersMap().put(advisor, new Member(advisor, "Rada", Rank.ADVISOR, 30L));
        c.membersMap().put(regent, new Member(regent, "Namiestnik", Rank.ADVISOR, 40L));

        assertEquals(regent, UnionRules.successor(c, monarch, regent).uuid());
        c.membersMap().remove(regent);
        assertEquals(advisor, UnionRules.successor(c, monarch, regent).uuid());
        c.membersMap().remove(advisor);
        assertEquals(citizen, UnionRules.successor(c, monarch, null).uuid());
        c.membersMap().remove(citizen);
        assertNull(UnionRules.successor(c, monarch, regent), "only the monarch is left");
    }
}
