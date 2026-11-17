// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/** Small countries, steep land and readable reasons when there is no place for a building. */
class RoomForBuildingsTest {

    /** Terrain with a height function; own land = a set of chunks; optional water and unknown chunks. */
    static final class Land implements TerrainView {
        final Set<Long> own = new HashSet<>();
        final IntBinaryOperator h;
        final Set<Long> waterChunks = new HashSet<>();
        final Set<Long> unknownChunks = new HashSet<>();

        Land(final IntBinaryOperator h) {
            this.h = h;
        }

        Land chunk(final int cx, final int cz) {
            this.own.add(LandPicker.key(cx, cz));
            return this;
        }

        private long chunkOf(final int x, final int z) {
            return LandPicker.key(x >> 4, z >> 4);
        }

        @Override
        public int height(final int x, final int z) {
            return this.unknownChunks.contains(this.chunkOf(x, z)) ? UNKNOWN : this.h.applyAsInt(x, z);
        }

        @Override
        public boolean liquid(final int x, final int z) {
            return this.waterChunks.contains(this.chunkOf(x, z));
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return this.own.contains(this.chunkOf(x, z));
        }
    }

    private static final SiteFinder.Settings S = new SiteFinder.Settings(48, 2, 2, 3);

    @Test
    void aOneChunkCountryClaimsLandUntilTheHouseFits() {
        final Land land = new Land((x, z) -> 64).chunk(0, 0);
        final int[] size = {20, 12};
        SiteFinder.Search s = SiteFinder.search(land, size, 8, 8, "inner", List.of(), Set.of(), S, 0, false);
        assertNull(s.spot(), "20×12 does not fit into one chunk");
        assertTrue(s.stats().reason(20, 12).startsWith("za mało ziemi kraju"), s.stats().reason(20, 12));
        int claims = 0;
        while (s.spot() == null && claims < 4) {
            final long c = LandPicker.pick(land.own, 0, 0, 4, (cx, cz) -> true, land);
            assertNotEquals(Long.MIN_VALUE, c);
            land.own.add(c);
            claims++;
            s = SiteFinder.search(land, size, 8, 8, "inner", List.of(), Set.of(), S, 0, false);
        }
        assertNotNull(s.spot(), "after claiming " + claims + " chunks the house fits");
        assertTrue(claims <= 3, "claims: " + claims);
    }

    @Test
    void claimsGoTowardsGoodTerrainAndCompactLand() {
        final Land land = new Land((x, z) -> x < 0 ? 64 + Math.abs(x) : 64).chunk(0, 0).chunk(0, 1);
        land.waterChunks.add(LandPicker.key(0, -1));
        final long c = LandPicker.pick(land.own, 0, 0, 4, (cx, cz) -> true, land);
        // west is steep, north is a lake: east it is
        assertEquals(LandPicker.key(1, 0) >> 32, c >> 32, "east");
        // no free chunk at all
        assertEquals(Long.MIN_VALUE, LandPicker.pick(land.own, 0, 0, 4, (cx, cz) -> false, land));
    }

    @Test
    void relaxedRulesFindAPlaceOnASlope() {
        // the whole country is a slope of 1:2: too steep for the strict limit of 3 over a 9-wide plot
        final Land land = new Land((x, z) -> 64 + x / 2);
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                land.chunk(cx, cz);
            }
        }
        assertNull(SiteFinder.evaluate(land, 9, 7, 0, 0, 0, 0, "inner", List.of(), Set.of(), 2, 3), "strict: too steep");
        final SiteFinder.Search s = SiteFinder.search(land, new int[] {9, 7}, 0, 0, "inner", List.of(), Set.of(), S, 0, false);
        assertNotNull(s.spot());
        assertTrue(s.level() > 0, "found with relaxed rules (level " + s.level() + ")");
        // high-rise buildings never use the relaxed rules
        assertNull(SiteFinder.search(land, new int[] {13, 13}, 0, 0, "center", List.of(), Set.of(), S, 0, true).spot());
    }

    @Test
    void reasonsArePolishAndSpecific() {
        final Land steep = new Land((x, z) -> 64 + x * 3);
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                steep.chunk(cx, cz);
            }
        }
        final SiteFinder.Search s = SiteFinder.search(steep, new int[] {9, 7}, 0, 0, "inner", List.of(), Set.of(), S, 0, false);
        assertNull(s.spot());
        assertTrue(s.stats().reason(9, 7).contains("stromy"), s.stats().reason(9, 7));

        final Land unknown = new Land((x, z) -> 64).chunk(0, 0).chunk(1, 0);
        unknown.unknownChunks.add(LandPicker.key(0, 0));
        unknown.unknownChunks.add(LandPicker.key(1, 0));
        final SiteFinder.Search u = SiteFinder.search(unknown, new int[] {9, 7}, 16, 8, "inner", List.of(), Set.of(), S, 0, false);
        assertTrue(u.stats().reason(9, 7).contains("nie jest jeszcze zbadany"), u.stats().reason(9, 7));

        final Land none = new Land((x, z) -> 64);
        assertEquals("brak ziemi kraju w zasięgu planisty",
            SiteFinder.search(none, new int[] {9, 7}, 0, 0, "inner", List.of(), Set.of(), S, 0, false).stats().reason(9, 7));

        final Land busy = new Land((x, z) -> 64).chunk(0, 0);
        final var taken = List.of(new pl.medivalrpg.ludnosc.model.BlockBox("w", 0, 60, 0, 15, 70, 15));
        final SiteFinder.Search b = SiteFinder.search(busy, new int[] {9, 7}, 8, 8, "inner", taken, Set.of(), S, 0, false);
        assertNull(b.spot());
        assertTrue(b.stats().reason(9, 7).contains("kolizja"), b.stats().reason(9, 7));
    }
}
