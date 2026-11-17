// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reflection bridge to Ludnosc: current, older and failing plugins (stand-ins with the same public methods). */
class LudnoscBridgeTest {

    static final UUID C = UUID.randomUUID();

    public enum Job { FARMER, MINER }

    public record Stats(int population, int adults, int children, int housed, int homeless, int employed, int unemployed,
                        int couples, long savings, long average, int unpaid, long time) {
    }

    public record Summary(Map<Job, Integer> workers, Map<Job, Integer> slots, Map<Job, Integer> places, int adults, int assigned,
                          long foodToday, long foodCap, long fishToday, long fishCap) {
    }

    public static final class CountryLife {
        public long lastWagesPaid = 400;
        public long lastTaxPaid = 900;
        public int lastWorkers = 11;
        public Boolean huts;
        public List<String> notCopied = List.of("x");
        public static int alsoNotCopied = 5;
    }

    public static class Life {
        int countryLifeCalls;

        public boolean enabled() {
            return true;
        }

        public Stats stats(final UUID country) {
            return new Stats(20, 16, 4, 18, 2, 11, 5, 6, 12345, 617, 1, 99);
        }

        public CountryLife countryLife(final UUID country) {
            this.countryLifeCalls++;
            return new CountryLife();
        }
    }

    public static final class Work {
        public Summary summary(final UUID country) {
            final Map<Job, Integer> w = new EnumMap<>(Job.class);
            w.put(Job.FARMER, 4);
            w.put(Job.MINER, 5);
            return new Summary(w, w, w, 16, 9, 30, 120, 0, 40);
        }
    }

    /** flow-release5-like Ludnosc. */
    public static class Current {
        final Life life = new Life();

        public Map<String, Object> materialFlow(final UUID country) {
            return Map.of("workplaces", List.of(Map.of("id", "a", "function", "pole", "status", "Magazyn gotowy", "x", 1, "z", 2)),
                "requireMaterials", true);
        }

        public Life life() {
            return this.life;
        }

        public Work work() {
            return new Work();
        }
    }

    /** Before flow-release1: no materialFlow, no life(). */
    public static final class Old {
        public Work work() {
            return new Work();
        }
    }

    /** materialFlow throws (e.g. called off the main thread). */
    public static final class Broken extends Current {
        @Override
        public Map<String, Object> materialFlow(final UUID country) {
            throw new IllegalStateException("flowData requires the main thread");
        }
    }

    /** No citizens: countryLife must not be asked (it would create an empty entry). */
    public static final class Empty extends Current {
        final Life empty = new Life() {
            @Override
            public Stats stats(final UUID country) {
                return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
        };

        @Override
        public Life life() {
            return this.empty;
        }
    }

    @Test
    void readsEverySourceOfACurrentLudnosc() {
        final LudnoscBridge.Sources s = new LudnoscBridge().read(new Current(), C);
        assertNotNull(s.flow());
        assertEquals("pole", ((Map<?, ?>) ((List<?>) s.flow().get("workplaces")).get(0)).get("function"));
        assertEquals(1L, ((Map<?, ?>) ((List<?>) s.flow().get("workplaces")).get(0)).get("x"), "liczby jako long");
        assertEquals(20L, s.lifeStats().get("population"));
        assertEquals(99L, s.lifeStats().get("time"));
        assertEquals(400L, s.lifeDay().get("lastWagesPaid"));
        assertEquals(11L, s.lifeDay().get("lastWorkers"));
        assertTrue(s.lifeDay().containsKey("huts"));
        assertNull(s.lifeDay().get("notCopied"), "tylko proste pola");
        assertNull(s.lifeDay().get("alsoNotCopied"), "bez pól statycznych");
        assertEquals(Map.of("FARMER", 4L, "MINER", 5L), s.work().get("workers"), "klucze enum jako tekst");
        assertEquals(30L, s.work().get("foodToday"));
        assertTrue(s.errors().isEmpty(), s.errors().toString());
    }

    @Test
    void olderLudnoscGivesNullsAndReasons() {
        final LudnoscBridge.Sources s = new LudnoscBridge().read(new Old(), C);
        assertNull(s.flow());
        assertNull(s.lifeStats());
        assertNotNull(s.work());
        assertTrue(s.errors().get("flow").contains("materialFlow"));
        assertTrue(s.errors().get("life").contains("life"));
    }

    @Test
    void failingMaterialFlowIsReportedNotThrown() {
        final LudnoscBridge.Sources s = new LudnoscBridge().read(new Broken(), C);
        assertNull(s.flow());
        assertTrue(s.errors().get("flow").contains("IllegalStateException"), s.errors().toString());
        assertNotNull(s.lifeStats(), "reszta źródeł działa");
    }

    @Test
    void noLudnoscAtAll() {
        final LudnoscBridge.Sources s = new LudnoscBridge().read(null, C);
        assertNull(s.flow());
        assertTrue(s.errors().containsKey("ludnosc"));
    }

    @Test
    void countryLifeIsNotTouchedForACountryWithoutCitizens() {
        final Empty e = new Empty();
        final LudnoscBridge.Sources s = new LudnoscBridge().read(e, C);
        assertEquals(0L, s.lifeStats().get("population"));
        assertNull(s.lifeDay());
        assertEquals(0, e.empty.countryLifeCalls);
    }

    @Test
    void plainIsBounded() {
        final java.util.List<Integer> big = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            big.add(i);
        }
        assertEquals(Plain.MAX_LIST, ((List<?>) Plain.of(big)).size());
        Object deep = "x";
        for (int i = 0; i < 20; i++) {
            deep = Map.of("d", deep);
        }
        assertNotNull(Plain.of(deep));
        assertEquals(Double.valueOf(1.5), Plain.of(1.5f));
        assertNull(Plain.of(Double.NaN));
        assertEquals("FARMER", Plain.of(Job.FARMER));
        assertEquals(12L, Plain.num("12"));
        assertNull(Plain.num("dwanaście"));
    }
}
