// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.geometry.AreaPlanner.Plan;
import pl.medivalrpg.kraje.geometry.AreaPlanner.Skip;
import pl.medivalrpg.kraje.util.ChunkKeys;

class AreaPlannerTest {

    private static final UUID OWN = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    /** Simple world: owners per chunk key and a set of protected chunks. */
    private static final class TestLand implements AreaPlanner.Land {
        final Map<Long, UUID> owners = new HashMap<>();
        final Set<Long> protectedKeys = new HashSet<>();

        TestLand own(final UUID country, final int x, final int z) {
            this.owners.put(k(x, z), country);
            return this;
        }

        TestLand protect(final int x, final int z) {
            this.protectedKeys.add(k(x, z));
            return this;
        }

        Set<Long> territoryOf(final UUID country) {
            final Set<Long> out = new HashSet<>();
            this.owners.forEach((key, owner) -> {
                if (owner.equals(country)) {
                    out.add(key);
                }
            });
            return out;
        }

        @Override
        public UUID ownerAt(final long key) {
            return this.owners.get(key);
        }

        @Override
        public boolean isProtected(final long key) {
            return this.protectedKeys.contains(key);
        }
    }

    private static long k(final int x, final int z) {
        return ChunkKeys.key(x, z);
    }

    @Test
    void squareCoversRadiusAndStartsAtCentre() {
        assertEquals(List.of(k(5, -3)), AreaPlanner.square(5, -3, 0));
        final List<Long> r2 = AreaPlanner.square(0, 0, 2);
        assertEquals(25, r2.size());
        assertEquals(25, new HashSet<>(r2).size());
        assertEquals(k(0, 0), r2.getFirst());
        // the four direct neighbours come right after the centre
        assertEquals(Set.of(k(1, 0), k(-1, 0), k(0, 1), k(0, -1)), new HashSet<>(r2.subList(1, 5)));
        for (final long key : r2) {
            assertTrue(Math.abs(ChunkKeys.x(key)) <= 2 && Math.abs(ChunkKeys.z(key)) <= 2);
        }
        assertEquals(33 * 33, AreaPlanner.square(100, 100, AreaPlanner.MAX_RADIUS).size());
        assertEquals(List.of(k(0, 0), k(3, 0)), AreaPlanner.sortedFrom(List.of(k(3, 0), k(0, 0), k(3, 0)), 0, 0));
    }

    @Test
    void claimSkipsOwnForeignAndProtected() {
        final TestLand land = new TestLand().own(OWN, 0, 0).own(OTHER, 1, 0).own(OTHER, 1, 1).protect(-1, 0);
        final Plan plan = AreaPlanner.claim(OWN, AreaPlanner.square(0, 0, 1), land);
        assertEquals(9 - 4, plan.count());
        assertFalse(plan.chunks().contains(k(1, 0)));
        assertFalse(plan.chunks().contains(k(-1, 0)));
        assertEquals(Skip.OWN, plan.skipped().get(k(0, 0)));
        assertEquals(Skip.FOREIGN, plan.skipped().get(k(1, 0)));
        assertEquals(Skip.PROTECTED, plan.skipped().get(k(-1, 0)));
        assertEquals(Map.of(Skip.OWN, 1, Skip.FOREIGN, 2, Skip.PROTECTED, 1), plan.skipCounts());
        assertEquals(Map.of(OTHER, 2), plan.foreign());
        assertFalse(plan.needsPreview());
        // a protected chunk already owned by someone counts as owned, not protected
        land.own(OTHER, -1, 0);
        assertEquals(Skip.FOREIGN, AreaPlanner.claim(OWN, List.of(k(-1, 0)), land).skipped().get(k(-1, 0)));
    }

    @Test
    void largeClaimNeedsPreview() {
        final Plan plan = AreaPlanner.claim(OWN, AreaPlanner.square(0, 0, 2), new TestLand());
        assertEquals(25, plan.count());
        assertTrue(plan.needsPreview());
        assertTrue(AreaPlanner.claim(OWN, AreaPlanner.square(0, 0, 1), new TestLand()).count() <= AreaPlanner.PREVIEW_THRESHOLD);
    }

