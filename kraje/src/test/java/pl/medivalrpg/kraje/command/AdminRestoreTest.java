// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.command.AdminRestore.Kind;
import pl.medivalrpg.kraje.command.AdminRestore.Mode;
import pl.medivalrpg.kraje.command.AdminRestore.Request;
import pl.medivalrpg.kraje.manager.KrajeException;

/** Argument parsing of {@code /kraj admin przywroc} and {@code /kraj admin usun <kraj> przywroc} (no server needed). */
class AdminRestoreTest {

    private static Request parse(final String input) {
        return AdminRestore.parse(KrajCommandTree.tokens(input));
    }

    private static String error(final String input) {
        return assertThrows(KrajeException.class, () -> parse(input)).messageKey();
    }

    @Test
    void wholeCountry() {
        final Request r = parse("Testowia");
        assertEquals(Kind.COUNTRY, r.kind());
        assertEquals(Mode.ALL, r.mode());
        assertEquals("Testowia", r.country());
        assertFalse(r.confirm());
        assertFalse(r.delete());
        assertEquals("", r.areaArgs());
        assertTrue(parse("Testowia POTWIERDZ").confirm());
        assertEquals(r.confirmed(), parse("Testowia potwierdz"));
    }

    @Test
    void countryParts() {
        final Request radius = parse("Wolne Miasto promien 3 potwierdz");
        assertEquals(Kind.COUNTRY, radius.kind());
        assertEquals(Mode.RADIUS, radius.mode());
        assertEquals("Wolne Miasto", radius.country());
        assertEquals("Wolne", radius.firstWord());
        assertEquals(3, radius.amount());
        assertTrue(radius.confirm());
        assertEquals(" promien 3", radius.areaArgs());

        final Request selection = parse("Testowia zaznaczenie");
        assertEquals(Mode.SELECTION, selection.mode());
        assertEquals(" zaznaczenie", selection.areaArgs());
    }

    @Test
    void wilderness() {
        final Request r = parse("teren promien 0");
        assertEquals(Kind.WILD, r.kind());
        assertEquals(Mode.RADIUS, r.mode());
        assertEquals(0, r.amount());
        assertEquals(Kind.WILD, parse("TEREN zaznaczenie potwierdz").kind());
        // "teren" alone is a country name (resolved later; without such a country → usage)
        assertEquals(Kind.COUNTRY, parse("teren").kind());
    }

    @Test
    void stop() {
        assertEquals(Kind.STOP, parse("stop").kind());
        assertEquals(Kind.STOP, parse("STOP").kind());
        assertEquals(Kind.COUNTRY, parse("stop promien 2").kind());
    }

    @Test
    void confirmationMatchesTheSameOperation() {
        // the clickable confirmation uses the stored name ("Testowia"), the admin may have typed "testowia"
        assertTrue(parse("testowia").confirmed().sameOperation(parse("Testowia potwierdz")));
        assertFalse(parse("Testowia promien 2").sameOperation(parse("Testowia promien 3 potwierdz")));
        assertFalse(parse("Testowia").sameOperation(parse("Testowia zaznaczenie potwierdz")));
        assertFalse(parse("Testowia").sameOperation(
            AdminRestore.parseDelete(KrajCommandTree.tokens("Testowia przywroc potwierdz"))));
    }

    @Test
    void errors() {
        assertEquals("usage-admin-przywroc", error(""));
        assertEquals("usage-admin-przywroc", error("potwierdz"));
        assertEquals("usage-admin-przywroc", error("Testowia promien"));
        assertEquals("usage-admin-przywroc", error("promien 3"));
        assertEquals("admin-area-radius-range", error("Testowia promien 17"));
        assertEquals("admin-area-radius-range", error("Testowia promien x"));
    }

    @Test
    void deleteWithRestore() {
        assertTrue(AdminRestore.isDeleteRestore(KrajCommandTree.tokens("Testowia przywroc")));
        assertTrue(AdminRestore.isDeleteRestore(KrajCommandTree.tokens("Testowia przywroc potwierdz")));
        assertFalse(AdminRestore.isDeleteRestore(KrajCommandTree.tokens("Testowia")));
        assertFalse(AdminRestore.isDeleteRestore(KrajCommandTree.tokens("przywroc")));
        assertFalse(AdminRestore.isDeleteRestore(KrajCommandTree.tokens("Testowia potwierdz")));

        final Request r = AdminRestore.parseDelete(KrajCommandTree.tokens("Wolne Miasto przywroc potwierdz"));
        assertTrue(r.delete());
        assertTrue(r.confirm());
        assertEquals(Kind.COUNTRY, r.kind());
        assertEquals(Mode.ALL, r.mode());
        assertEquals("Wolne Miasto", r.country());
        assertEquals("usage-admin-usun", assertThrows(KrajeException.class,
            () -> AdminRestore.parseDelete(KrajCommandTree.tokens("Testowia"))).messageKey());
    }
}
