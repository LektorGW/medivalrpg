// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;

class ToolsTest {

    private static final long HOUR = 3_600_000L;
    private static final ToolRules.Interlocutor NOBODY = new ToolRules.Interlocutor(null, null, false);
    private static final ToolRules.Interlocutor RULER = new ToolRules.Interlocutor(UUID.randomUUID(), Rank.RULER, false);
    private static final ToolRules.Interlocutor CITIZEN = new ToolRules.Interlocutor(UUID.randomUUID(), Rank.CITIZEN, false);

    private static ToolSpec spec(final String name) {
        return ToolSpecs.find(ToolSpecs.all(true), name).orElseThrow();
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void catalogueSortedAndWarOptional() {
        final List<String> names = ToolSpecs.all(true).stream().map(ToolSpec::name).toList();
        assertEquals(names.stream().sorted().toList(), names, "sorted for a stable cached prefix");
        assertEquals(List.of("odbierz_zlecenie", "odrzuc", "podaruj", "przyjmij_pokoj", "przyjmij_sojusz", "sprawdz_kraj_rozmowcy",
            "sprawdz_reputacje", "wypowiedz_wojne", "zakoncz_rozmowe", "zapamietaj", "zaproponuj_pokoj", "zaproponuj_sojusz",
            "zaproponuj_zlecenie", "zazadaj_trybutu"), names);
        final List<String> noQuests = ToolSpecs.all(true, false).stream().map(ToolSpec::name).toList();
        assertFalse(noQuests.contains(ToolSpecs.OFFER_QUEST) || noQuests.contains(ToolSpecs.CHECK_REPUTATION)
            || noQuests.contains(ToolSpecs.TURN_IN_QUEST), "zlecenia.enabled: false removes the quest tools");
        assertEquals(11, noQuests.size());
        assertFalse(ToolSpecs.all(false).stream().anyMatch(s -> s.name().equals(ToolSpecs.DECLARE_WAR)), "allow-war: false removes the tool");
        assertEquals(ToolSpecs.all(true), ToolSpecs.all(true), "deterministic");
        final String gift = ToolSpecs.find(ToolSpecs.all(true), ToolSpecs.GIFT).orElseThrow().description();
        assertTrue(gift.contains("Nigdy jako zapłata za towar"), "podaruj is not a payment for goods from the chat");
    }

    // ------------------------------------------------------------------ argument validation

    @Test
    void validArguments() throws Exception {
        final ToolArgs.Parsed g = ToolArgs.parse(spec(ToolSpecs.GIFT), Map.of("kwota", 150, "odbiorca", "kraj"));
        assertEquals(150L, g.integer("kwota"));
        assertEquals("kraj", g.string("odbiorca"));
        assertTrue(ToolArgs.parse(spec(ToolSpecs.END), Map.of()).values().isEmpty());
        assertEquals(500L, ToolArgs.parse(spec(ToolSpecs.DEMAND_TRIBUTE), Map.of("kwota", 500.0)).integer("kwota"), "500.0 is an integer");
        assertEquals("urażony", ToolArgs.parse(spec(ToolSpecs.DECLARE_WAR), Map.of("powod", "  urażony ")).string("powod"));
    }

    @Test
    void invalidArguments() {
        final ToolSpec gift = spec(ToolSpecs.GIFT);
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", 10)), "missing field");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", 0, "odbiorca", "gracz")), "below minimum");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", -5, "odbiorca", "gracz")));
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", 1.5, "odbiorca", "gracz")), "fraction");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", "100", "odbiorca", "gracz")), "string number");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", 10, "odbiorca", "admin")), "enum");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(gift, Map.of("kwota", 10, "odbiorca", "gracz", "x", 1)), "unknown field");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(spec(ToolSpecs.DECLARE_WAR), Map.of("powod", " ")), "blank reason");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(spec(ToolSpecs.DECLARE_WAR), Map.of("powod", "x".repeat(161))), "too long");
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(spec(ToolSpecs.REMEMBER), Map.of("notatka", "obraza", "zmiana_nastawienia", 50)));
        assertThrows(ToolArgs.InvalidArgs.class, () -> ToolArgs.parse(spec(ToolSpecs.DEMAND_TRIBUTE), Map.of("kwota", ToolSpecs.MAX_AMOUNT + 1)));
    }

    // ------------------------------------------------------------------ diplomacy / tribute / gifts

    @Test
    void onlyRulersNegotiate() {
        assertTrue(ToolRules.negotiator(NOBODY, Rank.RULER).isPresent());
        assertTrue(ToolRules.negotiator(CITIZEN, Rank.RULER).isPresent());
        assertTrue(ToolRules.negotiator(new ToolRules.Interlocutor(UUID.randomUUID(), Rank.ADVISOR, false), Rank.RULER).isPresent());
        assertTrue(ToolRules.negotiator(new ToolRules.Interlocutor(UUID.randomUUID(), Rank.ADVISOR, false), Rank.ADVISOR).isEmpty());
        assertTrue(ToolRules.negotiator(RULER, Rank.RULER).isEmpty());
        assertTrue(ToolRules.negotiator(new ToolRules.Interlocutor(UUID.randomUUID(), Rank.RULER, true), Rank.RULER).isPresent(), "own country");
    }

    @Test
    void tributeRules() {
        assertTrue(ToolRules.tribute(RULER, Rank.RULER, 500, 5000, false).isEmpty());
        assertTrue(ToolRules.tribute(RULER, Rank.RULER, 5001, 5000, false).isPresent(), "over the configured maximum");
        assertTrue(ToolRules.tribute(RULER, Rank.RULER, 500, 5000, true).isPresent(), "one pending demand at a time");
        assertTrue(ToolRules.tribute(CITIZEN, Rank.RULER, 500, 5000, false).isPresent(), "only the ruler pays");
        assertTrue(ToolRules.tribute(NOBODY, Rank.RULER, 500, 5000, false).isPresent());
    }

    @Test
    void giftAllowanceIsFivePercentPerDayAndPerPlayerCap() {
        assertEquals(500, ToolRules.giftAllowance(10_000, 5, 0, 0, 1000));
        assertEquals(200, ToolRules.giftAllowance(10_000, 5, 300, 0, 1000), "ruler already gave 300 today");
        assertEquals(100, ToolRules.giftAllowance(100_000, 5, 0, 900, 1000), "player cap");
        assertEquals(0, ToolRules.giftAllowance(10_000, 5, 900, 0, 1000));
        assertEquals(0, ToolRules.giftAllowance(-5, 5, 0, 0, 1000));
        assertTrue(ToolRules.gift(100, 500, false, NOBODY).isEmpty(), "a player without a country may get a personal gift");
        assertTrue(ToolRules.gift(100, 500, true, NOBODY).isPresent());
        assertTrue(ToolRules.gift(600, 500, false, RULER).isPresent());
        assertTrue(ToolRules.gift(100, 0, false, RULER).isPresent());
    }

    // ------------------------------------------------------------------ war

    private static ToolRules.WarCheck war(final boolean allow, final ToolRules.Interlocutor who, final boolean atWar, final OptionalInt aggression,
                                          final long lastWarAt, final long now, final String reason) {
        return new ToolRules.WarCheck(allow, who, atWar, aggression, 40, lastWarAt, now, 24, reason);
    }

    @Test
    void warAllowedForAggressiveRulerAgainstCountry() {
        assertEquals(Optional.empty(), ToolRules.war(war(true, CITIZEN, false, OptionalInt.of(70), 0, 100 * HOUR, "obraza")),
            "any member of a country can provoke a war");
    }

    @Test
    void warNeedsInterlocutorCountry() {
        final Optional<String> e = ToolRules.war(war(true, NOBODY, false, OptionalInt.of(90), 0, 100 * HOUR, "obraza"));
        assertTrue(e.isPresent());
        assertTrue(e.get().contains("nie należy"), e.get());
    }

    @Test
    void warRefusedForGentleRuler() {
        final Optional<String> e = ToolRules.war(war(true, RULER, false, OptionalInt.of(39), 0, 100 * HOUR, "obraza"));
        assertTrue(e.isPresent());
        assertTrue(e.get().contains("łagodny") && e.get().contains("Wycofaj"), e.get());
        assertTrue(ToolRules.war(war(true, RULER, false, OptionalInt.of(40), 0, 100 * HOUR, "obraza")).isEmpty(), "40 = minimum, allowed");
        assertTrue(ToolRules.war(war(true, RULER, false, OptionalInt.empty(), 0, 100 * HOUR, "obraza")).isPresent(), "unknown personality");
    }

    @Test
    void warCooldownOncePer24Hours() {
        final long now = 100 * HOUR;
        final Optional<String> e = ToolRules.war(war(true, RULER, false, OptionalInt.of(90), now - 23 * HOUR, now, "obraza"));
        assertTrue(e.isPresent());
        assertTrue(e.get().contains("1 h"), e.get());
        assertTrue(ToolRules.war(war(true, RULER, false, OptionalInt.of(90), now - 24 * HOUR, now, "obraza")).isEmpty());
    }

    @Test
    void warOtherRefusals() {
        assertTrue(ToolRules.war(war(false, RULER, false, OptionalInt.of(90), 0, HOUR, "obraza")).isPresent(), "allow-war: false");
        assertTrue(ToolRules.war(war(true, RULER, true, OptionalInt.of(90), 0, HOUR, "obraza")).isPresent(), "already at war");
        assertTrue(ToolRules.war(war(true, RULER, false, OptionalInt.of(90), 0, HOUR, "  ")).isPresent(), "no reason");
        assertTrue(ToolRules.war(war(true, new ToolRules.Interlocutor(UUID.randomUUID(), Rank.RULER, true), false, OptionalInt.of(90), 0, HOUR, "x"))
            .isPresent(), "own country");
    }

    @Test
    void neverWarStanceForbidsAWarTalkedIntoInAnAudience() {
        assertTrue(ToolRules.warStance(pl.medivalrpg.armia.api.NpcStance.NEVER_WAR).isPresent());
        for (final pl.medivalrpg.armia.api.NpcStance s : pl.medivalrpg.armia.api.NpcStance.values()) {
            if (s != pl.medivalrpg.armia.api.NpcStance.NEVER_WAR) {
                assertFalse(ToolRules.warStance(s).isPresent(), s.name());
            }
        }
    }
}
