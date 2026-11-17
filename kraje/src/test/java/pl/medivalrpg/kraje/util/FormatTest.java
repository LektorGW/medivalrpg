// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FormatTest {

    @Test
    void polishPlurals() {
        assertEquals("1 chunk", Format.chunks(1));
        assertEquals("2 chunki", Format.chunks(2));
        assertEquals("5 chunków", Format.chunks(5));
        assertEquals("12 chunków", Format.chunks(12));
        assertEquals("22 chunki", Format.chunks(22));
        assertEquals("24 chunki", Format.chunks(24));
    }

    @Test
    void areaFormat() {
        assertEquals("24 chunki • 6 144 bl²", Format.area(24));
        assertEquals("1 000 chunków • 256 000 bl²", Format.area(1000));
    }

    @Test
    void htmlEscaping() {
        assertEquals("&lt;b&gt;x&amp;y&quot;&#39;", Format.html("<b>x&y\"'"));
    }

    @Test
    void colorParsing() {
        assertEquals(0xE53935, Colors.parse("#e53935"));
        assertEquals(0xE53935, Colors.parse("E53935"));
        assertEquals(0xE53935, Colors.parse("czerwony"));
        assertEquals(0xFDD835, Colors.parse("Żółty"));
        assertEquals(0xF5F5F5, Colors.parse("biały"));
        assertEquals(0x55FF55, Colors.parse("green"));
        assertNull(Colors.parse("#12345"));
        assertNull(Colors.parse("tęczowy"));
    }
}
