// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HousingTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    @Test
    void bedsAreNeverDoubleBookedAndOverflowIsHomeless() {
        final List<Housing.Home> homes = List.of(new Housing.Home(id(100), 2, 0, 0), new Housing.Home(id(101), 4, 20, 0));
        final List<Housing.Resident> people = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            people.add(new Housing.Resident(id(i), null, null, -1, i, 0));
        }
        final Housing.Result r = Housing.assign(people, homes);
        assertTrue(Housing.consistent(r, homes));
        assertEquals(6, r.housed());
        assertEquals(3, r.homeless().size());
        assertTrue(r.residentsOf(id(100)) <= 2);
        assertTrue(r.residentsOf(id(101)) <= 4);
    }

    @Test
    void familyLivesTogether() {
        // a single already lives in the 4-bed house; a family of three (couple + child) arrives
        final UUID household = id(1);
        final List<Housing.Home> homes = List.of(new Housing.Home(id(100), 2, 0, 0), new Housing.Home(id(101), 4, 30, 0));
        final List<Housing.Resident> people = List.of(
            new Housing.Resident(id(9), null, id(101), 0, 30, 0),
            new Housing.Resident(id(1), household, null, -1, 0, 0),
            new Housing.Resident(id(2), household, null, -1, 0, 0),
            new Housing.Resident(id(3), household, null, -1, 0, 0));
        final Housing.Result r = Housing.assign(people, homes);
        assertTrue(Housing.consistent(r, homes));
        final UUID home = r.slots().get(id(1)).home();
        assertEquals(id(101), home, "only the 4-bed house has room for all three");
        assertEquals(home, r.slots().get(id(2)).home());
        assertEquals(home, r.slots().get(id(3)).home());
        assertEquals(new Housing.Slot(id(101), 0), r.slots().get(id(9)), "the single keeps the bed");
    }

    @Test
    void splitFamilyIsReunitedWhenAHouseHasRoom() {
        final UUID household = id(1);
        final List<Housing.Home> homes = List.of(new Housing.Home(id(100), 1, 0, 0), new Housing.Home(id(101), 1, 10, 0),
            new Housing.Home(id(102), 2, 50, 0));
        final List<Housing.Resident> people = List.of(
            new Housing.Resident(id(1), household, id(100), 0, 0, 0),
            new Housing.Resident(id(2), household, id(101), 0, 10, 0));
        final Housing.Result r = Housing.assign(people, homes);
        assertEquals(id(102), r.slots().get(id(1)).home());
        assertEquals(id(102), r.slots().get(id(2)).home());
        assertTrue(Housing.consistent(r, homes));
    }

    @Test
    void assignmentIsStableAcrossRuns() {
        final Random random = new Random(7);
        final List<Housing.Home> homes = new ArrayList<>();
        for (int h = 0; h < 6; h++) {
            homes.add(new Housing.Home(id(1000 + h), 1 + random.nextInt(5), random.nextInt(100), random.nextInt(100)));
        }
        List<Housing.Resident> people = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            people.add(new Housing.Resident(id(i), i % 3 == 0 ? id(i) : id(i - i % 3), null, -1, random.nextInt(100), 0));
        }
        // a run may still consolidate a split family once; then the assignment is a fixed point
        Housing.Result previous = Housing.assign(people, homes);
        boolean stable = false;
        for (int run = 0; run < 4 && !stable; run++) {
            people = withSlots(people, previous);
            final Housing.Result next = Housing.assign(people, homes);
            assertTrue(Housing.consistent(next, homes));
            stable = next.slots().equals(previous.slots());
            previous = next;
        }
        assertTrue(stable, "the assignment settles and then nobody moves");
        // a settled town: exactly the same result again
        final List<Housing.Resident> settled = withSlots(people, previous);
        assertEquals(previous.slots(), Housing.assign(settled, homes).slots());
        assertEquals(previous.homeless(), Housing.assign(settled, homes).homeless());
    }

    private static List<Housing.Resident> withSlots(final List<Housing.Resident> people, final Housing.Result r) {
        final List<Housing.Resident> out = new ArrayList<>();
        for (final Housing.Resident p : people) {
            final Housing.Slot s = r.slots().get(p.id());
            out.add(new Housing.Resident(p.id(), p.household(), s == null ? null : s.home(), s == null ? -1 : s.bed(), p.x(), p.z()));
        }
        return out;
    }

    @Test
    void invalidBedsAreDroppedAfterABuildingShrinks() {
        final List<Housing.Home> homes = List.of(new Housing.Home(id(100), 1, 0, 0));
        final List<Housing.Resident> people = List.of(
            new Housing.Resident(id(1), null, id(100), 0, 0, 0),
            new Housing.Resident(id(2), null, id(100), 1, 0, 0),   // bed 1 was destroyed
            new Housing.Resident(id(3), null, id(999), 0, 0, 0));  // house demolished
        final Housing.Result r = Housing.assign(people, homes);
        assertEquals(new Housing.Slot(id(100), 0), r.slots().get(id(1)));
        assertNull(r.slots().get(id(2)));
        assertNull(r.slots().get(id(3)));
        assertEquals(List.of(id(2), id(3)), r.homeless());
    }

    @Test
    void randomTownsStayConsistent() {
        final Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            final List<Housing.Home> homes = new ArrayList<>();
            int beds = 0;
            for (int h = 0; h < 1 + random.nextInt(8); h++) {
                final int b = random.nextInt(6);
                beds += b;
                homes.add(new Housing.Home(id(10_000 + h), b, random.nextInt(64), random.nextInt(64)));
            }
            final List<Housing.Resident> people = new ArrayList<>();
            final int n = random.nextInt(30);
            for (int i = 0; i < n; i++) {
                final UUID home = random.nextBoolean() ? id(10_000 + random.nextInt(9)) : null;
                people.add(new Housing.Resident(id(i), id(i / 3), home, random.nextInt(7) - 1, random.nextInt(64), random.nextInt(64)));
            }
            final Housing.Result r = Housing.assign(people, homes);
            assertTrue(Housing.consistent(r, homes));
            assertEquals(Math.min(n, beds), r.housed(), "every free bed is used while somebody is homeless");
            final Set<UUID> all = new HashSet<>(r.slots().keySet());
            all.addAll(r.homeless());
            assertEquals(n, all.size());
            assertNotNull(r.homeless());
        }
    }
}
