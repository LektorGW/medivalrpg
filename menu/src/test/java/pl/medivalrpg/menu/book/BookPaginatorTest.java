// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.config.MenusLoader;
import pl.medivalrpg.menu.model.BookDef;

class BookPaginatorTest {

    private static final BookPaginator.Layout LAYOUT = BookPaginator.Layout.DEFAULT;
    private static final Function<String, Component> MM = s -> MiniMessage.miniMessage().deserialize(s);

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Every line of every page fits the width (measured per character with its own style) and the line limit. */
    private static void assertFits(final List<Component> pages, final BookPaginator.Layout layout) {
        for (final Component page : pages) {
            final List<List<BookPaginator.Glyph>> lines = BookPaginator.wrap(page, Integer.MAX_VALUE);
            assertTrue(lines.size() <= layout.linesPerPage(), "too many lines: " + lines.size() + " in\n" + plain(page));
            for (final List<BookPaginator.Glyph> line : lines) {
                final int width = line.stream().mapToInt(BookPaginator.Glyph::width).sum();
                assertTrue(width <= layout.lineWidth(), "line too wide (" + width + " px): " + line);
            }
        }
    }

    private static String words(final String s) {
        return String.join(" ", s.trim().split("\\s+"));
    }

    @Test
    void shortTextIsOnePage() {
        final List<Component> pages = BookPaginator.paginate(List.of(Component.text("Witaj na serwerze!")), LAYOUT);
        assertEquals(1, pages.size());
        assertEquals("Witaj na serwerze!", plain(pages.getFirst()));
    }

    @Test
    void emptyInputGivesOneEmptyPage() {
        final List<Component> pages = BookPaginator.paginate(List.of(), LAYOUT);
        assertEquals(1, pages.size());
        assertEquals("", plain(pages.getFirst()));
    }

    @Test
    void wrapsByPixelWidth() {
        // "aaaa..." : 'a' = 6 px → 18 per 112 px line (18 × 6 = 108)
        final String text = "a".repeat(40);
        final List<List<BookPaginator.Glyph>> lines = BookPaginator.wrap(Component.text(text), 112);
        assertEquals(3, lines.size(), "a single long word is cut by characters");
        assertEquals(18, lines.get(0).size());
        assertEquals(18, lines.get(1).size());
        assertEquals(4, lines.get(2).size());
        // narrow letters fit more: 'i' = 2 px → 56 per line
        assertEquals(1, BookPaginator.wrap(Component.text("i".repeat(56)), 112).size());
        assertEquals(2, BookPaginator.wrap(Component.text("i".repeat(57)), 112).size());
    }

    @Test
    void wrapsAtSpaces() {
        final List<List<BookPaginator.Glyph>> lines = BookPaginator.wrap(
            Component.text("Kraj zakładasz komendą /kraj zaloz nazwa"), 112);
        final List<String> texts = new ArrayList<>();
        for (final List<BookPaginator.Glyph> line : lines) {
            final StringBuilder sb = new StringBuilder();
            line.forEach(g -> sb.appendCodePoint(g.codepoint()));
            texts.add(sb.toString());
        }
        assertTrue(texts.size() >= 2);
        for (final String t : texts) {
            assertFalse(t.startsWith(" ") || t.endsWith(" "), "no leading/trailing spaces: '" + t + "'");
        }
        assertEquals("Kraj zakładasz komendą /kraj zaloz nazwa", String.join(" ", texts));
    }

    @Test
    void boldIsWider() {
        final Component normal = Component.text("a".repeat(18));
        final Component bold = Component.text("a".repeat(18), Style.style(TextDecoration.BOLD));
        assertEquals(1, BookPaginator.lineCount(normal, 112));
        assertEquals(2, BookPaginator.lineCount(bold, 112), "18 × 7 px = 126 px > 112 px");
    }

