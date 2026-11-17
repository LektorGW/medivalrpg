// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HouseholdForecastTest {

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    static final long NOW = TODAY.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();

    private static Map<String, Object> day(final LocalDate d, final long in, final long out) {
        return FlowQuery.map("day", d.toString(), "t", d.atStartOfDay(ZONE).toInstant().toEpochMilli(), "in", in, "out", out, "byCategory", Map.of());
    }

    @Test
    void forecastFromTheLastSevenCompleteDays() {
        final List<Map<String, Object>> daily = new ArrayList<>();
        for (int k = 10; k >= 1; k--) {
            daily.add(day(TODAY.minusDays(k), 6_500, 9_000)); // households pay 9 022/day, get ~6 500 back
        }
        daily.add(day(TODAY, 0, 100_000)); // today is partial and ignored
        final Map<String, Object> f = HouseholdForecast.of(daily, 312_600, ZONE, NOW);
        assertEquals(-2_500.0, ((Map<?, ?>) f.get("avg7")).get("net"));
        assertEquals(125L, f.get("forecastDays"));
        assertEquals(TODAY.plusDays(125).toString(), f.get("forecastDate"));
        assertEquals(false, f.get("selfSustaining"));
    }

    @Test
    void quietDaysCountAsZeroAndASurplusIsSelfSustaining() {
        final List<Map<String, Object>> daily = List.of(day(TODAY.minusDays(3), 7_000, 0));
        final Map<String, Object> f = HouseholdForecast.of(daily, 1_000, ZONE, NOW);
        assertEquals(3, ((Map<?, ?>) f.get("avg7")).get("days"));
        assertNull(f.get("forecastDays"));
        assertTrue((Boolean) f.get("selfSustaining"));
    }

    @Test
    void noHistoryNoForecast() {
        final Map<String, Object> f = HouseholdForecast.of(List.of(), 0, ZONE, NOW);
        assertNull(((Map<?, ?>) f.get("avg7")).get("net"));
        assertNull(f.get("forecastDays"));
    }

    @Test
    void citizenFlowsAreCategorised() {
        assertEquals("citizens", Categories.category("Ludnosc: dochód obywateli NPC z pracy (640 osób)", "world:households", "ludnosc:obywatele_x"));
        assertEquals("citizens", Categories.category("Ludnosc: obywatele NPC kupują żywność (posiłki) — zwrot do budżetu gospodarstw", "ludnosc:obywatele_x", "world:households"));
        assertEquals("citizens", Categories.category("Ludnosc: obywatele NPC robią zakupy na targu z oszczędności (12 osób)", "ludnosc:obywatele_x", "world:households"));
        assertEquals("tax", Categories.category("Ludnosc: podatek obywateli NPC z ich dochodów (600 podatników)", "ludnosc:obywatele_x", "country:y"));
        assertEquals("labor", Categories.category("robocizna: 60% budów — wynagrodzenie obywateli NPC", "world:payments", "world:households"));
        assertEquals("Oszczędności obywateli NPC", AccountTypes.label(AccountTypes.type("ludnosc:obywatele_17b1bc20-8d55-4b16-8fab-30418b89d3ea")));
    }
}
