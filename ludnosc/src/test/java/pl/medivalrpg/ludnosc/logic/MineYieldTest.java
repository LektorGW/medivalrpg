// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MineYieldTest {

    private final MineYield y = MineYield.defaults();

    @Test
    void slotsGrowWithLevel() {
        assertEquals(2, this.y.slots(1));
        assertEquals(4, this.y.slots(2));
        assertEquals(10, this.y.slots(5));
        assertEquals(10, this.y.slots(9), "level capped at 5");
        assertEquals(2, this.y.slots(0));
    }

    @Test
    void outputScalesWithMinersLevelAndTools() {
        final MineYield.Output one = this.y.daily(1, 1, 1.0, 1.0);
        assertEquals(6, one.ore());
        assertEquals(10, one.stone());
        assertEquals(0, one.precious());
        final MineYield.Output two = this.y.daily(2, 1, 1.0, 1.0);
        assertEquals(2 * one.ore(), two.ore(), "proportional to miners");
        assertEquals(two, this.y.daily(7, 1, 1.0, 1.0), "a level 1 mine employs only 2 miners");
        final MineYield.Output lvl3 = this.y.daily(2, 3, 1.0, 1.0);
        assertEquals(19, lvl3.ore(), "+30% per level: 6 x 2 x 1.6 = 19.2");
        final double tools = this.y.toolFactor(true, 2);
        assertEquals(1.4, tools, 1e-9, "smithy +25%, renaissance +15%");
        final MineYield.Output big = this.y.daily(10, 5, tools, 1.25);
        assertEquals(231, big.ore(), "6 x 10 x 2.2 x 1.4 x 1.25 = 231");
        assertTrue(big.precious() > 0, "a big mine finds precious metal every day");
        assertEquals(MineYield.NONE, this.y.daily(0, 5, tools, 1));
        assertTrue(this.y.daily(4, 2, 1, 0.6).ore() < this.y.daily(4, 2, 1, 1).ore(), "bad mood lowers the output");
    }

    @Test
    void meaningfulComparedToTheOldMine() {
        // old: 8 ore per mine + 2 per level a day, nothing sold below 512 in stock
        final MineYield.Output out = this.y.daily(4, 2, this.y.toolFactor(true, 1), 1.0);
        assertTrue(out.ore() >= 3 * (8 + 2 * 2), "4 miners at level 2 yield at least 3x the old mine: " + out.ore());
        assertEquals((long) Math.floor(out.ore() * 0.6), this.y.sold(out.ore()));
        assertEquals(0, this.y.sold(-5));
    }

    @Test
    void diggingProgressPerMiner() {
        assertEquals(4.0, this.y.steps(1, 3_600_000L), 1e-9);
        assertEquals(12.0, this.y.steps(3, 3_600_000L), 1e-9);
        assertEquals(0.0, this.y.steps(0, 3_600_000L), 1e-9);
        assertEquals(0.0, this.y.steps(-2, 3_600_000L), 1e-9);
        // one mine tick (40 ticks = 2 s) of 4 miners: about 9 thousandths of a step
        assertEquals(9, Math.round(this.y.steps(4, 2000L) * 1000));
    }

    @Test
    void orePrices() {
        assertArrayEquals(new int[] {1, 0}, MineYield.oreValue("minecraft:coal_ore"));
        assertArrayEquals(new int[] {2, 0}, MineYield.oreValue("minecraft:deepslate_iron_ore"));
        assertArrayEquals(new int[] {0, 2}, MineYield.oreValue("minecraft:gold_ore"));
        assertArrayEquals(new int[] {0, 5}, MineYield.oreValue("minecraft:diamond_ore"));
        assertArrayEquals(new int[] {0, 0}, MineYield.oreValue("minecraft:stone"));
        assertArrayEquals(new int[] {0, 0}, MineYield.oreValue(null));
        assertEquals("minecraft:stone_pickaxe", MineYield.pickaxe(false, 1));
        assertEquals("minecraft:iron_pickaxe", MineYield.pickaxe(true, 1));
        assertEquals("minecraft:iron_pickaxe", MineYield.pickaxe(false, 2));
    }

    @Test
    void negativeConfigIsClamped() {
        final MineYield bad = new MineYield(-1, -1, -1, -1, 0, -3, -1, -1, -1, 7);
        assertEquals(1, bad.slots(5));
        assertEquals(MineYield.NONE, bad.daily(3, 3, 1, 1));
        assertEquals(1.0, bad.toolFactor(true, 4), 1e-9);
        assertEquals(10, bad.sold(10), "sell share capped at 100%");
    }
}
