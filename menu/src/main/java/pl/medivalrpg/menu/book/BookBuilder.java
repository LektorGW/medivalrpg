// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.book;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import pl.medivalrpg.menu.model.BookDef;

/**
 * Builds the pages of a {@link BookDef}: intro + clickable table of contents ({@link ClickEvent#changePage(int)})
 * first, then every visible section starting on its own page. A section header is clickable and returns to page 1.
 * A paragraph {@code ---} forces a page break. Pure – the caller supplies MiniMessage parsing and visibility.
 */
public final class BookBuilder {

    /** Client limit of a written book. */
    public static final int MAX_PAGES = 100;
    public static final String PAGE_BREAK = "---";

    /**
     * A clickable entry that runs a command (chapter of a chapter index, "back to all chapters").
     *
     * @param name    MiniMessage text
     * @param hover   plain hover text ({@code null} = none)
     * @param command command run on click, e.g. {@code /menu poradnik kolej}
     */
    public record Link(String name, String hover, String command) {
    }

    /** Built pages plus the first page (1-based) of every visible section. */
    public record Result(List<Component> pages, List<Integer> sectionStarts, boolean truncated) {
    }

    private BookBuilder() {
    }

    public static Result build(final BookDef book, final Predicate<BookDef.SectionDef> visible,
                               final Function<String, Component> parse, final BookPaginator.Layout layout) {
        return build(book, visible, List.of(), null, parse, layout);
    }

    /**
     * @param links chapter entries listed in the table of contents after the sections (already filtered)
     * @param back  entry at the end of the table of contents, e.g. back to the chapter index ({@code null} = none)
     */
    public static Result build(final BookDef book, final Predicate<BookDef.SectionDef> visible, final List<Link> links,
                               final Link back, final Function<String, Component> parse,
                               final BookPaginator.Layout layout) {
        final List<BookDef.SectionDef> sections = book.sections().stream().filter(visible).toList();

        // 1. Sections (their layout does not depend on page numbers).
        final List<List<Component>> sectionPages = new ArrayList<>();
        for (final BookDef.SectionDef s : sections) {
            final List<Component> flow = new ArrayList<>();
            flow.add(parse.apply(s.title())
                .decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.TRUE)
                .hoverEvent(HoverEvent.showText(Component.text("Wróć do spisu treści", NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.changePage(1)));
            flow.add(Component.empty());
            addParagraphs(flow, s.paragraphs(), parse);
            sectionPages.add(layout(flow, layout));
        }

        // 2. Table of contents. Only click/hover depend on the target pages, never the width, so a first pass
        //    with dummy targets tells how many pages the TOC takes and where the sections start.
        final int tocPages = layout(toc(book, sections, null, links, back, parse), layout).size();
        final List<Integer> starts = new ArrayList<>();
        int next = tocPages + 1;
        for (final List<Component> p : sectionPages) {
            starts.add(next);
            next += p.size();
        }
        final List<Component> pages = new ArrayList<>(layout(toc(book, sections, starts, links, back, parse), layout));
        sectionPages.forEach(pages::addAll);

        final boolean truncated = pages.size() > MAX_PAGES;
        return new Result(truncated ? List.copyOf(pages.subList(0, MAX_PAGES)) : List.copyOf(pages),
            List.copyOf(starts), truncated);
    }

    /** Intro, TOC header, one clickable entry per section and chapter ({@code null} entries are page breaks). */
    private static List<Component> toc(final BookDef book, final List<BookDef.SectionDef> sections,
                                       final List<Integer> starts, final List<Link> links, final Link back,
                                       final Function<String, Component> parse) {
        final List<Component> flow = new ArrayList<>();
        addParagraphs(flow, book.intro(), parse);
        if (!sections.isEmpty() || !links.isEmpty()) {
            if (!flow.isEmpty()) {
                flow.add(Component.empty());
            }
            flow.add(parse.apply(book.tocTitle()));
        }
        for (int i = 0; i < sections.size(); i++) {
            final int page = starts == null ? 1 : starts.get(i);
            flow.add(Component.text("» ", NamedTextColor.DARK_GRAY)
                .append(parse.apply(sections.get(i).title()))
                .hoverEvent(HoverEvent.showText(Component.text("Przejdź do strony " + page, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.changePage(page)));
        }
        for (final Link link : links) {
            flow.add(entry(link, parse));
        }
        if (back != null) {
            flow.add(Component.empty());
            flow.add(entry(back, parse));
        }
        return flow;
    }

    private static Component entry(final Link link, final Function<String, Component> parse) {
        final Component text = Component.text("» ", NamedTextColor.DARK_GRAY).append(parse.apply(link.name()))
            .clickEvent(ClickEvent.runCommand(link.command()));
        return link.hover() == null || link.hover().isBlank() ? text
            : text.hoverEvent(HoverEvent.showText(Component.text(link.hover(), NamedTextColor.GRAY)));
    }

    private static void addParagraphs(final List<Component> flow, final List<String> paragraphs,
                                      final Function<String, Component> parse) {
        for (final String p : paragraphs) {
            flow.add(PAGE_BREAK.equals(p.strip()) ? null : parse.apply(p));
        }
    }

    /** Paginates a flow; {@code null} elements start a new page. */
    private static List<Component> layout(final List<Component> flow, final BookPaginator.Layout layout) {
        final List<Component> out = new ArrayList<>();
        List<Component> chunk = new ArrayList<>();
        for (final Component c : flow) {
            if (c == null) {
                if (!chunk.isEmpty()) {
                    out.addAll(BookPaginator.paginate(chunk, layout));
                }
                chunk = new ArrayList<>();
            } else {
                chunk.add(c);
            }
        }
        if (!chunk.isEmpty() || out.isEmpty()) {
            out.addAll(BookPaginator.paginate(chunk, layout));
        }
        return out;
    }
}
