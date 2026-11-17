// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FamilyTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    private static Family.Member m(final int id, final String name, final boolean adult, final Integer partner, final Integer mother,
                                   final Integer father) {
        return new Family.Member(id(id), name, adult, partner == null ? null : id(partner), mother == null ? null : id(mother),
            father == null ? null : id(father), "world", id * 3, 0);
    }

    @Test
    void polishFemaleNames() {
        assertTrue(Family.female("Jadwiga"));
        assertTrue(Family.female("Anna, piekarka"));
        assertFalse(Family.female("Bolesław"));
        assertFalse(Family.female("Kuba"));
        assertFalse(Family.female(""));
    }

    @Test
    void householdIsTheCoupleWithTheirChildren() {
        final List<Family.Member> members = List.of(
            m(1, "Jan", true, 2, null, null), m(2, "Anna", true, 1, null, null),
            m(3, "Staś", false, null, 2, 1), m(4, "Zosia", true, null, 2, 1),
            m(5, "Marek", true, 6, 2, 1), m(6, "Ewa", true, 5, null, null),   // married son: own household
            m(7, "Piotr", true, null, null, null));
        final Map<UUID, UUID> h = Family.households(members);
        assertEquals(h.get(id(1)), h.get(id(2)));
        assertEquals(h.get(id(1)), h.get(id(3)));
        assertEquals(h.get(id(1)), h.get(id(4)), "an unmarried adult child still lives with the parents");
        assertEquals(h.get(id(5)), h.get(id(6)));
        assertTrue(!h.get(id(5)).equals(h.get(id(1))));
        assertEquals(id(7), h.get(id(7)));
    }

    @Test
    void oneSidedPartnerIsNotAMarriage() {
        final List<Family.Member> members = List.of(m(1, "Jan", true, 2, null, null), m(2, "Anna", true, null, null, null));
        final Map<UUID, UUID> h = Family.households(members);
        assertTrue(!h.get(id(1)).equals(h.get(id(2))));
        assertTrue(Family.couples(members).isEmpty());
    }

    @Test
    void marriagesPairWomenAndMenOnceAndNeverRelatives() {
        final List<Family.Member> members = new ArrayList<>(List.of(
            m(1, "Jan", true, null, null, null), m(2, "Anna", true, null, null, null),
            m(3, "Zosia", true, null, 9, 8), m(4, "Staś", true, null, 9, 8),     // siblings
            m(5, "Ewa", true, null, null, null), m(6, "Kasia", false, null, null, null)));
        final List<UUID[]> pairs = Family.marriages(members, 1.0, new Random(1));
        final Set<UUID> seen = new HashSet<>();
        for (final UUID[] p : pairs) {
            assertTrue(seen.add(p[0]) && seen.add(p[1]), "nobody marries twice");
            final Family.Member a = members.stream().filter(x -> x.id().equals(p[0])).findFirst().orElseThrow();
            final Family.Member b = members.stream().filter(x -> x.id().equals(p[1])).findFirst().orElseThrow();
            assertTrue(Family.female(a.name()) && !Family.female(b.name()));
            assertTrue(a.adult() && b.adult());
            assertFalse(Family.related(a, b));
        }
        assertEquals(2, pairs.size(), "two men for three women");
        assertTrue(Family.marriages(members, 0.0, new Random(1)).isEmpty());
    }

    @Test
    void couplesAndGrowingUp() {
        final List<Family.Member> members = List.of(m(1, "Jan", true, 2, null, null), m(2, "Anna", true, 1, null, null));
        final List<UUID[]> couples = Family.couples(members);
        assertEquals(1, couples.size());
        assertEquals(id(2), couples.get(0)[0], "the woman is listed first (mother)");
        assertFalse(Family.grownUp(1000, 1000 + 3_599_999, 3_600_000));
        assertTrue(Family.grownUp(1000, 1000 + 3_600_000, 3_600_000));
        assertTrue(Family.grownUp(0, 5, 3_600_000), "unknown birth = adult");
    }

    @Test
    void childTakesOverAParentsTrade() {
        assertEquals("minecraft:farmer", Family.inheritedProfession("minecraft:farmer", "minecraft:librarian"));
        assertEquals("minecraft:librarian", Family.inheritedProfession("minecraft:none", "minecraft:librarian"));
        assertEquals("minecraft:mason", Family.inheritedProfession("mason", null));
        assertEquals("minecraft:none", Family.inheritedProfession(null, "minecraft:nitwit"));
    }

    @Test
    void longLivesAndNoWaveOfDeathsAfterTheMigration() {
        final Random r = new Random(77);
        final long now = 1_800_000_000_000L;
        int dieOnFirstDay = 0;
        for (int i = 0; i < 5000; i++) {
            final long life = Family.lifespanDays(35, 50, r);
            assertTrue(life >= 35 && life <= 50);
            final long start = Family.initialLifeStart(now, life, 0.4, r);
            final long age = (now - start) / 86_400_000L;
            assertTrue(age >= 0 && age <= life * 0.4 + 1, "existing citizens get at most 40% of their life");
            if (Family.diesOfOldAge(age, life, 0.35, r)) {
                dieOnFirstDay++;
            }
        }
        assertEquals(0, dieOnFirstDay, "nobody dies right after the migration");
        assertEquals(10, Family.lifespanDays(10, 10, r));
    }

    @Test
    void oldAgeOnlyWhenEnabledAndOld() {
        final Random r = new Random(3);
        assertFalse(Family.diesOfOldAge(500, 0, 1.0, r), "lifespan 0 = off");
        assertFalse(Family.diesOfOldAge(10, 120, 1.0, r));
        assertTrue(Family.diesOfOldAge(130, 120, 1.0, r));
    }
}
