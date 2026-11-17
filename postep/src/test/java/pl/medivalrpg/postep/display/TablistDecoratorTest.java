// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The decorator steps aside when Kraje's tab-list format shows the era itself. */
class TablistDecoratorTest {

    @Test
    void detectsKrajeEraPlaceholders() {
        assertTrue(TablistDecorator.krajeShowsEra("<rank_icon><era_icon><color>[<country>]</color> <white><player>"));
        assertTrue(TablistDecorator.krajeShowsEra("<era> <color>[<country>]</color> <player>"));
        assertFalse(TablistDecorator.krajeShowsEra("<rank_icon><color>[<country>]</color> <white><player>"));
        assertFalse(TablistDecorator.krajeShowsEra(null));
    }

    @Test
    void usesTheNameFormatOfTheActiveKrajeStyle() {
        final String classic = "<color>[<country>]</color> <player>";
        final String extended = "<afk><rank_icon><era_icon><color>[<country>]</color> <white><player>";
        // old Kraje: no tablist.style at all → classic tablist.format (old behaviour)
        assertEquals(classic, TablistDecorator.krajeNameFormat(null, classic, null));
        assertFalse(TablistDecorator.krajeShowsEra(TablistDecorator.krajeNameFormat(null, classic, null)));
        // new Kraje, old server config (style from jar defaults): the extended name format shows the era → step aside
        assertEquals(extended, TablistDecorator.krajeNameFormat("extended", classic, extended));
        assertTrue(TablistDecorator.krajeShowsEra(TablistDecorator.krajeNameFormat("extended", classic, extended)));
        // explicit classic style goes back to tablist.format
        assertEquals(classic, TablistDecorator.krajeNameFormat(" Classic ", classic, extended));
        assertEquals(classic, TablistDecorator.krajeNameFormat("klasyczna", classic, extended));
        // extended without its own format (should not happen with a new jar) → tablist.format
        assertEquals(classic, TablistDecorator.krajeNameFormat("extended", classic, null));
    }
}
