// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** New settlements, the high road between them, the kingdom map. */
class KingdomTest {

    /** Own land: chunks -8..8 × -2..2, flat; a lake in chunks x ≥ 6. */
    static final class Realm implements TerrainView {
        final Set<Long> claims = new HashSet<>();

        Realm() {
            for (int cx = -8; cx <= 8; cx++) {
                for (int cz = -2; cz <= 2; cz++) {
                    this.claims.add(KingdomPlanner.key(cx, cz));
                }
            }
        }

        @Override
        public int height(final int x, final int z) {
            return 64 + (x < -40 ? (-40 - x) * 2 : 0); // steep hills in the west
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return x >= 96;
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return this.claims.contains(KingdomPlanner.key(x >> 4, z >> 4));
        }
    }

    @Test
    void newSettlementIsFarFlatAndDry() {
        final Realm r = new Realm();
        final long site = KingdomPlanner.site(r.claims, List.of(new int[] {8, 8}), 64, r);
        assertNotEquals(Long.MIN_VALUE, site);
        final int x = (int) (site >> 32) * 16 + 8;
        assertTrue(Math.abs(x - 8) >= 64, "far from the capital: " + x);
        assertTrue(x < 84, "not at the lake: " + x);
        assertTrue(x > 0, "the flat side, not the hills: " + x);
        // too small a country: nowhere
        assertEquals(Long.MIN_VALUE, KingdomPlanner.site(r.claims, List.of(new int[] {8, 8}), 400, r));
    }

    @Test
    void theHighRoadConnectsTheSettlementToTheCapital() {
        final Realm r = new Realm();
        final List<int[]> path = RoadPlanner.path(r, 88, 8, Set.of(), 8, 8, List.of(), 40_000);
        assertFalse(path.isEmpty());
        assertEquals(8, path.get(path.size() - 1)[0], "ends at the capital");
        for (int i = 1; i < path.size(); i++) {
            assertEquals(1, Math.abs(path.get(i)[0] - path.get(i - 1)[0]) + Math.abs(path.get(i)[1] - path.get(i - 1)[1]), "continuous");
            assertTrue(r.own(path.get(i)[0], path.get(i)[1]), "on own land");
        }
    }

    @Test
    void kingdomMap() {
        final Realm r = new Realm();
        final String[] rows = KingdomPlanner.map(-8, -2, 8, 2, (cx, cz) -> cx == 0 && cz == 0 ? 'S' : cx == 5 && cz == 0 ? 'o'
            : r.liquid(cx * 16 + 8, cz * 16 + 8) ? '~' : '.');
        assertEquals(5, rows.length);
        assertEquals(17, rows[0].length());
        assertEquals('S', rows[2].charAt(8));
        assertEquals('o', rows[2].charAt(13));
        assertEquals('~', rows[2].charAt(15));
        // a big kingdom is scaled down to at most 48 × 24 and keeps the capital visible
        final String[] big = KingdomPlanner.map(-100, -100, 100, 100, (cx, cz) -> cx == 3 && cz == 3 ? 'S' : '.');
        assertTrue(big.length <= 24 && big[0].length() <= 48);
        assertTrue(String.join("", big).contains("S"));
    }
}
