// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every visible worker role of Ludnosc maps to a work animation of Interakcje (INTEGRATION-NPC.md). */
class WorkAnimRolesTest {

    /** Names of {@code WorkAnimations#animations()} in the Interakcje candidate npc-20261029. */
    private static final Set<String> KNOWN = Set.of("budowa", "noszenie_plecy", "noszenie_rece", "kilof", "motyka", "siekiera",
        "kowadlo", "zaproszenie", "salut", "straz", "ziewanie", "odpoczynek");

    @Test
    void rolesAtWork() {
        assertEquals("budowa", BuilderCrew.workAnim("Dom", false));
        assertEquals("budowa", BuilderCrew.workAnim("Huta szkła", false)); // a builder of a glassworks, not a glassmaker
        assertEquals("kilof", BuilderCrew.workAnim("Górnik", false));
        assertEquals("kilof", BuilderCrew.workAnim("Kamieniarz", false));
        assertEquals("kilof", BuilderCrew.workAnim("Hutnik", false));
        assertEquals("kilof", BuilderCrew.workAnim("Ceglarz", false));
        assertEquals("siekiera", BuilderCrew.workAnim("Drwal", false));
        assertEquals("motyka", BuilderCrew.workAnim("Rolnik", false));
        assertEquals("kowadlo", BuilderCrew.workAnim("Kowal", false));
        assertEquals("odpoczynek", BuilderCrew.workAnim("Rybak", false));
        assertEquals("odpoczynek", BuilderCrew.workAnim("Owczarz", false));
        assertEquals("odpoczynek", BuilderCrew.workAnim("Tragarz — drewno", false));
    }

    @Test
    void walking() {
        assertEquals("noszenie_plecy", BuilderCrew.workAnim("Tragarz — jedzenie", true));
        assertEquals("noszenie_rece", BuilderCrew.workAnim("Dom", true));
        assertEquals("noszenie_rece", BuilderCrew.workAnim("Górnik", true));
        assertEquals("budowa", BuilderCrew.workAnim(null, false));
    }

    @Test
    void onlyKnownAnimations() {
        for (final String role : new String[] {"Dom", "Górnik", "Kamieniarz", "Hutnik", "Ceglarz", "Drwal", "Rolnik", "Kowal", "Rybak",
            "Owczarz", "Tragarz — kamień", "", null}) {
            assertTrue(KNOWN.contains(BuilderCrew.workAnim(role, false)), role);
            assertTrue(KNOWN.contains(BuilderCrew.workAnim(role, true)), role);
        }
    }
}
