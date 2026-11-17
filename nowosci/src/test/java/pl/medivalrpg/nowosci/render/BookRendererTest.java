// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.render.BookLayout.Block;

class BookRendererTest {

    private static final int LPP = 13;
    private static final int CPL = 18;

    // ------------------------------------------------------------------ LineWrap

    @Test
    void lineWrapCountsConservatively() {
        assertEquals(1, LineWrap.lines("", CPL, false));
        assertEquals(1, LineWrap.lines("a".repeat(18), CPL, false));
        assertEquals(2, LineWrap.lines("a".repeat(19), CPL, false));
        assertEquals(2, LineWrap.lines("aaaaaaaaa aaaaaaaaa", CPL, false)); // 9 + space + 9 = 19 > 18
        assertEquals(1, LineWrap.lines("aaaaaaaa aaaaaaaaa", CPL, false));  // exactly 18
        assertEquals(3, LineWrap.lines("a".repeat(40), CPL, false));        // long word is cut
        assertEquals(3, LineWrap.lines("a\n\nb", CPL, false));
        assertEquals(1, LineWrap.lines("✦".repeat(9), CPL, false));         // symbols count double
        assertEquals(2, LineWrap.lines("✦".repeat(10), CPL, false));
        assertEquals(1, LineWrap.lines("ą".repeat(18), CPL, false));        // Polish letters are normal
        assertEquals(1, LineWrap.lines("a".repeat(15), CPL, true));         // bold is wider
        assertEquals(2, LineWrap.lines("a".repeat(16), CPL, true));
    }

    @Test
    void splitToFitKeepsEveryWord() {
        final String text = "słowo ".repeat(120).strip();
        final List<String> chunks = LineWrap.splitToFit(text, CPL, 10);
        assertTrue(chunks.size() > 1);
        for (final String c : chunks) {
            assertTrue(LineWrap.lines(c, CPL, false) <= 10, c);
        }
        assertEquals(text, String.join(" ", chunks));
    }

    // ------------------------------------------------------------------ book

    private static BookRenderer renderer(final Map<String, Object> overrides) {
        return new BookRenderer(Fixtures.format(overrides));
    }

    /** Every page fits: by the layout's own counts and by re-measuring the page text. */
    private static void assertPagesFit(final BookRenderer.Rendered r) {
        assertEquals(r.layout().size(), r.book().pages().size());
        for (int i = 0; i < r.layout().size(); i++) {
            final List<Block> page = r.layout().get(i);
            assertTrue(BookLayout.lines(page) <= LPP, "page " + (i + 1) + " has " + BookLayout.lines(page) + " lines");
            final String plain = EntryFormat.plain(r.book().pages().get(i));
            assertTrue(LineWrap.lines(plain, CPL, false) <= LPP, "page " + (i + 1) + " text: " + plain);
        }
    }

    private static List<Integer> changePageTargets(final Component c) {
        final List<Integer> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(final Component c, final List<Integer> out) {
        final ClickEvent<?> click = c.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Int p) {
            out.add(p.integer());
        }
        c.children().forEach(child -> collect(child, out));
    }

    @Test
    void bundledChangelogBookHasTocAndEntriesNewestFirst() {
        final Changelog changelog = Fixtures.bundledChangelog();
        final BookRenderer.Rendered r = renderer(Map.of()).render(changelog, 0);
        assertPagesFit(r);
        assertEquals(1, r.tocPages());
        assertEquals(5, r.shownEntries());
        final String cover = EntryFormat.plain(r.book().pages().getFirst());
        assertTrue(cover.contains("Nowości") && cover.contains("MEDIVALRPG") && cover.contains("stan na 25.10.2026"), cover);
        assertTrue(cover.contains("5 nowych wpisów") && cover.contains("✦ ✧ ✦"), cover);
        assertEquals(List.of(2), changePageTargets(r.book().pages().getFirst())); // cover → table of contents
        final String toc = EntryFormat.plain(r.book().pages().get(1));
        assertTrue(toc.contains("Spis treści"), toc);
        assertEquals(r.entryStartPages(), changePageTargets(r.book().pages().get(1)));
        assertEquals(3, r.entryStartPages().getFirst());
        for (int i = 0; i < changelog.size(); i++) {
            final Entry e = changelog.entries().get(i);
            final String page = EntryFormat.plain(r.book().pages().get(r.entryStartPages().get(i) - 1));
            assertTrue(page.startsWith(e.title()) || page.contains(e.title()), "entry " + e.id() + " on its page: " + page);
        }
        // newest first
        assertTrue(EntryFormat.plain(r.book().pages().get(2)).contains("Porządki na serwerze"));
    }

    @Test
    void unreadEntriesAreMarkedOnCoverTocAndTheirPages() {
        final Changelog changelog = Fixtures.bundledChangelog();
        final BookRenderer.Rendered r = renderer(Map.of()).render(changelog, 3);
        assertPagesFit(r);
        assertTrue(EntryFormat.plain(r.book().pages().getFirst()).contains("2 nowe wpisy"));
        final String toc = EntryFormat.plain(r.book().pages().get(1));
        assertEquals(1 + 2, toc.split("★", -1).length - 1, toc); // legend + entries 5 and 4
        assertTrue(EntryFormat.plain(r.book().pages().get(r.entryStartPages().get(0) - 1)).contains("★ nowe"));
        assertTrue(EntryFormat.plain(r.book().pages().get(r.entryStartPages().get(1) - 1)).contains("★ nowe"));
        assertTrue(!EntryFormat.plain(r.book().pages().get(r.entryStartPages().get(2) - 1)).contains("★ nowe"));
        final BookRenderer.Rendered allRead = renderer(Map.of()).render(changelog, 5);
        assertTrue(EntryFormat.plain(allRead.book().pages().getFirst()).contains("wszystko przeczytane"));

        assertTrue(!EntryFormat.plain(allRead.book().pages().getFirst()).contains("Przeczytane"));
    }

