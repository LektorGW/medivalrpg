// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Shared cases (natural-drift-cases.json) also asserted by the plan generator's test_natural_drift.py. */
class NaturalDriftTest {
    @Test void sharedCasesAgree() throws Exception {
        assertTrue(NaturalDrift.loaded(), "resource natural-drift.json must load");
        try (var in = NaturalDriftTest.class.getResourceAsStream("/natural-drift-cases.json")) {
            var cases = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("cases");
            assertTrue(cases.size() >= 20);
            for (var c : cases) {
                var a = c.getAsJsonArray();
                assertEquals(a.get(2).getAsBoolean(), NaturalDrift.same(a.get(0).getAsString(), a.get(1).getAsString()), a.toString());
            }
        }
    }
}
