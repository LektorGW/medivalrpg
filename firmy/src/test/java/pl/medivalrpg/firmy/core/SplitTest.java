// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SplitTest {

    @Test
    void partsAlwaysSumToTheAmount() {
        final Random r = new Random(7);
        for (int t = 0; t < 2_000; t++) {
            final Map<Integer, Long> w = new LinkedHashMap<>();
            final int n = 1 + r.nextInt(8);
            for (int i = 0; i < n; i++) {
                w.put(i, (long) r.nextInt(10_001));
            }
            if (w.values().stream().mapToLong(Long::longValue).sum() == 0) {
                continue;
            }
            final long amount = r.nextInt(100_000_000);
            final Map<Integer, Long> parts = Split.proportional(amount, w);
            assertEquals(amount, parts.values().stream().mapToLong(Long::longValue).sum());
            for (final var e : parts.entrySet()) {
                if (w.get(e.getKey()) == 0) {
                    assertEquals(0L, e.getValue());
                }
            }
        }
    }

    @Test
    void thirdsAreDeterministic() {
        final Map<String, Long> w = new LinkedHashMap<>();
        w.put("a", 1L);
        w.put("b", 1L);
        w.put("c", 1L);
        assertEquals(Map.of("a", 34L, "b", 33L, "c", 33L), Split.proportional(100, w));
    }
}
