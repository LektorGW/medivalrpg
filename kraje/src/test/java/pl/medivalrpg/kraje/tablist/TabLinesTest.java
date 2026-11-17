// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TabLinesTest {

    @Test
    void lineWithMissingPlaceholderDisappears() {
        assertNull(TabLines.line("<gray>Saldo: <gold><balance></gold></gray>", Set.of("balance")));
        assertEquals("<gray>Saldo: <gold><balance></gold></gray>",
            TabLines.line("<gray>Saldo: <gold><balance></gold></gray>", Set.of("news")));
    }

    @Test
    void optionalPartDisappearsAlone() {
        final String line = "<gray><date>[[ • <season_icon> <season>]]</gray>";
        assertEquals("<gray><date></gray>", TabLines.line(line, Set.of("season", "season_icon")));
        assertEquals("<gray><date> • <season_icon> <season></gray>", TabLines.line(line, Set.of()));
    }

    @Test
    void lineOfOnlyMissingOptionalPartsDisappears() {
        final String links = "[[<gray>Mapa: <map_link></gray>]][[ • <gray>Panel: <panel_link></gray>]]";
        assertNull(TabLines.line(links, Set.of("map_link", "panel_link")));
        assertEquals(" • <gray>Panel: <panel_link></gray>", TabLines.line(links, Set.of("map_link")));
        assertEquals("<gray>Mapa: <map_link></gray>", TabLines.line(links, Set.of("panel_link")));
        assertNull(TabLines.line("<gray>[[<news>]]</gray>", Set.of("news")), "only formatting tags left");
        assertEquals("<gray><date></gray>", TabLines.line("<gray><date>[[<news>]]</gray>", Set.of("news")),
            "a placeholder left counts as content");
    }

    @Test
    void missingOutsideOptionalDropsWholeLine() {
        assertNull(TabLines.line("<tip>[[ <news>]]", Set.of("tip")));
        assertNull(TabLines.line("[[<news>]] <tip>", Set.of("tip")));
    }

    @Test
    void emptyLinesStayForSpacing() {
        assertEquals(List.of("", "a", ""), TabLines.filter(List.of("", "a", "<news>", ""), Set.of("news")));
    }

    @Test
    void placeholderMatchingIsExact() {
        assertTrue(TabLines.uses("<era>", "era"));
        assertTrue(TabLines.uses("<era:x>", "era"));
        assertFalse(TabLines.uses("<era_icon>", "era"));
        assertTrue(TabLines.uses("<era_icon>", "era_icon"));
        assertFalse(TabLines.uses("<season_day>", "season"));
        assertFalse(TabLines.uses("plain text", "season"));
        assertFalse(TabLines.usesAny("<news>", Set.of()));
    }
}
