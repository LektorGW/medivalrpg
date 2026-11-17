// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mood part of country.get (Zycie) and the festival console command. */
class NpcExtraMethodsTest {

    @Test
    @SuppressWarnings("unchecked")
    void moodJsonSortsFactorsAndLabelsTheValue() {
        final Map<String, Integer> f = new LinkedHashMap<>();
        f.put("podatki", -8);
        f.put("jedzenie", 12);
        f.put("pusty", 0);
        f.put("bezpieczenstwo_granic", 3);
        final Map<String, Object> m = NpcExtraMethods.moodJson(71, f, java.util.Arrays.asList("Za wysokie podatki", " ", null), 1.1234, true, false);
        assertEquals(71, m.get("value"));
        assertEquals("Zadowolenie", m.get("label"));
        final List<Map<String, Object>> factors = (List<Map<String, Object>>) m.get("factors");
        assertEquals(List.of("jedzenie", "podatki", "bezpieczenstwo_granic"), factors.stream().map(x -> x.get("key")).toList(), "largest effect first, zeros dropped");
        assertEquals("Bezpieczenstwo granic", factors.get(2).get("label"));
        assertEquals(List.of("Za wysokie podatki"), m.get("concerns"));
        assertEquals(1.12, m.get("multiplier"));
        assertEquals(true, m.get("festival"));
        assertEquals(100, NpcExtraMethods.moodJson(140, null, null, 1, false, false).get("value"), "clamped");
    }

    @Test
    void moodLabels() {
        assertEquals("Bunt", NpcExtraMethods.moodLabel(5));
        assertEquals("Niezadowolenie", NpcExtraMethods.moodLabel(20));
        assertEquals("Niepokój", NpcExtraMethods.moodLabel(49));
        assertEquals("Spokój", NpcExtraMethods.moodLabel(50));
        assertEquals("Zachwyt", NpcExtraMethods.moodLabel(80));
    }

    @Test
    void festivalCommandQuotesNamesWithSpaces() {
        assertEquals("zycie admin swieto Mazowsze", NpcExtraMethods.festivalCommand("Mazowsze"));
        assertEquals("zycie admin swieto \"Ruś Czerwona\"", NpcExtraMethods.festivalCommand("Ruś \"Czerwona"));
    }
}
