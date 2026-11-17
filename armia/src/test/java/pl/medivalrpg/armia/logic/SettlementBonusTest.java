// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementBonusTest {
    @Test void noProviderAndHamletsAreNeutral() {
        assertEquals(1.0, SettlementBonus.multiplier(0));
        assertEquals(1.0, SettlementBonus.multiplier(1));
        assertEquals(1.0, SettlementBonus.multiplier(-20));
    }
    @Test void trainingScalesModestlyAndHasACap() {
        assertEquals(1.04, SettlementBonus.multiplier(3), 0.00001);
        assertEquals(1.1, SettlementBonus.multiplier(6), 0.00001);
        assertEquals(1.1, SettlementBonus.multiplier(100), 0.00001);
    }
}