    @Test
    void unclaimNeverTakesCapitalOrForeignChunks() {
        final TestLand land = new TestLand();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                land.own(OWN, x, z);
            }
        }
        land.own(OTHER, 2, 0);
        final Plan plan = AreaPlanner.unclaim(OWN, AreaPlanner.square(0, 0, 2), k(0, 0), land);
        assertEquals(8, plan.count());
        assertFalse(plan.chunks().contains(k(0, 0)));
        assertEquals(Skip.CAPITAL, plan.skipped().get(k(0, 0)));
        assertEquals(Skip.NOT_OWN, plan.skipped().get(k(2, 0)));
        assertEquals(25 - 9, plan.skipCounts().get(Skip.NOT_OWN));
        // capital in another world: everything of this world may go
        assertEquals(9, AreaPlanner.unclaim(OWN, AreaPlanner.square(0, 0, 1), null, land).count());
    }

    @Test
    void splitDetection() {
        // a line 0..4 with the capital at 0
        final Set<Long> line = new HashSet<>();
        for (int x = 0; x <= 4; x++) {
            line.add(k(x, 0));
        }
        assertEquals(2, AreaPlanner.splitsInto(line, List.of(k(2, 0))));
        assertEquals(0, AreaPlanner.splitsInto(line, List.of(k(4, 0))));
        assertEquals(0, AreaPlanner.splitsInto(line, List.of(k(3, 0), k(4, 0))));
        assertEquals(3, AreaPlanner.splitsInto(line, List.of(k(1, 0), k(3, 0))));
        // removing a whole exclave is no split
        final Set<Long> withExclave = new HashSet<>(line);
        withExclave.add(k(10, 10));
        assertEquals(0, AreaPlanner.splitsInto(withExclave, List.of(k(10, 10))));
    }

    @Test
    void oneRingAroundSingleChunkAndSquare() {
        final TestLand land = new TestLand().own(OWN, 0, 0);
        final Plan one = AreaPlanner.rings(OWN, land.territoryOf(OWN), 1, land);
        assertEquals(8, one.count(), "a full ring including the corners");
        assertEquals(new HashSet<>(AreaPlanner.square(0, 0, 1)), union(one.chunks(), k(0, 0)));

        final TestLand square = new TestLand();
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                square.own(OWN, x, z);
            }
        }
        assertEquals(12, AreaPlanner.rings(OWN, square.territoryOf(OWN), 1, square).count());

        final Plan two = AreaPlanner.rings(OWN, land.territoryOf(OWN), 2, land);
        assertEquals(24, two.count());
        assertEquals(new HashSet<>(AreaPlanner.square(0, 0, 2)), union(two.chunks(), k(0, 0)));
    }

    @Test
    void ringSkipsForeignAndProtectedAndStaysConnected() {
        final TestLand land = new TestLand().own(OWN, 0, 0).own(OTHER, 1, 0).protect(0, 1);
        final Plan plan = AreaPlanner.rings(OWN, land.territoryOf(OWN), 1, land);
        // (1,1) touches the territory only at a corner, its edge neighbours are foreign/protected → left out
        assertEquals(5, plan.count());
        assertFalse(plan.chunks().contains(k(1, 1)));
        assertEquals(Skip.FOREIGN, plan.skipped().get(k(1, 0)));
        assertEquals(Skip.PROTECTED, plan.skipped().get(k(0, 1)));
        assertEquals(Map.of(OTHER, 1), plan.foreign());
        final Set<Long> after = union(plan.chunks(), k(0, 0));
        assertTrue(ChunkGraph.isConnected(after));

        // walled in on all four sides: the diagonal corners would only touch at a corner → nothing is added
        final TestLand walled = new TestLand().own(OWN, 0, 0).own(OTHER, 1, 0).own(OTHER, -1, 0).protect(0, 1).protect(0, -1);
        final Plan none = AreaPlanner.rings(OWN, walled.territoryOf(OWN), 3, walled);
        assertEquals(0, none.count());
        assertEquals(4, none.skipped().size());
        // every foreign chunk is counted once
        assertEquals(Map.of(OTHER, 2), none.foreign());

        // two rings with a foreign chunk in the second one
        final TestLand twoRings = new TestLand().own(OWN, 0, 0).own(OTHER, 2, 0);
        final Plan grown = AreaPlanner.rings(OWN, twoRings.territoryOf(OWN), 2, twoRings);
        assertEquals(8 + 15, grown.count());
        assertEquals(Map.of(k(2, 0), Skip.FOREIGN), grown.skipped());
        assertFalse(grown.chunks().contains(k(3, 0)));
    }

    @Test
    void outlineOfAreas() {
        assertEquals(4, AreaPlanner.outline(Set.of(k(0, 0))).size());
        assertEquals(6, AreaPlanner.outline(Set.of(k(0, 0), k(1, 0))).size());
        assertEquals(12, AreaPlanner.outline(new HashSet<>(AreaPlanner.square(0, 0, 1))).size());
        final AreaPlanner.Edge north = AreaPlanner.outline(Set.of(k(1, 2))).getFirst();
        assertEquals(new AreaPlanner.Edge(16, 32, 32, 32), north);
    }

    private static Set<Long> union(final List<Long> chunks, final long extra) {
        final Set<Long> out = new HashSet<>(chunks);
        out.add(extra);
        return out;
    }
}