    @Test
    void coverHasReadButtonOnlyWithUnreadEntries() {
        final Changelog changelog = Fixtures.bundledChangelog();
        final Component cover = renderer(Map.of()).render(changelog, 3).book().pages().getFirst();
        assertTrue(EntryFormat.plain(cover).contains("✔ Przeczytane"));
        assertTrue(runCommands(cover).contains("/nowosci przeczytane"), runCommands(cover).toString());
        final Component read = renderer(Map.of()).render(changelog, 5).book().pages().getFirst();
        assertTrue(runCommands(read).isEmpty());
    }

    private static List<String> runCommands(final Component c) {
        final List<String> out = new ArrayList<>();
        final ClickEvent<?> click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND && click.payload() instanceof ClickEvent.Payload.Text t) {
            out.add(t.value());
        }
        c.children().forEach(child -> out.addAll(runCommands(child)));
        return out;
    }

    @Test
    void longEntriesContinueOnNextPagesAndOversizedPointsAreSplit() {
        final List<String> points = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            points.add("Punkt numer " + i + " z całkiem długim opisem, który na pewno zajmie kilka linii w książce.");
        }
        points.add("Olbrzymi punkt " + "bardzo długi tekst ".repeat(60));
        final Changelog c = new Changelog(List.of(
            new Entry(1, LocalDate.of(2026, 10, 25), "Bardzo długi wpis z długim tytułem", "nowe", points),
            new Entry(2, LocalDate.of(2026, 10, 26), "Krótki", "poprawka", List.of("Jeden."))), 3);
        final BookRenderer.Rendered r = renderer(Map.of()).render(c, 0);
        assertPagesFit(r);
        assertEquals(List.of(3, 4), r.entryStartPages()); // cover, toc, short newest entry takes one page
        assertTrue(r.book().pages().size() > 7);
        final StringBuilder all = new StringBuilder();
        for (int i = 3; i < r.book().pages().size(); i++) {
            final String page = EntryFormat.plain(r.book().pages().get(i));
            if (i > 3) {
                assertTrue(page.startsWith("(cd.)"), page);
            }
            all.append(page).append(' ');
        }
        assertTrue(all.toString().replace('\n', ' ').contains("Punkt numer 11"));
        assertEquals(60, all.toString().split("bardzo długi tekst", -1).length - 1);
    }

    @Test
    void manyEntriesGiveMultiPageTocWithCorrectTargets() {
        final List<Entry> entries = new ArrayList<>();
        for (int id = 1; id <= 40; id++) {
            entries.add(new Entry(id, LocalDate.of(2026, 1, 1).plusDays(id), "Aktualizacja numer " + id, "zmiana",
                List.of("Pierwszy punkt.", "Drugi punkt.")));
        }
        final BookRenderer.Rendered r = renderer(Map.of()).render(new Changelog(entries, 41), 0);
        assertPagesFit(r);
        assertTrue(r.tocPages() > 1);
        final List<Integer> targets = new ArrayList<>();
        for (int i = 1; i <= r.tocPages(); i++) {
            targets.addAll(changePageTargets(r.book().pages().get(i)));
        }
        assertEquals(r.entryStartPages(), targets);
        assertEquals(r.tocPages() + 2, r.entryStartPages().getFirst());
        final String last = EntryFormat.plain(r.book().pages().get(r.entryStartPages().getLast() - 1));
        assertTrue(last.contains("Aktualizacja numer 1"), last);
    }

    @Test
    void pageLimitDropsOldestEntries() {
        final List<Entry> entries = new ArrayList<>();
        for (int id = 1; id <= 30; id++) {
            entries.add(new Entry(id, null, "Wpis " + id, "nowe", List.of("Punkt.")));
        }
        final BookRenderer.Rendered r = renderer(Map.of("book.max-pages", 10)).render(new Changelog(entries, 31), 0);
        assertTrue(r.book().pages().size() <= 10);
        assertTrue(r.shownEntries() < 30);
        assertEquals(r.shownEntries(), r.entryStartPages().size());
        assertTrue(r.entryStartPages().getLast() <= 10);
        assertTrue(EntryFormat.plain(r.book().pages().get(r.tocPages() + 1)).contains("Wpis 30")); // newest kept
    }

    @Test
    void emptyChangelogGivesOnePage() {
        final BookRenderer.Rendered r = renderer(Map.of()).render(Changelog.empty(), 0);
        assertEquals(1, r.book().pages().size());
        assertTrue(EntryFormat.plain(r.book().pages().getFirst()).contains("Na razie nie ma"));
    }

    @Test
    void shortenCutsAtWordBoundary() {
        assertEquals("krótki", EntryFormat.shorten("krótki", 10));
        assertEquals("Wpisz /glos i kliknij…", EntryFormat.shorten("Wpisz /glos i kliknij link — rozmawiasz", 24));
    }
}
