// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcSettings;

class ParamsTest {

    private static Params p(final String json) {
        return Params.of(JsonParser.parseString(json));
    }

    private static void rejects(final Runnable r, final String fragment) {
        final RpcException e = assertThrows(RpcException.class, r::run);
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void envelope() {
        assertFalse(Params.of(null).has("x"));
        assertFalse(p("null").has("x"));
        rejects(() -> p("[1,2]"), "obiektem");
        rejects(() -> p("\"x\""), "obiektem");
    }

    @Test
    void missingAndWrongType() {
        rejects(() -> p("{}").string("text", 0, 10), "Brak parametru „text”");
        rejects(() -> p("{\"text\":null}").string("text", 0, 10), "Brak parametru");
        rejects(() -> p("{\"text\":5}").string("text", 0, 10), "oczekiwano tekstu");
        rejects(() -> p("{\"text\":{\"a\":1}}").string("text", 0, 10), "wartości prostej");
        rejects(() -> p("{\"n\":\"abc\"}").integer("n", 0, 10), "liczby całkowitej");
        rejects(() -> p("{\"b\":\"true\"}").bool("b"), "true/false");
    }

    @Test
    void stringsAreCleanedAndLengthChecked() {
        assertEquals("Ala ma kota", p("{\"t\":\"  Ala\\u0000 ma\\u200B kota§ \\n\"}").string("t", 0, 50));
        rejects(() -> p("{\"t\":\"abcdef\"}").string("t", 1, 5), "1–5");
        rejects(() -> p("{\"t\":\"   \"}").string("t", 1, 5), "1–5");
        assertEquals("", p("{\"t\":\"\"}").string("t", 0, 5));
        assertTrue(p("{\"t\":\" \"}").absentOrEmpty("t"));
        assertTrue(p("{}").absentOrEmpty("t"));
        assertFalse(p("{\"t\":\"x\"}").absentOrEmpty("t"));
        rejects(() -> p("{\"t\":\"" + "a".repeat(5000) + "\"}").string("t", 0, 200), "0–200");
    }

    @Test
    void nicks() {
        assertEquals("Gracz_1", p("{\"n\":\"Gracz_1\"}").nick("n"));
        rejects(() -> p("{\"n\":\"ab\"}").nick("n"), "nick");
        rejects(() -> p("{\"n\":\"za_dlugi_nick_123\"}").nick("n"), "nick");
        rejects(() -> p("{\"n\":\"zły\"}").nick("n"), "nick");
        rejects(() -> p("{\"n\":\"a b c\"}").nick("n"), "nick");
    }

    @Test
    void uuids() {
        final UUID u = UUID.randomUUID();
        assertEquals(u, p("{\"id\":\"" + u + "\"}").uuid("id"));
        rejects(() -> p("{\"id\":\"nie-uuid\"}").uuid("id"), "UUID");
        rejects(() -> p("{\"id\":\"1-1-1-1-1\"}").uuid("id"), "UUID");
        rejects(() -> p("{\"id\":\"" + u.toString().replace('-', 'x') + "\"}").uuid("id"), "UUID");
    }

    @Test
    void integers() {
        assertEquals(5, p("{\"n\":5}").integer("n", 1, 64));
        assertEquals(5, p("{\"n\":\"5\"}").integer("n", 1, 64));
        rejects(() -> p("{\"n\":0}").integer("n", 1, 64), "zakres 1–64");
        rejects(() -> p("{\"n\":65}").integer("n", 1, 64), "zakres");
        rejects(() -> p("{\"n\":1.5}").integer("n", 1, 64), "całkowitej");
        rejects(() -> p("{\"n\":1e400}").integer("n", 1, 64), "całkowitej");
        assertEquals(-29_999_000, p("{\"x\":-29999000}").integer("x", -29_999_000, 29_999_000));
    }

    @Test
    void booleans() {
        assertTrue(p("{\"b\":true}").bool("b"));
        assertFalse(p("{\"b\":false}").bool("b"));
        rejects(() -> p("{\"b\":1}").bool("b"), "true/false");
    }

    @Test
    void colors() {
        assertEquals(0xA83232, p("{\"c\":\"#A83232\"}").color("c"));
        assertEquals(0xA83232, p("{\"c\":\"a83232\"}").color("c"));
        assertEquals(0x00FF00, p("{\"c\":65280}").color("c"));
        rejects(() -> p("{\"c\":\"#GG0000\"}").color("c"), "#RRGGBB");
        rejects(() -> p("{\"c\":\"#FFF\"}").color("c"), "#RRGGBB");
        rejects(() -> p("{\"c\":16777216}").color("c"), "zakres");
    }

    @Test
    void enums() {
        assertEquals("ADVISOR", p("{\"r\":\"advisor\"}").oneOf("r", List.of("ADVISOR", "CITIZEN")));
        rejects(() -> p("{\"r\":\"RULER\"}").oneOf("r", List.of("ADVISOR", "CITIZEN")), "ADVISOR, CITIZEN");
        assertEquals("max-chunki", p("{\"p\":\"MAX-CHUNKI\"}").oneOf("p", NpcSettings.PARAMS));
        rejects(() -> p("{\"p\":\"zloto\"}").oneOf("p", NpcSettings.PARAMS), "agresja");
    }

    @Test
    void amounts() {
        assertEquals(150, p("{\"a\":150}").amount("a", false));
        assertEquals(350, p("{\"a\":\"3zł 5sr\"}").amount("a", false));
        rejects(() -> p("{\"a\":-5}").amount("a", false), "dodatnia");
        rejects(() -> p("{\"a\":0}").amount("a", false), "dodatnia");
        rejects(() -> p("{\"a\":true}").amount("a", false), "dodatnia");
        assertEquals(-200, p("{\"a\":\"-2zł\"}").amount("a", true));
        assertEquals(-7, p("{\"a\":-7}").amount("a", true));
        assertEquals(0, p("{\"a\":0}").amountOrZero("a"));
        assertEquals(0, p("{\"a\":\"brak\"}").amountOrZero("a"));
        assertEquals(20, p("{\"a\":\"2sr\"}").amountOrZero("a"));
        rejects(() -> p("{\"a\":-1}").amountOrZero("a"), "dodatnia");
    }

    @Test
    void scalars() {
        assertEquals("50", p("{\"v\":50}").scalar("v"));
        assertEquals("tak", p("{\"v\":true}").scalar("v"));
        assertEquals("nie", p("{\"v\":\"NIE\"}").scalar("v"));
        rejects(() -> p("{\"v\":2.5}").scalar("v"), "całkowitej");
        rejects(() -> p("{\"v\":\"" + "x".repeat(17) + "\"}").scalar("v"), "1–16");
    }
}
