// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.economy.Money;

class TabFormatTest {

    @Test
    void tpsIsCappedAndUsesPolishComma() {
        assertEquals("20,0", TabFormat.tps(20.004));
        assertEquals("20,0", TabFormat.tps(21.3));
        assertEquals("19,8", TabFormat.tps(19.79));
        assertEquals("0,0", TabFormat.tps(Double.NaN));
        assertEquals("0,0", TabFormat.tps(-3));
    }

    @Test
    void colourLevels() {
        assertEquals(0, TabFormat.tpsLevel(19.9));
        assertEquals(0, TabFormat.tpsLevel(18.0));
        assertEquals(1, TabFormat.tpsLevel(17.9));
        assertEquals(2, TabFormat.tpsLevel(14.9));
        assertEquals(0, TabFormat.pingLevel(35));
        assertEquals(1, TabFormat.pingLevel(100));
        assertEquals(2, TabFormat.pingLevel(250));
        assertEquals("green", TabFormat.levelColor(0));
        assertEquals("yellow", TabFormat.levelColor(1));
        assertEquals("red", TabFormat.levelColor(2));
        assertEquals("42 ms", TabFormat.ping(42));
        assertEquals("? ms", TabFormat.ping(-1));
    }

    @Test
    void gameClock() {
        assertEquals("06:00", TabFormat.gameTime(0));
        assertEquals("12:00", TabFormat.gameTime(6000));
        assertEquals("18:00", TabFormat.gameTime(12000));
        assertEquals("00:00", TabFormat.gameTime(18000));
        assertEquals("06:30", TabFormat.gameTime(500));
        assertEquals("06:00", TabFormat.gameTime(24000));
        assertEquals(1, TabFormat.gameDay(0));
        assertEquals(1, TabFormat.gameDay(23999));
        assertEquals(3, TabFormat.gameDay(48000));
    }

    @Test
    void tipRotatesForEverybodyTheSame() {
        final List<String> tips = List.of("a", "b", "c");
        assertEquals("a", TabFormat.tip(tips, 0, 60));
        assertEquals("a", TabFormat.tip(tips, 59_999, 60));
        assertEquals("b", TabFormat.tip(tips, 60_000, 60));
        assertEquals("c", TabFormat.tip(tips, 120_000, 60));
        assertEquals("a", TabFormat.tip(tips, 180_000, 60));
        assertNull(TabFormat.tip(List.of(), 0, 60));
        assertEquals(-1, TabFormat.tipIndex(0, 60, 0));
        assertEquals(0, TabFormat.tipIndex(5_000, 0, 1), "period below 1 s is clamped");
    }

    @Test
    void shortenAndUrls() {
        assertEquals("Kolej", TabFormat.shorten(" Kolej ", 10));
        assertEquals("Nowa ekono…", TabFormat.shorten("Nowa ekonomia świata", 11));
        assertEquals("", TabFormat.shorten(null, 10));
        assertEquals("panel.example.workers.dev", TabFormat.displayUrl("https://panel.example.workers.dev/"));
        assertEquals("mapa.example.pl/x", TabFormat.displayUrl("http://mapa.example.pl/x"));
        assertEquals("", TabFormat.displayUrl(null));
    }

    @Test
    void balanceFallbackFormatMatchesProjectCurrency() {
        // Without Skarbiec Kraje formats with Money (same units as Skarbiec: 1 zł = 100 md, 1 sr = 10 md)
        assertEquals("123 zł 4 sr 5 md", Money.format(12_345));
        assertEquals("0 md", Money.format(0));
        assertEquals("1 zł", Money.format(100));
    }
}
