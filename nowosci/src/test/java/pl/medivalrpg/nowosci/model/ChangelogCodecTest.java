// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.render.EntryFormat;

class ChangelogCodecTest {

    @Test
    void bundledChangelogParsesWithoutWarnings() {
        final ChangelogCodec.Result result = ChangelogCodec.parse(Fixtures.resource("changelog.yml"));
        assertTrue(result.ok());
        assertEquals(List.of(), result.warnings());
        final Changelog c = result.changelog();
        assertEquals(5, c.size());
        assertEquals(List.of(5, 4, 3, 2, 1), c.entries().stream().map(Entry::id).toList());
        assertEquals(6, c.nextId());
        assertEquals("Czat głosowy w przeglądarce", c.byId(1).orElseThrow().title());
        assertEquals("Kraje", c.byId(2).orElseThrow().title());
        assertEquals("Mapa świata", c.byId(3).orElseThrow().title());
        assertEquals("Interakcje", c.byId(4).orElseThrow().title());
        final Entry newest = c.newest().orElseThrow();
        assertEquals("Porządki na serwerze", newest.title());
        assertEquals("zmiana", newest.category());
        for (final Entry e : c.entries()) {
            assertEquals(LocalDate.of(2026, 10, 25), e.date());
            assertFalse(e.points().isEmpty(), "entry " + e.id() + " has points");
        }
    }

    @Test
    void bundledPointsUseOnlyKnownTagsAndCategories() {
        final EntryFormat format = Fixtures.format();
        final NowosciConfig cfg = format.config();
        for (final Entry e : Fixtures.bundledChangelog().entries()) {
            assertTrue(cfg.categories.containsKey(e.category()), "category " + e.category());
            for (final String point : e.points()) {
                final String plain = EntryFormat.plain(format.point(point, EntryFormat.Medium.BOOK));
                assertFalse(plain.contains("<cmd>") || plain.contains("</cmd>") || plain.contains("<hl>"), plain);
            }
        }
        final String voice = String.join(" ", Fixtures.bundledChangelog().byId(1).orElseThrow().points());
        assertTrue(voice.contains("/glos") && voice.contains("szept") && voice.contains("krzyk") && voice.contains("Kucnij"));
    }

    @Test
    void lenientParsingSkipsBrokenEntriesAndExplainsWhy() {
        final String yaml = """
            entries:
              - id: 1
                date: 2026-10-20
                title: "Bez cudzysłowu w dacie"
                category: NOWE
                points: "jeden punkt jako tekst"
              - title: "Brak id"
                category: nowe
              - id: 1
                title: "Powtórzone id"
                category: nowe
              - id: 2
                category: poprawka
              - id: 3
                date: "25.10.2026"
                title: "Zła data"
                points: []
            """;
        final ChangelogCodec.Result result = ChangelogCodec.parse(yaml);
        assertTrue(result.ok());
        final Changelog c = result.changelog();
        assertEquals(List.of(3, 1), c.entries().stream().map(Entry::id).toList());
        final Entry first = c.byId(1).orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 20), first.date());
        assertEquals("nowe", first.category());
        assertEquals(List.of("jeden punkt jako tekst"), first.points());
        final Entry third = c.byId(3).orElseThrow();
        assertEquals(null, third.date());
        assertEquals("nowe", third.category()); // missing category → nowe
        assertEquals(5, result.warnings().size(), result.warnings().toString()); // no id, duplicate, no title, date, category
        assertEquals(4, c.nextId());
    }

    @Test
    void brokenFileIsNotOk() {
        assertFalse(ChangelogCodec.parse("entries: [ {id: 1, title: \"x\"").ok());
        assertFalse(ChangelogCodec.parse("entries:\n  id: 1\n").ok());
        assertTrue(ChangelogCodec.parse("").ok());
        assertTrue(ChangelogCodec.parse("").changelog().isEmpty());
    }

    @Test
    void writeThenParseGivesTheSameChangelog() {
        final Changelog original = Fixtures.bundledChangelog()
            .with(new Entry(6, LocalDate.of(2026, 11, 1), "Tytuł: z dwukropkiem i \"cudzysłowem\"", "poprawka",
                List.of("Punkt z <cmd>/komenda</cmd> i ąęółśżźćń", "- zaczyna się od myślnika")));
        final ChangelogCodec.Result again = ChangelogCodec.parse(ChangelogCodec.write(original));
        assertTrue(again.ok());
        assertEquals(List.of(), again.warnings());
        assertEquals(original.entries(), again.changelog().entries());
        assertEquals(original.nextId(), again.changelog().nextId());
    }

    @Test
    void idsAreNeverReusedAfterDelete() {
        final Changelog c = Fixtures.bundledChangelog();
        final Changelog withoutNewest = c.without(5);
        assertEquals(4, withoutNewest.maxId());
        assertEquals(6, withoutNewest.nextId());
        final Changelog reparsed = ChangelogCodec.parse(ChangelogCodec.write(withoutNewest)).changelog();
        assertEquals(6, reparsed.nextId());
        // next-id lower than the highest id is corrected
        assertEquals(10, ChangelogCodec.parse("next-id: 2\nentries:\n  - {id: 9, title: x, category: nowe}\n")
            .changelog().nextId());
    }

    @Test
    void newerThanReturnsNewestFirst() {
        final Changelog c = Fixtures.bundledChangelog();
        assertEquals(List.of(5, 4), c.newerThan(3).stream().map(Entry::id).toList());
        assertEquals(List.of(), c.newerThan(5));
        assertEquals(5, c.newerThan(0).size());
    }
}
