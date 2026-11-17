// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rolling 24 h output of a workplace for the panel: hourly buckets, pruning, survives a save / load. */
class OutputLedgerTest {

    private static final long H = 3_600_000L;

    @Test
    void sumsTheLastDayAndDropsOlderHours() {
        final Map<String, Map<String, Integer>> b = new LinkedHashMap<>();
        final long t0 = 1_000 * H;
        OutputLedger.record(b, t0, "minecraft:glass", 8);
        OutputLedger.record(b, t0 + 10 * H, "minecraft:glass", 8);
        OutputLedger.record(b, t0 + 10 * H, "minecraft:sand", 3);
        assertEquals(Map.of("minecraft:glass", 16, "minecraft:sand", 3), OutputLedger.lastDay(b, t0 + 23 * H));
        assertEquals(Map.of("minecraft:glass", 8, "minecraft:sand", 3), OutputLedger.lastDay(b, t0 + 24 * H)); // first hour out
        OutputLedger.record(b, t0 + 40 * H, "minecraft:glass", 1);
        assertEquals(1, b.size()); // old buckets pruned on write
        OutputLedger.record(b, t0, "minecraft:glass", 0);
        assertTrue(OutputLedger.lastDay(null, t0).isEmpty());
    }

    @Test
    void ledgerSurvivesJsonRoundTrip() {
        final Map<String, Map<String, Integer>> b = new LinkedHashMap<>();
        OutputLedger.record(b, 5 * H, "minecraft:white_wool", 2);
        final Gson gson = new Gson();
        final Map<String, Map<String, Integer>> back = gson.fromJson(gson.toJson(b),
            new TypeToken<Map<String, Map<String, Integer>>>() { }.getType());
        assertEquals(Map.of("minecraft:white_wool", 2), OutputLedger.lastDay(back, 6 * H));
    }
}
