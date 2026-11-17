// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Point;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Zone;
import pl.medivalrpg.armia.logic.VillageRules.Decision;

/** Villages → NPC countries: automat rules, scan grid, names, claim order. */
class VillageRulesTest {

    private static final VillageRules.Rules R = VillageRules.Rules.defaults();
    private static final List<Zone> SPAWN = List.of(new Zone(-50, -50, 50, 50).grow(600));

    @Test
    void chanceDecidesOnlyWhenEverythingElseAllows() {
        final Point v = new Point(2000, 0);
        assertEquals(Decision.CREATE, VillageRules.decide(R, v, false, SPAWN, List.of(), 3, 0, 0.1));
        assertEquals(Decision.CHANCE, VillageRules.decide(R, v, false, SPAWN, List.of(), 3, 0, 0.5));
        assertEquals(Decision.SEEN, VillageRules.decide(R, v, true, SPAWN, List.of(), 3, 0, 0.0), "each village rolled once");
        final VillageRules.Rules off = new VillageRules.Rules(false, 1, 0, 0, 99, 99, 0, 800);
        assertEquals(Decision.DISABLED, VillageRules.decide(off, v, false, SPAWN, List.of(), 0, 0, 0.0));
    }

    @Test
    void distancesFromSpawnAndCountries() {
        assertEquals(Decision.SPAWN, VillageRules.decide(R, new Point(500, 300), false, SPAWN, List.of(), 0, 0, 0.0));
        assertEquals(Decision.COUNTRY, VillageRules.decide(R, new Point(2000, 0), false, SPAWN, List.of(new Point(2300, 100)), 0, 0, 0.0));
        assertEquals(Decision.CREATE, VillageRules.decide(R, new Point(2000, 0), false, SPAWN, List.of(new Point(2500, 0)), 0, 0, 0.0));
    }

    @Test
    void limits() {
        assertEquals(Decision.LIMIT, VillageRules.decide(R, new Point(2000, 0), false, SPAWN, List.of(), 18, 0, 0.0), "all NPC countries");
        assertEquals(Decision.LIMIT, VillageRules.decide(R, new Point(2000, 0), false, SPAWN, List.of(), 13, 6, 0.0), "village countries");
        assertEquals(Decision.CREATE, VillageRules.decide(R, new Point(2000, 0), false, SPAWN, List.of(), 12, 5, 0.0), "after a 12-country scenario");
    }

    @Test
    void scanGridIsDeterministicAndCoversTheRadius() {
        final List<Point> a = VillageRules.scanGrid(new Point(0, 0), 4000, 800);
        final List<Point> b = VillageRules.scanGrid(new Point(0, 0), 4000, 800);
        assertEquals(a, b, "same order after a restart (the saved index stays valid)");
        assertEquals(new Point(0, 0), a.get(0));
        assertTrue(a.stream().allMatch(p -> p.distance(new Point(0, 0)) <= 4000));
        assertTrue(a.size() > 60 && a.size() < 100, "about π·5² points: " + a.size());
        assertEquals(VillageRules.key(17, 33), VillageRules.key(31, 47), "same chunk = same village key");
        assertNotEquals(VillageRules.key(0, 0), VillageRules.key(16, 0));
    }

    @Test
    void namesAreUniqueThenNumbered() {
        final List<String> pool = List.of("Księstwo_Wiślan", "Ziemia_Sądecka");
        final Random rnd = new Random(1);
        final String n = VillageRules.pickName(pool, Set.of("księstwo_wiślan"), rnd);
        assertEquals("Ziemia_Sądecka", n);
        final String all = VillageRules.pickName(pool, Set.of("Księstwo_Wiślan", "Ziemia_Sądecka"), rnd);
        assertTrue(all.endsWith("_II"), all);
        assertEquals("księcia", VillageRules.rulerTitle("Księstwo_Wiślan"));
        assertEquals("hrabiego", VillageRules.rulerTitle("Hrabstwo Pomorskie"));
        assertEquals("kasztelana", VillageRules.rulerTitle("Ziemia_Sądecka"));
        assertEquals("na wschodzie", VillageRules.direction(new Point(3000, 100), new Point(0, 0)));
        assertEquals("na północy", VillageRules.direction(new Point(0, -2000), new Point(0, 0)));
        assertEquals("na południowym zachodzie", VillageRules.direction(new Point(-1500, 1500), new Point(0, 0)));
    }

    @Test
    void configNamesAreValidKrajeNames() throws Exception {
        try (var in = VillageRulesTest.class.getResourceAsStream("/config.yml")) {
            final YamlConfiguration c = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            final List<String> names = c.getStringList("villages.names");
            assertTrue(names.size() >= 12);
            assertEquals(names.size(), new HashSet<>(names).size(), "unique");
            for (final String n : names) {
                assertTrue(Scenario.NAME.matcher(n).matches() && n.length() >= 3 && n.length() <= 24, n);
            }
            assertFalse(c.getStringList("villages.rulers").isEmpty());
            assertEquals(6, c.getInt("villages.auto.max-auto-countries"));
        }
    }

    @Test
    void randomPersonalityInBoundsAndColoursApart() {
        final Random rnd = new Random(7);
        for (int i = 0; i < 50; i++) {
            final int[] t = VillageRules.randomPersonality(rnd);
            for (int j = 0; j < 4; j++) {
                assertTrue(t[j] >= 20 && t[j] <= 80);
            }
            assertTrue(t[4] >= 30 && t[4] <= 90, "honor");
        }
        final int c = VillageRules.randomColor(List.of(0xFF0000, 0x00FF00, 0x0000FF), rnd);
        assertTrue(c >= 0 && c <= 0xFFFFFF);
    }

    @Test
    void claimOrderKeepsTheCountryConnected() {
        final long cap = ChunkPlanner.key(0, 0);
        final Set<Long> village = Set.of(ChunkPlanner.key(1, 0), ChunkPlanner.key(2, 0), ChunkPlanner.key(2, 1), ChunkPlanner.key(5, 5));
        final List<Long> order = VillageRules.claimOrder(cap, village);
        assertEquals(List.of(ChunkPlanner.key(1, 0), ChunkPlanner.key(2, 0), ChunkPlanner.key(2, 1)), order, "BFS from the capital, island left out");
        assertFalse(order.contains(cap));
    }
}
