// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class AmountParserTest {

    private static long ok(final String s, final boolean signed) {
        final OptionalLong v = AmountParser.parse(s, signed);
        assertTrue(v.isPresent(), "should parse: " + s);
        return v.getAsLong();
    }

    private static void bad(final String s, final boolean signed) {
        assertTrue(AmountParser.parse(s, signed).isEmpty(), "should reject: " + s);
    }

    @Test
    void plainCopper() {
        assertEquals(150, ok("150", false));
        assertEquals(7, ok(" 7 ", false));
        assertEquals(5, ok("5md", false));
        assertEquals(5, ok("5 md", false));
    }

    @Test
    void unitsAndCombinations() {
        assertEquals(300, ok("3zł", false));
        assertEquals(300, ok("3zl", false));
        assertEquals(300, ok("3 ZŁ", false));
        assertEquals(350, ok("3zł 5sr", false));
        assertEquals(352, ok("3 zl 5 sr 2 md", false));
        assertEquals(352, ok("3zł5sr2md", false));
        assertEquals(20, ok("2s", false));
        assertEquals(100, ok("1z", false));
        assertEquals(1234, ok("12zł 34", false));
        assertEquals(305, ok("3zł 5", false));
    }

    @Test
    void rejectsGarbageZeroAndFractions() {
        bad("", false);
        bad("   ", false);
        bad(null, false);
        bad("0", false);
        bad("0zł", false);
        bad("abc", false);
        bad("2,5zł", false);
        bad("2.5", false);
        bad("1 2", false);
        bad("5x", false);
        bad("3zloty", false);
        bad("zł", false);
        bad("1e5", false);
    }

    @Test
    void signOnlyWhenAllowed() {
        bad("-5", false);
        bad("+5", false);
        assertEquals(-200, ok("-2zł", true));
        assertEquals(50, ok("+50", true));
        bad("-", true);
        bad("-0", true);
    }

    @Test
    void bounds() {
        assertEquals(AmountParser.MAX, ok("10000000zł", false));
        bad("10000001zł", false);
        bad("1000000001", false);
        bad("999999999999zł", false);
        bad("9".repeat(41), false);
    }

    @Test
    void jsonNumbers() {
        assertEquals(150, AmountParser.ofNumber(150, false).getAsLong());
        assertTrue(AmountParser.ofNumber(1.5, false).isEmpty());
        assertTrue(AmountParser.ofNumber(0, true).isEmpty());
        assertTrue(AmountParser.ofNumber(-3, false).isEmpty());
        assertEquals(-3, AmountParser.ofNumber(-3, true).getAsLong());
        assertTrue(AmountParser.ofNumber(Double.NaN, true).isEmpty());
        assertTrue(AmountParser.ofNumber(2e12, true).isEmpty());
    }

    @Test
    void format() {
        assertEquals("0 md", AmountParser.format(0));
        assertEquals("3 zł 4 sr 5 md", AmountParser.format(345));
        assertEquals("1 zł", AmountParser.format(100));
        assertEquals("5 sr", AmountParser.format(50));
        assertEquals("-2 zł 1 md", AmountParser.format(-201));
    }
}
