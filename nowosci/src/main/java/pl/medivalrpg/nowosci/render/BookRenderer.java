// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.render.BookLayout.Block;
import pl.medivalrpg.nowosci.render.EntryFormat.Medium;

/**
 * The {@code /nowosci} book: a table of contents (one clickable line per entry, {@link ClickEvent#changePage})
 * followed by the entries, newest first, each starting on a new page. Page 1 is a cover (frame of symbols,
 * "Nowości", date, how many entries are new for the reader, link to the table of contents). Entries newer than
 * the reader's last seen one are marked with ★ in the table of contents and on their page; being the newest,
 * they are always on the first pages. Pure function of the changelog, the reader's last seen id and config.
 */
public final class BookRenderer {

    /** Widest page number a table of contents line can show; lines are measured with it. */
    private static final String WIDEST_PAGE = "100";

    /**
     * @param layout          blocks of every page (same order as {@code book.pages()})
     * @param entryStartPages page (1-based) each shown entry starts on, newest first
     * @param shownEntries    how many entries made it into the book (oldest are dropped above {@code max-pages})
     */
    public record Rendered(Book book, List<List<Block>> layout, List<Integer> entryStartPages, int tocPages,
                           int shownEntries) {
    }

    private final EntryFormat format;

    public BookRenderer(final EntryFormat format) {
        this.format = format;
    }

    /** @param lastSeenId highest entry id the reader has already seen (entries above it are marked as new) */
    public Book book(final Changelog changelog, final int lastSeenId) {
        return this.render(changelog, lastSeenId).book();
    }

    public Rendered render(final Changelog changelog, final int lastSeenId) {
        final Messages m = this.format.messages();
        final Component title = m.get("book-title");
        final Component author = m.get("book-author");
        final int lpp = this.format.config().bookLinesPerPage;
        final int maxPages = this.format.config().bookMaxPages;

        if (changelog.isEmpty()) {
            return new Rendered(Book.book(title, author, List.of(m.get("book-empty"))), List.of(), List.of(), 0, 0);
        }

        final List<List<List<Block>>> entryPages = new ArrayList<>();
        for (final Entry e : changelog.entries()) {
            entryPages.add(this.entryPages(e, e.id() > lastSeenId));
        }

        // Oldest entries are dropped if the book would exceed the client's page limit (1 = cover).
        int count = entryPages.size();
        int tocPages = this.tocPageCount(changelog.entries().subList(0, count), lastSeenId, lpp);
        while (count > 1 && 1 + tocPages + total(entryPages, count) > maxPages) {
            count--;
            tocPages = this.tocPageCount(changelog.entries().subList(0, count), lastSeenId, lpp);
        }

        final List<Integer> starts = new ArrayList<>();
        int next = 1 + tocPages + 1;
        for (int i = 0; i < count; i++) {
            starts.add(next);
            next += entryPages.get(i).size();
        }

        final List<List<Block>> layout = new ArrayList<>();
        layout.add(this.cover(changelog, lastSeenId));
        layout.addAll(BookLayout.pages(this.tocBlocks(changelog.entries().subList(0, count), starts, lastSeenId), lpp));
        for (int i = 0; i < count; i++) {
            layout.addAll(entryPages.get(i));
        }
        final List<List<Block>> limited = layout.size() > maxPages ? layout.subList(0, maxPages) : layout;
        final List<Component> pages = limited.stream().map(BookLayout::compose).toList();
        return new Rendered(Book.book(title, author, pages), List.copyOf(limited), List.copyOf(starts), tocPages, count);
    }

