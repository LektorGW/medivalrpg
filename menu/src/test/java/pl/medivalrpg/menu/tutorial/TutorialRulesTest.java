// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TutorialRulesTest {

    private static final List<String> STEPS = List.of("pieniadze", "kraj", "ziemia", "mieszkania", "jedzenie", "budowa",
        "produkcja", "wlasne", "armia", "pomoc");
    private static final long GOAL = 100;

    private static WorldState noCountry(final long money) {
        return new WorldState(money, false, 0, 0, Set.of(), false, 0);
    }

    private static WorldState country(final int chunks, final int housing, final Set<String> buildings,
                                      final boolean construction, final int soldiers) {
        return new WorldState(0L, true, chunks, housing, buildings, construction, soldiers);
    }

    @Test
    void newPlayerHasNothingDone() {
        final TutorialStore.Entry entry = new TutorialStore.Entry();
        final TutorialProgress p = TutorialProgress.evaluate(STEPS, entry, noCountry(0), GOAL);
        assertEquals(0, p.done());
        assertEquals(10, p.total());
        assertEquals("pieniadze", p.next());
        assertTrue(p.remind());
        assertFalse(p.finished());
        assertFalse(p.changed());
        assertTrue(entry.done.isEmpty());
    }

    @Test
    void moneyStepNeedsTheGoalAndStaysDoneAfterSpending() {
        final TutorialStore.Entry entry = new TutorialStore.Entry();
        assertFalse(TutorialProgress.evaluate(STEPS, entry, noCountry(99), GOAL).steps().get("pieniadze"));
        final TutorialProgress rich = TutorialProgress.evaluate(STEPS, entry, noCountry(100), GOAL);
        assertTrue(rich.steps().get("pieniadze"));
        assertTrue(rich.changed());
        assertEquals("kraj", rich.next());
        // The player founds a country and has no coin left: the step is sticky.
        final TutorialProgress poor = TutorialProgress.evaluate(STEPS, entry, noCountry(0), GOAL);
        assertTrue(poor.steps().get("pieniadze"));
        assertFalse(poor.changed());
    }

    @Test
    void countryStepsFollowTheRealStateOfTheCountry() {
        // Capital only: country yes, land no.
        WorldState s = country(1, 0, Set.of(), false, 0);
        assertEquals(Boolean.TRUE, TutorialRules.done("kraj", s, GOAL));
        assertEquals(Boolean.TRUE, TutorialRules.done("pieniadze", s, GOAL), "a member of a country is past the money step");
        assertEquals(Boolean.FALSE, TutorialRules.done("ziemia", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("mieszkania", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("jedzenie", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("budowa", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("produkcja", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("armia", s, GOAL));
        // A second chunk, a hut being built.
        s = country(2, 0, Set.of(), true, 0);
        assertEquals(Boolean.TRUE, TutorialRules.done("ziemia", s, GOAL));
        assertEquals(Boolean.TRUE, TutorialRules.done("budowa", s, GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("mieszkania", s, GOAL));
        // Beds, granary, sawmill, barracks.
        s = country(5, 4, Set.of("spichlerz", "tartak", "koszary"), true, 0);
        for (final String step : List.of("mieszkania", "jedzenie", "produkcja", "armia")) {
            assertEquals(Boolean.TRUE, TutorialRules.done(step, s, GOAL), step);
        }
        // Soldiers without barracks (mercenaries) also count; a quarry or a mine is production too.
        assertEquals(Boolean.TRUE, TutorialRules.done("armia", country(2, 0, Set.of(), false, 3), GOAL));
        assertEquals(Boolean.TRUE, TutorialRules.done("produkcja", country(2, 0, Set.of("kamieniolom"), true, 0), GOAL));
        assertEquals(Boolean.TRUE, TutorialRules.done("produkcja", country(2, 0, Set.of("kopalnia"), true, 0), GOAL));
        assertEquals(Boolean.FALSE, TutorialRules.done("produkcja", country(2, 0, Set.of("spichlerz"), true, 0), GOAL));
    }

    @Test
    void withoutCountryEveryLaterStepIsOpen() {
        final WorldState s = noCountry(10_000);
        for (final String step : List.of("kraj", "ziemia", "mieszkania", "jedzenie", "budowa", "produkcja", "armia")) {
            assertEquals(Boolean.FALSE, TutorialRules.done(step, s, GOAL), step);
        }
    }

    @Test
    void informationalStepsAndUnreadableStateFallBackToTheClick() {
        assertNull(TutorialRules.done("wlasne", noCountry(0), GOAL));
        assertNull(TutorialRules.done("pomoc", noCountry(0), GOAL));
        assertNull(TutorialRules.done("cos-nowego", noCountry(0), GOAL));
        // No Kraje / no Skarbiec / Ludnosc API not readable: unknown, never "done" by itself.
        for (final String step : TutorialRules.STATE_STEPS) {
            assertNull(TutorialRules.done(step, WorldState.UNKNOWN, GOAL), step);
        }
        final WorldState noLudnosc = new WorldState(0L, true, 3, null, null, null, null);
        for (final String step : List.of("mieszkania", "jedzenie", "budowa", "produkcja", "armia")) {
            assertNull(TutorialRules.done(step, noLudnosc, GOAL), step);
        }
        final TutorialStore.Entry entry = new TutorialStore.Entry();
        assertFalse(TutorialProgress.evaluate(STEPS, entry, noCountry(0), GOAL).steps().get("wlasne"));
        entry.seen.add("wlasne");
        entry.seen.add("kraj"); // a click never completes a step the game can check
        final TutorialProgress p = TutorialProgress.evaluate(STEPS, entry, noCountry(0), GOAL);
        assertTrue(p.steps().get("wlasne"));
        assertFalse(p.steps().get("kraj"));
        entry.seen.add("mieszkania");
        assertTrue(TutorialProgress.evaluate(STEPS, entry, noLudnosc, GOAL).steps().get("mieszkania"));
    }

    @Test
    void reminderStopsWhenOnlyInformationalStepsAreLeft() {
        final TutorialStore.Entry entry = new TutorialStore.Entry();
        final WorldState established = country(40, 30, Set.of("spichlerz", "tartak", "kopalnia", "koszary"), true, 12);
        final TutorialProgress p = TutorialProgress.evaluate(STEPS, entry, established, GOAL);
        assertEquals(8, p.done());
        assertEquals("wlasne", p.next());
        assertFalse(p.remind(), "an established player gets no invitation and no reminder");
        assertFalse(p.finished());
        entry.seen.addAll(List.of("wlasne", "pomoc"));
        final TutorialProgress all = TutorialProgress.evaluate(STEPS, entry, established, GOAL);
        assertTrue(all.finished());
        assertNull(all.next());
        assertEquals(STEPS, List.copyOf(all.steps().keySet()), "menu order is kept");
    }

    @Test
    void storeRoundTripSkipsGarbage() {
        final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        final TutorialStore.Entry first = new TutorialStore.Entry();
        first.invited = true;
        first.dismissed = true;
        first.done.addAll(List.of("pieniadze", "kraj"));
        first.seen.add("pomoc");
        final TutorialStore.Entry second = new TutorialStore.Entry();
        second.finished = true;
        final String json = TutorialStore.serialize(Map.of(a, first, b, second));
        assertTrue(json.contains("Maciej Kaczmarczyk"));
        final Map<UUID, TutorialStore.Entry> back = TutorialStore.parse(json);
        assertEquals(Set.of(a, b), back.keySet());
        assertTrue(back.get(a).invited && back.get(a).dismissed && !back.get(a).finished);
        assertEquals(List.of("pieniadze", "kraj"), List.copyOf(back.get(a).done));
        assertEquals(Set.of("pomoc"), back.get(a).seen);
        assertTrue(back.get(b).finished && !back.get(b).invited && back.get(b).done.isEmpty());
        assertEquals(json, TutorialStore.serialize(back), "deterministic output");
        assertTrue(TutorialStore.parse("{}").isEmpty());
        assertTrue(TutorialStore.parse("[]").isEmpty());
        assertEquals(1, TutorialStore.parse("{\"players\":{\"nie-uuid\":{},\"" + a + "\":{\"done\":\"x\",\"seen\":[1,\"y\"]},\"" + b
            + "\":5}}").size());
    }
}
