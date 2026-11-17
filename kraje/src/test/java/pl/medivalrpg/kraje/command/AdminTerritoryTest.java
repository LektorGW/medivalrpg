// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.command.AdminTerritory.Mode;
import pl.medivalrpg.kraje.command.AdminTerritory.Op;
import pl.medivalrpg.kraje.command.AdminTerritory.Request;
import pl.medivalrpg.kraje.manager.KrajeException;

/** Argument parsing of the admin area tools (no server needed). */
class AdminTerritoryTest {

    private static Request parse(final Op op, final String input) {
        return AdminTerritory.parse(op, KrajCommandTree.tokens(input));
    }

    private static String error(final Op op, final String input) {
        return assertThrows(KrajeException.class, () -> parse(op, input)).messageKey();
    }

    @Test
    void claimModes() {
        final Request here = parse(Op.CLAIM, "Żmudź");
        assertEquals(Mode.HERE, here.mode());
        assertEquals("Żmudź", here.country());
        assertFalse(here.confirm());

        final Request radius = parse(Op.CLAIM, "Żmudź promien 5");
        assertEquals(Mode.RADIUS, radius.mode());
        assertEquals(5, radius.amount());
        assertEquals(" promien 5", radius.areaArgs());

        final Request selection = parse(Op.CLAIM, "Żmudź zaznaczenie potwierdz");
        assertEquals(Mode.SELECTION, selection.mode());
        assertTrue(selection.confirm());
        assertEquals(" zaznaczenie", selection.areaArgs());

        // keywords are case-insensitive, radius 0 = the current chunk only
        assertEquals(0, parse(Op.CLAIM, "Żmudź PROMIEN 0 POTWIERDZ").amount());
    }

    @Test
    void countryNamesWithSpaces() {
        final Request r = parse(Op.UNCLAIM, "Wolne Miasto promien 3 potwierdz");
        assertEquals("Wolne Miasto", r.country());
        assertEquals("Wolne", r.firstWord());
        assertEquals(Mode.RADIUS, r.mode());
        assertTrue(r.confirm());
        assertEquals("Wolne Miasto", parse(Op.RINGS, "Wolne Miasto 2").country());
    }

    @Test
    void radiusLimitsAndUsage() {
        assertEquals("admin-area-radius-range", error(Op.CLAIM, "Żmudź promien 17"));
        assertEquals("admin-area-radius-range", error(Op.CLAIM, "Żmudź promien -1"));
        assertEquals("admin-area-radius-range", error(Op.CLAIM, "Żmudź promien dużo"));
        assertEquals("usage-admin-zajmij", error(Op.CLAIM, "Żmudź promien"));
        assertEquals("usage-admin-zajmij", error(Op.CLAIM, "promien 3"));
        assertEquals("usage-admin-porzuc", error(Op.UNCLAIM, "zaznaczenie"));
        assertEquals(16, parse(Op.CLAIM, "Żmudź promien 16").amount());
    }

    @Test
    void rings() {
        final Request one = parse(Op.RINGS, "Norvik");
        assertEquals(Mode.RINGS, one.mode());
        assertEquals(1, one.amount());
        assertEquals(" 1", one.areaArgs());
        final Request three = parse(Op.RINGS, "Norvik 3 potwierdz");
        assertEquals(3, three.amount());
        assertTrue(three.confirm());
        assertEquals("admin-area-rings-range", error(Op.RINGS, "Norvik 0"));
        assertEquals("admin-area-rings-range", error(Op.RINGS, "Norvik 17"));
        assertEquals("usage-admin-przylacz", error(Op.RINGS, ""));
        // a country called like a number is still a country when it is the only word
        assertEquals("1918", parse(Op.RINGS, "1918").country());
    }

    @Test
    void confirmedRequestMatchesThePreviewedOne() {
        final Request previewed = parse(Op.CLAIM, "Żmudź promien 5");
        final Request confirmed = parse(Op.CLAIM, "Żmudź promien 5 potwierdz");
        assertEquals(new Request(previewed.op(), previewed.mode(), previewed.country(), previewed.firstWord(),
            previewed.amount(), true), confirmed);
    }
}
