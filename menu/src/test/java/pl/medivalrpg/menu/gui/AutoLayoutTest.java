// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoLayoutTest {

    private static final List<Integer> MAIN = List.of(20, 21, 22, 23, 24, 29, 30, 31, 32, 33);

    @Test
    void fullRows() {
        assertEquals(MAIN, AutoLayout.assign(MAIN, 10));
    }

    @Test
    void partialLastRowIsCentred() {
        // Skarbiec and Armia missing → 8 buttons: full first row, 3 centred in the second
        assertEquals(List.of(20, 21, 22, 23, 24, 30, 31, 32), AutoLayout.assign(MAIN, 8));
        assertEquals(List.of(20, 21, 22, 23, 24, 31), AutoLayout.assign(MAIN, 6));
        assertEquals(List.of(21, 22, 23), AutoLayout.assign(MAIN, 3));
        assertEquals(List.of(22), AutoLayout.assign(MAIN, 1));
    }

    @Test
    void overflowAndEmpty() {
        assertEquals(MAIN, AutoLayout.assign(MAIN, 15));
        assertTrue(AutoLayout.assign(MAIN, 0).isEmpty());
        assertTrue(AutoLayout.assign(List.of(), 3).isEmpty());
    }

    @Test
    void borderSlotsOfSixRows() {
        final List<Integer> border = MenuService.borderSlots(6);
        assertEquals(9 + 9 + 4 * 2, border.size());
        assertTrue(border.contains(0) && border.contains(8) && border.contains(45) && border.contains(53));
        assertTrue(border.contains(9) && border.contains(17));
        assertTrue(!border.contains(10) && !border.contains(22));
    }

    @Test
    void numberFormat() {
        assertEquals("3", MenuService.format(3.0));
        assertEquals("2.5", MenuService.format(2.5));
    }
}
