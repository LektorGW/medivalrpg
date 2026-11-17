// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WageMoodTest {

    @Test
    void unpaidWagesHurtAndSavingsHelp() {
        final WageMood w = WageMood.defaults();
        assertEquals(0, w.points(0, 0));
        assertEquals(3, w.points(0, 30), "comfortable savings");
        assertTrue(w.points(10, 500) < 0, "unpaid workers outweigh the savings");
        assertTrue(w.points(100, 0) < w.points(10, 0), "more unpaid = worse");
        assertEquals(-12, w.points(100, 0), "capped");
        assertEquals(0, new WageMood(12, 3, 0).points(0, 1_000), "bonus off");
    }
}
