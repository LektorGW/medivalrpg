// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MoodCalculatorTest {

    private final MoodSettings s = MoodSettings.defaults();
    private final MoodCalculator calc = new MoodCalculator(this.s);

    /** A fed, housed medieval town without taxes or war. */
    private static MoodInputs base(final int population, final int housing, final long food, final long balance, final int hungry) {
        return new MoodInputs(true, population, housing, food, balance, hungry, 0, 0, 0, 3.0, 100, List.of(), List.of(), null,
            Set.of(), 1, Map.of());
    }

    @Test
    void neutralCountryIsBasePlusPeace() {
        final MoodCalculator.Result r = this.calc.calculate(MoodInputs.empty());
        assertEquals(this.s.base() + this.s.peaceBonus(), r.target());
        assertEquals(Map.of("pokoj", this.s.peaceBonus()), r.factors());
        // even medieval citizens expect a tavern and a church
        assertEquals(List.of("brak_wygod"), r.concerns().stream().map(Concern::key).toList());
        assertEquals("karczma, kościół", r.concerns().getFirst().args().get("lista"));
    }

    @Test
    void hungerAndHomelessnessLowerMoodAndBecomeConcerns() {
        final MoodCalculator.Result fed = this.calc.calculate(base(20, 30, 500, 10, 0));
        final MoodCalculator.Result hungry = this.calc.calculate(base(20, 10, 0, -20, 3));
        assertEquals(this.s.fedBonus(), fed.factors().get("jedzenie"));
        assertEquals(this.s.roomyBonus(), fed.factors().get("mieszkania"));
        assertEquals(-(this.s.hungerBase() + 3 * this.s.hungerPerDay()), hungry.factors().get("jedzenie"));
        assertTrue(hungry.factors().get("mieszkania") < 0);
        assertTrue(hungry.target() < fed.target());
        final List<String> keys = hungry.concerns().stream().map(Concern::key).toList();
        assertTrue(keys.contains("glod"));
        assertTrue(keys.contains("brak_lozek"));
        assertEquals("10", hungry.concerns().stream().filter(c -> c.key().equals("brak_lozek")).findFirst().orElseThrow().args().get("brak"));
    }

    @Test
    void hungerIsCapped() {
        final MoodCalculator.Result r = this.calc.calculate(base(20, 30, 0, -5, 100));
        assertEquals(-this.s.hungerCap(), r.factors().get("jedzenie"));
    }

    @Test
    void lowGranaryWarns() {
        final MoodCalculator.Result r = this.calc.calculate(base(20, 30, 10, -20, 0));
        assertEquals(-this.s.lowStockPenalty(), r.factors().get("jedzenie"));
        assertEquals("malo_jedzenia", r.concerns().getFirst().key());
    }

    @Test
    void highTaxesAreAConcern() {
        final MoodInputs in = new MoodInputs(true, 20, 30, 500, 10, 0, 0, 100, 5, 12.0, 200, List.of(), List.of(), null,
            Set.of(), 1, Map.of());
        final MoodCalculator.Result r = this.calc.calculate(in);
        assertEquals(-this.s.taxCap(), r.factors().get("podatki"));
        assertTrue(r.concerns().stream().anyMatch(c -> c.key().equals("podatki")));
        // no tax at all is a small plus
        final MoodInputs free = new MoodInputs(true, 20, 30, 500, 10, 0, 0, 0, 5, 2.0, 100, List.of(), List.of(), null,
            Set.of(), 1, Map.of());
        assertTrue(this.calc.calculate(free).factors().get("podatki") > 0);
    }

    @Test
    void warAndSiegesHurtPeaceHelps() {
        final MoodInputs war = new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, List.of("Zakon Krzyżacki", "Litwa"),
            List.of("Zakon Krzyżacki"), null, Set.of(), 1, Map.of());
        final MoodCalculator.Result r = this.calc.calculate(war);
        assertEquals(-2 * this.s.warPenalty(), r.factors().get("wojna"));
        assertEquals(-this.s.siegePenalty(), r.factors().get("oblezenie"));
        assertFalse(r.factors().containsKey("pokoj"));
        assertEquals("oblezenie", r.concerns().getFirst().key(), "a siege is the most important worry");
        assertTrue(r.concerns().stream().anyMatch(c -> c.key().equals("wojna") && c.args().get("wrog").equals("Zakon Krzyżacki")));
    }

    @Test
    void comfortBuildingsAndEraExpectations() {
        final MoodInputs town = new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, List.of(), List.of(), null,
            Set.of("karczma", "kosciol", "laznia", "rynek"), 1, Map.of());
        assertEquals(16, this.calc.calculate(town).factors().get("komfort"));
        final MoodInputs modernEmpty = new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, List.of(), List.of(), null,
            Set.of(), 4, Map.of());
        final MoodCalculator.Result r = this.calc.calculate(modernEmpty);
        assertEquals(-3 * this.s.eraExpectation(), r.factors().get("epoka"));
        final Concern missing = r.concerns().stream().filter(c -> c.key().equals("brak_wygod")).findFirst().orElseThrow();
        assertTrue(missing.args().get("lista").contains("łaźnia"));
        assertTrue(missing.args().get("lista").contains("szpital"));
        // a park only counts from the renaissance on
        final MoodInputs medievalPark = new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, List.of(), List.of(), null,
            Set.of("ogrody"), 1, Map.of());
        assertFalse(this.calc.calculate(medievalPark).factors().containsKey("komfort"));
    }

    @Test
    void modifiersAddUpAndNegativeOnesAreConcerns() {
        final MoodInputs in = new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, List.of(), List.of(), null, Set.of(), 1,
            Map.of("zwyciestwo", 10, "porazka", -10, "admin", 0));
        final MoodCalculator.Result r = this.calc.calculate(in);
        assertEquals(10, r.factors().get("zwyciestwo"));
        assertEquals(-10, r.factors().get("porazka"));
        assertFalse(r.factors().containsKey("admin"));
        assertTrue(r.concerns().stream().anyMatch(c -> c.key().equals("zdarzenie_porazka")));
    }

    @Test
    void targetIsAlwaysWithinRange() {
        final MoodInputs awful = new MoodInputs(true, 100, 0, 0, -50, 30, 30, 100, 10, 50.0, 300, List.of("A", "B", "C", "D"),
            List.of("A", "B", "C"), "A", Set.of(), 4, Map.of("porazka", -50));
        assertEquals(0, this.calc.calculate(awful).target());
        final MoodInputs great = new MoodInputs(true, 10, 100, 1000, 50, 0, 0, 0, 5, 0.5, 50, List.of(), List.of(), null,
            Set.of("karczma", "kosciol", "laznia", "rynek", "zielarnia", "studnia"), 1, Map.of("swieto", 60));
        assertEquals(100, this.calc.calculate(great).target());
    }

    @Test
    void multiplierRange() {
        assertEquals(0.6, MoodCalculator.multiplier(0, false, this.s), 1e-9);
        assertEquals(1.0, MoodCalculator.multiplier(50, false, this.s), 1e-9);
        assertEquals(1.25, MoodCalculator.multiplier(100, false, this.s), 1e-9);
        assertEquals(0.8, MoodCalculator.multiplier(25, false, this.s), 1e-9);
        assertEquals(1.125, MoodCalculator.multiplier(75, false, this.s), 1e-9);
        double last = 0;
        for (int m = -10; m <= 110; m++) {
            final double v = MoodCalculator.multiplier(m, false, this.s);
            assertTrue(v >= 0.6 && v <= 1.25);
            assertTrue(v >= last, "monotonic");
            last = v;
        }
        // protest lowers it but never below the minimum
        assertEquals(0.6, MoodCalculator.multiplier(10, true, this.s), 1e-9);
        assertTrue(MoodCalculator.multiplier(40, true, this.s) < MoodCalculator.multiplier(40, false, this.s));
    }

    @Test
    void stepSmoothsChanges() {
        assertEquals(70, MoodCalculator.step(-1, 70, 6), "first value is taken as is");
        assertEquals(56, MoodCalculator.step(50, 70, 6));
        assertEquals(44, MoodCalculator.step(50, 10, 6));
        assertEquals(52, MoodCalculator.step(50, 52, 6));
        assertEquals(100, MoodCalculator.step(98, 150, 6));
    }
}
