// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.util.Json;

/** Shape of admin.health (PANEL.md „Wydajność serwera”). */
class HealthJsonTest {

    static final long T0 = HealthHistoryTest.T0;

    @Test
    void oneHourUsesProbesLongerRangesUseMinutesAndDownsample() {
        final HealthHistory h = new HealthHistory(3_600_000L, 24 * 3_600_000L);
        final long now = T0 + 24 * 3_600_000L;
        for (long t = T0 + 60_000L; t <= now; t += 10_000L) {
            h.add(HealthHistoryTest.p(t, 19.987, 12.3456, 3));
        }
        final Map<String, Object> hour = HealthJson.series(h, "1h", now, 10_000L, 720);
        assertEquals("1h", hour.get("range"));
        assertEquals(10_000L, hour.get("resolutionMs"));
        assertEquals(361, ((List<?>) hour.get("points")).size());
        final Map<String, Object> six = HealthJson.series(h, "6h", now, 10_000L, 720);
        assertEquals(60_000L, six.get("resolutionMs"));
        assertEquals(361, ((List<?>) six.get("points")).size());
        final Map<String, Object> day = HealthJson.series(h, "24h", now, 10_000L, 720);
        assertEquals(120_000L, day.get("resolutionMs"), "1440 minutes → 2-minute points");
        assertTrue(((List<?>) day.get("points")).size() <= 720);
        assertEquals("1h", HealthJson.series(h, "bogus", now, 10_000L, 720).get("range"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> pt = (Map<String, Object>) ((List<?>) hour.get("points")).get(0);
        assertEquals(List.of("t", "tps", "tpsMin", "mspt", "msptMax", "players", "chunks", "entities", "npcTotal", "npc", "helpers", "warn"),
            List.copyOf(pt.keySet()));
        assertEquals(19.99, pt.get("tps"));
        assertEquals(12.35, pt.get("mspt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentSortsCountriesAndResolvesNames() {
        final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        final HealthJson.Current c = new HealthJson.Current(T0, 19.5, 19.8, 19.9, 21.234, 4, 20, 900, 3100, 42,
            Map.of("ludnosc", 30, "armia", 12), Map.of("citizens", 30), 9,
            List.of(new HealthJson.WorldStat("world", "NORMAL", 900, 3100, 4, 42)),
            List.of(new HealthJson.CountryNpc(b, 1, 0, 0), new HealthJson.CountryNpc(a, 20, 10, 1)), 1.234, T0, true);
        final Map<String, Object> m = HealthJson.current(c, id -> id.equals(a) ? Json.obj("name", "Polanie", "color", "#B02E26", "npc", false) : null, 50);
        final List<Map<String, Object>> countries = (List<Map<String, Object>>) m.get("countries");
        assertEquals(a.toString(), countries.get(0).get("id"));
        assertEquals("Polanie", countries.get(0).get("name"));
        assertEquals(31, countries.get(0).get("total"));
        assertEquals(false, countries.get(1).get("exists"), "deleted country");
        assertNull(countries.get(1).get("name"));
        assertEquals(2, m.get("countriesTotal"));
        assertEquals(21.23, m.get("mspt"));
        assertEquals(1, ((List<?>) HealthJson.current(c, id -> null, 1).get("countries")).size(), "limit");
        assertNull(HealthJson.current(null, null, 5));
    }

    @Test
    void pluginsWarningsAndEpisodes() {
        assertEquals(NpcCensus.PLUGINS, HealthJson.plugins().stream().map(x -> x.get("id")).toList());
        assertEquals("Władcy", HealthJson.plugins().get(3).get("name"));
        final HealthWarnings w = new HealthWarnings();
        final HealthWarnings.Thresholds th = new HealthWarnings.Thresholds(18, 0, 0, 0, 0, 0, 0, 0, Map.of(), 1);
        w.update(HealthHistoryTest.p(T0, 12.3456, 5, 0), th);
        final Map<String, Object> warn = HealthJson.warnings(w.active()).get(0);
        assertEquals("TPS", warn.get("code"));
        assertEquals("WARN", warn.get("level"));
        assertEquals(12.35, warn.get("value"));
        assertEquals(18.0, warn.get("threshold"));
        assertEquals(T0, warn.get("since"));
        final Map<String, Object> ep = HealthJson.episodes(w.episodes(), 10).get(0);
        assertNull(ep.get("end"), "still active");
        assertEquals(0, HealthJson.episodes(w.episodes(), 0).size());
        assertEquals(18.0, HealthJson.thresholds(th).get("tps"));
    }
}
