// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void formatOmitsZeroParts() {
        assertEquals("0 md", Money.format(0));
        assertEquals("5 md", Money.format(5));
        assertEquals("1 sr", Money.format(10));
        assertEquals("1 zł", Money.format(100));
        assertEquals("3 zł 4 sr 5 md", Money.format(345));
        assertEquals("3 zł 5 md", Money.format(305));
        assertEquals("12 zł 3 sr", Money.format(1230));
        assertEquals("-1 zł 5 sr", Money.format(-150));
        assertEquals("-7 md", Money.format(-7));
    }

    @Test
    void formatExtremes() {
        assertEquals("92233720368547758 zł 7 md", Money.format(Long.MAX_VALUE));
        assertEquals("-92233720368547758 zł 8 md", Money.format(Long.MIN_VALUE));
    }

    @Test
    void parseExamplesFromApi() {
        assertEquals(Optional.of(150L), Money.parse("150"));
        assertEquals(Optional.of(300L), Money.parse("3zl"));
        assertEquals(Optional.of(300L), Money.parse("3zł"));
        assertEquals(Optional.of(150L), Money.parse("1zł 5sr"));
        assertEquals(Optional.of(23L), Money.parse("2 sr 3 md"));
        assertEquals(Optional.of(0L), Money.parse("0"));
    }

    @Test
    void parseVariants() {
        assertEquals(Optional.of(125L), Money.parse("1 korona, 2 srebrniki i 5 miedziaków"));
        assertEquals(Optional.of(345L), Money.parse("  3 ZŁ 4 SR 5 MD "));
        assertEquals(Optional.of(345L), Money.parse("5md 4sr 3zl"));
        assertEquals(Optional.of(105L), Money.parse("1zł 5"));
        assertEquals(Optional.of(120L), Money.parse("1 zł. 2 sr."));
        assertEquals(Optional.of(110L), Money.parse("1zl+1sr"));
        assertEquals(Optional.of(7L), Money.parse("7 m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "-5", "-1zł", "1.5zł", "1,5", "abc", "5 euro", "1zł 2zł", "5 5", "zł",
        "1zł,", "99999999999999999999", "92233720368547759 zł", "1zł -5sr", "1 i"})
    void parseRejectsInvalid(final String input) {
        assertTrue(Money.parse(input).isEmpty(), input);
    }

    @Test
    void parseNull() {
        assertTrue(Money.parse(null).isEmpty());
    }

    @Test
    void formatParseRoundTrip() {
        for (long v = 0; v < 5000; v += 7) {
            assertEquals(Optional.of(v), Money.parse(Money.format(v)), Money.format(v));
        }
        assertEquals(Optional.of(Long.MAX_VALUE), Money.parse(Money.format(Long.MAX_VALUE)));
    }
}