    @Test
    void longTextSplitsIntoFullPagesWithoutLosingWords() {
        final List<Component> paragraphs = new ArrayList<>();
        final StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            final String p = "Akapit numer " + i + " opisuje zasady gry na serwerze i jest dość długi, żeby się zawinąć.";
            paragraphs.add(MM.apply("<dark_aqua>" + p + "</dark_aqua>"));
            expected.append(p).append(' ');
        }
        final List<Component> pages = BookPaginator.paginate(paragraphs, LAYOUT);
        assertTrue(pages.size() > 5, "pages: " + pages.size());
        assertFits(pages, LAYOUT);
        final StringBuilder actual = new StringBuilder();
        pages.forEach(p -> actual.append(plain(p)).append(' '));
        assertEquals(words(expected.toString()), words(actual.toString()));
        // all pages but the last are full
        for (int i = 0; i < pages.size() - 1; i++) {
            assertEquals(LAYOUT.linesPerPage(), BookPaginator.wrap(pages.get(i), Integer.MAX_VALUE).size());
        }
    }

    @Test
    void smallLayoutIsRespected() {
        final BookPaginator.Layout small = new BookPaginator.Layout(60, 4);
        final List<Component> pages = BookPaginator.paginate(List.of(Component.text(
            "Jeden dwa trzy cztery pięć sześć siedem osiem dziewięć dziesięć jedenaście dwanaście trzynaście")), small);
        assertTrue(pages.size() >= 2);
        assertFits(pages, small);
    }

    @Test
    void stylesAndClicksSurviveTheSplit() {
        final Component p = Component.text("Kliknij tutaj aby przejść dalej do kolejnej strony poradnika", NamedTextColor.BLUE)
            .decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.changePage(3));
        final List<Component> pages = BookPaginator.paginate(List.of(p), LAYOUT);
        final List<TextComponent> leaves = new ArrayList<>();
        collect(pages.getFirst(), leaves);
        assertFalse(leaves.isEmpty());
        for (final TextComponent leaf : leaves) {
            if (leaf.content().isBlank() || leaf.content().equals("\n")) {
                continue;
            }
            assertEquals(NamedTextColor.BLUE, leaf.color());
            assertEquals(TextDecoration.State.TRUE, leaf.decoration(TextDecoration.BOLD));
            assertEquals(ClickEvent.changePage(3), leaf.clickEvent());
        }
    }

    private static void collect(final Component c, final List<TextComponent> out) {
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t);
        }
        c.children().forEach(child -> collect(child, out));
    }

    @Test
    void emptyParagraphIsBlankLineButNotAtTopOfContinuedPage() {
        final BookPaginator.Layout tiny = new BookPaginator.Layout(112, 2);
        final List<Component> pages = BookPaginator.paginate(List.of(
            Component.text("A"), Component.text("B"), Component.empty(), Component.text("C")), tiny);
        assertEquals(2, pages.size());
        assertEquals("A\nB", plain(pages.get(0)));
        assertEquals("C", plain(pages.get(1)), "blank line dropped at the top of page 2");
    }

    @Test
    void explicitNewlineBreaksTheLine() {
        assertEquals(2, BookPaginator.lineCount(Component.text("a\nb"), 112));
    }

    // ------------------------------------------------------------------ whole book

    private static BookDef book(final int sections, final int paragraphsPerSection) {
        final List<BookDef.SectionDef> list = new ArrayList<>();
        for (int s = 0; s < sections; s++) {
            final List<String> text = new ArrayList<>();
            for (int i = 0; i < paragraphsPerSection; i++) {
                text.add("Sekcja " + s + ", akapit " + i + ": trochę tekstu, który trzeba zmieścić na stronie.");
            }
            list.add(new BookDef.SectionDef("s" + s, "<dark_green>Rozdział " + s, s == 2 ? List.of("Armia") : List.of(),
                null, text));
        }
        return new BookDef("test", "Test", "Autor", List.of("<gold>Wstęp</gold>"), "<bold>Spis treści</bold>", list);
    }

    @Test
    void tocLinksPointToSectionStarts() {
        final BookBuilder.Result r = BookBuilder.build(book(3, 12), s -> true, MM, LAYOUT);
        assertFalse(r.truncated());
        assertFits(r.pages(), LAYOUT);
        assertEquals(3, r.sectionStarts().size());
        assertEquals(2, r.sectionStarts().getFirst(), "TOC fits on page 1");
        for (int i = 0; i < 3; i++) {
            final int start = r.sectionStarts().get(i);
            assertTrue(plain(r.pages().get(start - 1)).startsWith("Rozdział " + i), "section " + i + " on page " + start);
        }
        assertTrue(r.sectionStarts().get(1) > r.sectionStarts().get(0) + 1, "12 paragraphs need more than one page");

        final List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(r.pages().getFirst(), clicks);
        assertTrue(clicks.contains(ClickEvent.changePage(r.sectionStarts().get(0))));
        assertTrue(clicks.contains(ClickEvent.changePage(r.sectionStarts().get(1))));
        assertTrue(clicks.contains(ClickEvent.changePage(r.sectionStarts().get(2))));
        final String toc = plain(r.pages().getFirst());
        assertTrue(toc.startsWith("Wstęp"));
        assertTrue(toc.contains("Spis treści"));
        assertTrue(toc.contains("» Rozdział 2"));
    }

    private static void collectClicks(final Component c, final List<ClickEvent> out) {
        if (c.clickEvent() != null) {
            out.add(c.clickEvent());
        }
        c.children().forEach(child -> collectClicks(child, out));
    }

    @Test
    void hiddenSectionsAreLeftOut() {
        final BookBuilder.Result r = BookBuilder.build(book(3, 2), s -> s.requiresPlugins().isEmpty(), MM, LAYOUT);
        assertEquals(2, r.sectionStarts().size());
        final String all = String.join("\n", r.pages().stream().map(BookPaginatorTest::plain).toList());
        assertFalse(all.contains("Rozdział 2"));
        assertTrue(all.contains("Rozdział 1"));
    }

    @Test
    void longTocSpansPages() {
        final BookBuilder.Result r = BookBuilder.build(book(30, 1), s -> true, MM, LAYOUT);
        assertTrue(r.sectionStarts().getFirst() > 2, "30 entries do not fit on one page");
        assertFits(r.pages(), LAYOUT);
        for (int i = 0; i < 30; i++) {
            assertTrue(plain(r.pages().get(r.sectionStarts().get(i) - 1)).startsWith("Rozdział " + i));
        }
    }

    @Test
    void pageBreakStartsNewPage() {
        final BookDef def = new BookDef("b", "B", "A", List.of(), "Spis", List.of(new BookDef.SectionDef("s", "Tytuł",
            List.of(), null, List.of("Raz", BookBuilder.PAGE_BREAK, "Dwa"))));
        final BookBuilder.Result r = BookBuilder.build(def, s -> true, MM, LAYOUT);
        assertEquals(3, r.pages().size());
        assertEquals("Tytuł\n\nRaz", plain(r.pages().get(1)));
        assertEquals("Dwa", plain(r.pages().get(2)));
    }

    @Test
    void bookIsCappedAt100Pages() {
        final BookBuilder.Result r = BookBuilder.build(book(10, 200), s -> true, MM, LAYOUT);
        assertTrue(r.truncated());
        assertEquals(BookBuilder.MAX_PAGES, r.pages().size());
    }

    @Test
    void bundledGuideBookFits() {
        final BookDef def = MenusLoader.load(YamlConfiguration.loadConfiguration(new InputStreamReader(
            BookPaginatorTest.class.getResourceAsStream("/menus.yml"), StandardCharsets.UTF_8))).book("poradnik");
        // all plugins "enabled"; placeholders resolve to sample values of realistic length
        final Function<String, Component> parse = s -> MiniMessage.miniMessage().deserialize(s
            .replace("<player>", "Wojownik_Zbyszek")
            .replace("<map_link>", "http://mc.example.org:8100")
            .replaceAll("<cfg:[^>]*:([^:>]*)>", "$1"));
        final BookBuilder.Result r = BookBuilder.build(def, s -> true, parse, LAYOUT);
        assertFalse(r.truncated());
        assertFits(r.pages(), LAYOUT);
        assertEquals(def.sections().size(), r.sectionStarts().size());
        // the real limit: a written book holds at most 100 pages
        assertTrue(r.pages().size() <= 100, "pages: " + r.pages().size());
    }
}
