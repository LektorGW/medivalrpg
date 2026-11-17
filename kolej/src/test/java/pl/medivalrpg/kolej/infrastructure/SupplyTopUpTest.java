// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SupplyTopUpTest {
    @Test void terminalTopUpFillsWhatTheLowRuleWouldNot() {
        assertFalse(SupplyRules.needs(300, 2500), "above 25 %: legacy rule waits");
        assertTrue(SupplyRules.topUp(300, 2500));
        assertFalse(SupplyRules.topUp(480, 3600), "less than 3 coal and 2 buckets of room: no transfer");
        assertTrue(SupplyRules.wants(SupplyKind.WEGIEL, 300, 4000, true));
        assertFalse(SupplyRules.wants(SupplyKind.WEGIEL, 300, 4000, false));
        assertFalse(SupplyRules.wants(SupplyKind.WIEZA, 300, 4000, true), "water already full");
        assertTrue(SupplyRules.wants(SupplyKind.WIEZA, 500, 900, false), "low water always");
        assertTrue(SupplyRules.topUp(Double.NaN, 0), "invalid record is never treated as full");
    }
}