    private static int total(final List<List<List<Block>>> entryPages, final int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            sum += entryPages.get(i).size();
        }
        return sum;
    }

    // ------------------------------------------------------------------ table of contents

    // ------------------------------------------------------------------ cover

    private List<Block> cover(final Changelog changelog, final int lastSeenId) {
        final Messages m = this.format.messages();
        final int cpl = this.format.config().bookCharsPerLine;
        final Entry newest = changelog.entries().getFirst();
        final int unread = changelog.newerThan(lastSeenId).size();
        final Component status = unread > 0
            ? m.get("book-cover-unread",
                Placeholder.unparsed("count", Integer.toString(unread)),
                Placeholder.unparsed("word", NowosciConfig.plural(this.format.config().pluralNewEntries, unread)))
            : m.get("book-cover-all-read");
        final Component toc = m.get("book-cover-toc")
            .clickEvent(ClickEvent.changePage(2))
            .hoverEvent(HoverEvent.showText(m.get("book-cover-toc-hover")));
        final List<Block> blocks = new ArrayList<>();
        blocks.add(Block.measured(m.get("book-cover-frame"), cpl));
        blocks.add(Block.blank());
        blocks.add(Block.measured(m.get("book-cover-title"), cpl));
        blocks.add(Block.measured(m.get("book-cover-subtitle"), cpl));
        blocks.add(Block.blank());
        blocks.add(Block.measured(m.get("book-cover-date", Placeholder.unparsed("date", this.format.date(newest))), cpl));
        blocks.add(Block.measured(status, cpl));
        blocks.add(Block.blank());
        blocks.add(Block.measured(m.get("book-cover-frame"), cpl));
        blocks.add(Block.blank());
        blocks.add(Block.measured(toc, cpl));
        if (unread > 0) {
            blocks.add(Block.measured(m.get("book-cover-read")
                .clickEvent(ClickEvent.runCommand(ChatRenderer.CMD_READ))
                .hoverEvent(HoverEvent.showText(m.get("book-cover-read-hover"))), cpl));
        }
        return blocks;
    }

    // ------------------------------------------------------------------ table of contents

    private int tocPageCount(final List<Entry> entries, final int lastSeenId, final int lpp) {
        final List<Integer> worst = entries.stream().map(e -> 100).toList();
        return BookLayout.pages(this.tocBlocks(entries, worst, lastSeenId), lpp).size();
    }

    /** TOC blocks; line heights are always measured with the widest page number, so the count never changes. */
    private List<Block> tocBlocks(final List<Entry> entries, final List<Integer> pages, final int lastSeenId) {
        final Messages m = this.format.messages();
        final int cpl = this.format.config().bookCharsPerLine;
        final List<Block> blocks = new ArrayList<>();
        blocks.add(Block.measured(m.get("book-toc-header"), cpl));
        blocks.add(Block.blank());
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            final String key = e.id() > lastSeenId ? "book-toc-line-new" : "book-toc-line";
            final String page = Integer.toString(pages.get(i));
            final Component worst = m.get(key, this.format.tags(e, Medium.BOOK),
                Placeholder.unparsed("page", WIDEST_PAGE));
            final Component line = m.get(key, this.format.tags(e, Medium.BOOK),
                    Placeholder.unparsed("page", page))
                .clickEvent(ClickEvent.changePage(pages.get(i)))
                .hoverEvent(HoverEvent.showText(m.get("book-toc-hover", Placeholder.unparsed("page", page))));
            blocks.add(new Block(line, Block.measured(worst, cpl).lines()));
        }
        return blocks;
    }

    // ------------------------------------------------------------------ entries

    private List<List<Block>> entryPages(final Entry e, final boolean unread) {
        final Messages m = this.format.messages();
        final int cpl = this.format.config().bookCharsPerLine;
        final int lpp = this.format.config().bookLinesPerPage;

        final List<Block> header = new ArrayList<>();
        header.add(Block.measured(m.get("book-entry-title", this.format.tags(e, Medium.BOOK)), cpl));
        header.add(Block.measured(m.get(unread ? "book-entry-meta-new" : "book-entry-meta",
            this.format.tags(e, Medium.BOOK)), cpl));
        header.add(Block.blank());
        final Block continued = Block.measured(m.get("book-continued", this.format.tags(e, Medium.BOOK)), cpl);
        final int capacity = Math.max(1, lpp - continued.lines());

        final List<Block> points = new ArrayList<>();
        for (final String raw : e.points()) {
            final Block point = Block.measured(m.get("book-entry-point",
                Placeholder.component("point", this.format.point(raw, Medium.BOOK))), cpl);
            if (point.lines() <= capacity) {
                points.add(point);
                continue;
            }
            // A single point longer than a page: split it by words (its own formatting is lost, rare case).
            // One line of headroom for the bullet in front of the first chunk.
            final Component bullet = m.get("book-entry-point", Placeholder.unparsed("point", ""));
            final String text = EntryFormat.plain(this.format.point(raw, Medium.BOOK));
            boolean first = true;
            for (final String chunk : LineWrap.splitToFit(text, cpl, Math.max(1, capacity - 1))) {
                final Component c = first
                    ? bullet.append(Component.text(chunk, NamedTextColor.BLACK))
                    : Component.text(chunk, NamedTextColor.BLACK);
                points.add(Block.measured(c, cpl));
                first = false;
            }
        }
        return BookLayout.entryPages(header, points, continued, lpp);
    }
}
