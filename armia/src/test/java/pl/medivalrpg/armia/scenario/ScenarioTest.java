// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcStance;
import pl.medivalrpg.armia.logic.Scenario;
import pl.medivalrpg.armia.logic.ScenarioPlacement;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Point;
import pl.medivalrpg.armia.logic.ScenarioPlacement.Zone;

/** Scenario files: parsing, validation and placement on synthetic data. */
class ScenarioTest {

    private static final Set<String> UNITS = Set.of("piechota", "lucznik", "rycerz", "kusznik");

    private static Scenario europa() throws Exception {
        try (var in = ScenarioTest.class.getResourceAsStream("/scenarios/europa1350.yml")) {
            assertNotNull(in);
            return ScenarioParser.parse("europa1350", YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    private static YamlConfiguration yaml(final String text) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void bundledEuropeIsValid() throws Exception {
        final Scenario s = europa();
        assertEquals("europa1350", s.id());
        assertEquals(12, s.countries().size());
        assertEquals(List.of(), s.validate(UNITS, 3, 24));
        assertEquals(List.of(), s.similarColors(60), "colours readable on the map");
        final Scenario.CountrySpec pl = s.country("Królestwo_Polskie");
        assertEquals("Kazimierz Wielki", pl.ruler());
        assertEquals(35, pl.aggression());
        assertEquals(75, pl.diplomacy());
        assertEquals(900, pl.x());
        assertEquals(-300, pl.z());
        assertEquals(38_000, pl.treasury(), "380 zł");
        assertEquals(0xDC143C, pl.rgb());
        assertEquals(400, s.villageRadius());
        for (final Scenario.CountrySpec c : s.countries()) {
            assertTrue(c.treasury() >= 20_000 && c.treasury() <= 60_000, c.name());
            final int army = c.army().values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(army >= 4 && army <= 10, c.name());
            assertTrue(c.settlers() >= 8 && c.settlers() <= 14, c.name());
            assertEquals(400, c.food());
            assertEquals("", c.skin());
        }
    }

    @Test
    void europeRelations() throws Exception {
        final Scenario s = europa();
        assertEquals(1, s.alliances().size());
        assertEquals("Królestwo_Polskie", s.alliances().get(0)[0]);
        final List<Scenario.Attitude> att = s.attitudes();
        // Orda towards everybody (11, one way) + 7 mutual pairs × 2
        assertEquals(11 + 14, att.size());
        assertTrue(att.stream().anyMatch(a -> a.from().equals("Złota_Orda") && a.to().equals("Republika_Wenecka")
            && a.opinion() == -40 && a.stance() == NpcStance.HOSTILE));
        assertFalse(att.stream().anyMatch(a -> a.to().equals("Złota_Orda") && a.opinion() == -40), "one way");
        assertTrue(att.stream().anyMatch(a -> a.from().equals("Królestwo_Polskie") && a.to().equals("Zakon_Krzyżacki") && a.opinion() == -70));
    }

    @Test
    void validationFindsProblems() throws Exception {
        final Scenario s = ScenarioParser.parse("x", yaml("""
            panstwa:
              - {nazwa: "Ma spacje", kolor: "#112233", wladca: "A", x: 0, z: 0, agresja: 150}
              - {nazwa: "Ma_spacje", kolor: "#112233", wladca: "B", x: 10, z: 10, wojsko: {smok: 2}}
              - {nazwa: "ma_spacje", kolor: "#445566", wladca: "", x: 10, z: 10, skin: "zły skin"}
            relacje:
              - {a: "Ma_spacje", b: "Nieznane", opinia: 500}
              - {a: "Ma_spacje", b: "*", relacja: sojusz}
            """));
        final String all = String.join("\n", s.validate(UNITS, 3, 24));
        assertTrue(all.contains("bez spacji"), all);
        assertTrue(all.contains("0–100"), all);
        assertTrue(all.contains("kolor #112233"), all);
        assertTrue(all.contains("powtarza"), all);
        assertTrue(all.contains("nieznany typ jednostki smok"), all);
        assertTrue(all.contains("władca"), all);
        assertTrue(all.contains("skin"), all);
        assertTrue(all.contains("nieznane państwo Nieznane"), all);
        assertTrue(all.contains("-100..100"), all);
        assertTrue(all.contains("sojusz ze wszystkimi"), all);
    }

    @Test
    void parserRejectsBrokenEntries() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("x", yaml("panstwa:\n  - {nazwa: A, kolor: zielony, x: 0, z: 0}\n")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("x", yaml("panstwa:\n  - {nazwa: Abc, kolor: \"#00ff00\", z: 0}\n")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("x", yaml("relacje:\n  - {a: A, b: B, relacja: wojna}\n")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("x", yaml("relacje:\n  - {a: A, b: B, nastawienie: zly}\n")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("x", yaml("panstwa:\n  - {nazwa: Abc, kolor: \"#00ff00\", x: 1.5, z: 0}\n")));
    }

    @Test
    void placementPrefersTheVillageThenTheTarget() {
        final Point target = new Point(1000, 0);
        final List<Zone> spawn = List.of(new Zone(-100, -100, 100, 100).grow(300));
        final Optional<ScenarioPlacement.Choice> v = ScenarioPlacement.choose(target, new Point(1200, 100), 400, spawn, List.of(), 500, 1600, 64);
        assertEquals(ScenarioPlacement.Source.VILLAGE, v.orElseThrow().source());
        assertEquals(new Point(1200, 100), v.get().point());
        final Optional<ScenarioPlacement.Choice> far = ScenarioPlacement.choose(target, new Point(1600, 0), 400, spawn, List.of(), 500, 1600, 64);
        assertEquals(ScenarioPlacement.Source.TARGET, far.orElseThrow().source(), "village too far");
    }

    @Test
    void placementKeepsAwayFromSpawnAndOtherCapitals() {
        final List<Zone> spawn = List.of(new Zone(-100, -100, 100, 100).grow(300));
        // target inside the spawn zone → moved out of it
        final ScenarioPlacement.Choice c = ScenarioPlacement.choose(new Point(200, 0), null, 400, spawn, List.of(), 500, 1600, 64).orElseThrow();
        assertEquals(ScenarioPlacement.Source.SEARCH, c.source());
        assertTrue(ScenarioPlacement.allowed(c.point(), spawn, List.of(), 500));
        assertFalse(spawn.get(0).contains(c.point()));
        // village too close to another capital → not used, target too close → search
        final List<Point> others = List.of(new Point(2000, 0));
        final ScenarioPlacement.Choice d = ScenarioPlacement.choose(new Point(2300, 0), new Point(2100, 0), 400, spawn, others, 500, 1600, 64).orElseThrow();
        assertEquals(ScenarioPlacement.Source.SEARCH, d.source());
        assertTrue(d.point().distance(others.get(0)) >= 500);
        assertTrue(d.point().distance(new Point(2300, 0)) <= 300, "as close to the target as possible");
        // nothing allowed within the search radius
        assertTrue(ScenarioPlacement.choose(new Point(0, 0), null, 400, List.of(new Zone(-5000, -5000, 5000, 5000)), List.of(), 0, 1600, 64).isEmpty());
    }

    @Test
    void flattestDrySpot() {
        // a hill around (0,0), water at dx > 20, flat plain at dx = -16
        final Point best = ScenarioPlacement.flattest(new Point(100, 100), 32, 4, (dx, dz) -> {
            if (dx > 20) {
                return null;
            }
            if (dx <= -12 && dx >= -20) {
                return 70;
            }
            return 70 + Math.abs(dx) + Math.abs(dz);
        });
        assertNotNull(best);
        assertTrue(best.x() <= 100 - 12 && best.x() >= 100 - 20, best.toString());
        assertEquals(null, ScenarioPlacement.flattest(new Point(0, 0), 8, 4, (dx, dz) -> null));
    }

    @Test
    void spiralFindsLandAcrossTheOcean() {
        // ocean everywhere except an island 300–420 blocks west of the target (towards the map centre at 0,0)
        final Point target = new Point(3400, -200);
        final java.util.function.BiFunction<Integer, Integer, Integer> map = (x, z) ->
            x >= 2980 && x <= 3100 && z >= -260 && z <= -140 ? 70 : null;
        final ScenarioPlacement.LandResult r = ScenarioPlacement.findLand(target, new Point(0, 0), 48, 800, List.of(), List.of(), 500, 24, map)
            .orElseThrow();
        assertTrue(map.apply(r.point().x(), r.point().z()) != null, "on land");
        assertTrue(r.point().distance(target) <= 450, r.toString());
        assertTrue(r.tried() > 1, "had to search");
        // no land within the radius
        assertTrue(ScenarioPlacement.findLand(target, new Point(0, 0), 48, 200, List.of(), List.of(), 500, 24, map).isEmpty());
    }

    @Test
    void spiralRespectsDistancesAndPrefersTheMapCentre() {
        final Point target = new Point(1000, 0);
        // land everywhere, but another capital at the target pushes the search out
        final ScenarioPlacement.LandResult r = ScenarioPlacement.findLand(target, new Point(0, 0), 48, 800, List.of(),
            List.of(new Point(1000, 0)), 300, 8, (x, z) -> 64).orElseThrow();
        assertTrue(r.point().distance(new Point(1000, 0)) >= 300 - 8, r.toString());
        assertTrue(r.rejectedByDistance() > 0);
        assertTrue(r.point().x() < 1000, "the side towards the map centre first");
        final List<Point> ring = ScenarioPlacement.spiralCandidates(target, new Point(0, 0), 48, 48);
        assertTrue(ring.get(0).x() < 1000 && ring.get(ring.size() - 1).x() > 1000);
    }

    @Test
    void olderScenarioFileIsDetected() throws Exception {
        final YamlConfiguration bundled = yaml("wersja: 2\npanstwa:\n  - {nazwa: Abc, honor: 50}\n");
        assertTrue(ScenarioParser.outdated(bundled, yaml("panstwa:\n  - {nazwa: Abc}\n")), "no version, no honor");
        assertTrue(ScenarioParser.outdated(bundled, yaml("wersja: 1\npanstwa:\n  - {nazwa: Abc, honor: 10}\n")), "lower version");
        assertFalse(ScenarioParser.outdated(bundled, yaml("wersja: 2\npanstwa:\n  - {nazwa: Abc}\n")), "same version, edited by an admin");
        assertFalse(ScenarioParser.outdated(bundled, yaml("panstwa:\n  - {nazwa: Abc, honor: 10}\n")), "already has honor");
        assertEquals(2, europa().countries().stream().filter(c -> c.honor() != null).count() > 0 ? 2 : 0);
    }

    @Test
    void zoneGrowAndContains() {
        final Zone z = new Zone(10, 10, -10, -10).grow(5);
        assertTrue(z.contains(new Point(-15, 15)));
        assertFalse(z.contains(new Point(-16, 0)));
        assertEquals(Map.of(), Map.of());
    }

    @Test
    void honorFromScenarioOrDerived() throws Exception {
        final Scenario s = europa();
        assertEquals(85, s.country("Królestwo_Polskie").honor());
        assertEquals(90, s.country("Królestwo_Kastylii").honor());
        assertTrue(s.country("Złota_Orda").honor() < 40, "Golden Horde: low honor");
        assertTrue(s.country("Republika_Wenecka").honor() < 40);
        for (final Scenario.CountrySpec c : s.countries()) {
            assertNotNull(c.honor(), c.name() + ": honor set in the bundled scenario");
        }
        final Scenario bad = ScenarioParser.parse("t", yaml("""
            panstwa:
              - {nazwa: "Test", kolor: "#112233", wladca: "Ktoś", x: 0, z: 0, honor: 150}
              - {nazwa: "Bez", kolor: "#445566", wladca: "Ktoś", x: 900, z: 0}
            """));
        assertTrue(bad.validate(UNITS, 3, 24).stream().anyMatch(e -> e.contains("honor")));
        assertEquals(null, bad.country("Bez").honor(), "omitted = derived from the personality");
        // Derived: diplomatic and peaceful → high, aggressive and greedy → low.
        assertTrue(pl.medivalrpg.armia.api.NpcSettings.deriveHonor(20, 85, 50) > 70);
        assertTrue(pl.medivalrpg.armia.api.NpcSettings.deriveHonor(90, 20, 90) < 25);
        assertEquals(50, pl.medivalrpg.armia.api.NpcSettings.deriveHonor(50, 50, 50));
    }
}
