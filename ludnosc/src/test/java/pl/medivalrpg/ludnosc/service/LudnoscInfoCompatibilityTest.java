// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LudnoscInfoCompatibilityTest {
    @Test void originalConstructorStillProducesAUsableSummary() {
        final LudnoscInfo info = new LudnoscInfo(12, 20, 100, 200, 5, 8, 0, 0, List.of(), List.of());
        assertEquals(12, info.population());
        assertEquals(20, info.housing());
        assertEquals(0, info.settlementLevel());
        assertEquals("", info.settlementName());
    }

    @Test void newConstructorPublishesSettlementLevelAndName() {
        final LudnoscInfo info = new LudnoscInfo(12, 20, 100, 200, 5, 8, 0, 0, List.of(), List.of(), 3, "Miasteczko");
        assertEquals(3, info.settlementLevel());
        assertEquals("Miasteczko", info.settlementName());
    }
}
