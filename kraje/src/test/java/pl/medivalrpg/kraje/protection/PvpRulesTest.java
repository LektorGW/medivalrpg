// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.protection.PvpRules.Mode;
import pl.medivalrpg.kraje.protection.PvpRules.Settings;

class PvpRulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void vanillaDefaultNeverBlocks() {
        final Settings s = new Settings(Mode.VANILLA, true, false, false);
        assertTrue(PvpRules.allowed(s, A, B, Relation.NEUTRAL, false));
        assertTrue(PvpRules.allowed(s, A, A, Relation.NEUTRAL, false), "same country");
        assertTrue(PvpRules.allowed(s, A, B, Relation.ALLIANCE, false), "allies");
        assertTrue(PvpRules.allowed(s, null, B, Relation.NEUTRAL, false), "no country");
        assertTrue(PvpRules.allowed(s, null, null, Relation.NEUTRAL, true));
    }

    @Test
    void vanillaWithoutFriendlyFire() {
        final Settings s = new Settings(Mode.VANILLA, false, false, false);
        assertFalse(PvpRules.allowed(s, A, A, Relation.NEUTRAL, false));
        assertFalse(PvpRules.allowed(s, A, B, Relation.ALLIANCE, true));
        assertTrue(PvpRules.allowed(s, A, B, Relation.NEUTRAL, false));
        assertTrue(PvpRules.allowed(s, null, B, Relation.NEUTRAL, false));
    }

    @Test
    void warOnly() {
        final Settings s = new Settings(Mode.WAR_ONLY, true, false, false);
        assertTrue(PvpRules.allowed(s, A, B, Relation.WAR, false));
        assertTrue(PvpRules.allowed(s, A, B, Relation.WAR, true));
        assertFalse(PvpRules.allowed(s, A, B, Relation.NEUTRAL, false));
        assertTrue(PvpRules.allowed(s, A, A, Relation.NEUTRAL, false), "friendly fire on");
        assertFalse(PvpRules.allowed(s, null, B, Relation.NEUTRAL, false), "without-country: false");
        assertFalse(PvpRules.allowed(s, A, null, Relation.NEUTRAL, false));
        final Settings open = new Settings(Mode.WAR_ONLY, false, true, true);
        assertTrue(PvpRules.allowed(open, null, B, Relation.NEUTRAL, false));
        assertTrue(PvpRules.allowed(open, A, B, Relation.NEUTRAL, true), "wilderness-free");
        assertFalse(PvpRules.allowed(open, A, B, Relation.NEUTRAL, false));
        assertFalse(PvpRules.allowed(open, A, A, Relation.NEUTRAL, true), "friendly fire off wins even in the wilderness");
    }

    @Test
    void modeParsing() {
        assertEquals(Mode.VANILLA, Mode.parse("vanilla"));
        assertEquals(Mode.WAR_ONLY, Mode.parse("war-only"));
        assertEquals(Mode.WAR_ONLY, Mode.parse("WAR_ONLY"));
        assertEquals(Mode.VANILLA, Mode.parse("cokolwiek"));
        assertEquals(Mode.VANILLA, Mode.parse(null));
    }
}
