// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Packs blocks (a component plus the number of lines it takes, see {@link LineWrap}) into book pages without
 * ever exceeding the line budget of a page, unless a single block is larger than a page. Pure functions.
 */
public final class BookLayout {

    /** A piece of a page that is never split: a title, one point, one table of contents line... */
    public record Block(Component content, int lines) {

        public Block {
            lines = Math.max(1, lines);
        }

        /** Block measured from its own plain text; bold anywhere in it → the whole block counts as bold. */
        public static Block measured(final Component content, final int charsPerLine) {
            return new Block(content, LineWrap.lines(EntryFormat.plain(content), charsPerLine, containsBold(content)));
        }

        public static Block blank() {
            return new Block(Component.empty(), 1);
        }
    }

    private BookLayout() {
    }

    /** Greedy packing (table of contents). */
    public static List<List<Block>> pages(final List<Block> blocks, final int linesPerPage) {
        final List<List<Block>> pages = new ArrayList<>();
        List<Block> page = new ArrayList<>();
        int used = 0;
        for (final Block b : blocks) {
            if (!page.isEmpty() && used + b.lines() > linesPerPage) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
            }
            page.add(b);
            used += b.lines();
        }
        if (!page.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }

    /**
     * Pages of one entry: the header starts the first page, points follow; when a point does not fit, it moves to
     * a new page that starts with {@code continued} (e.g. "(cd.) Title"). Points must already be at most
     * {@code linesPerPage - continued.lines()} lines (see {@link LineWrap#splitToFit}).
     */
    public static List<List<Block>> entryPages(final List<Block> header, final List<Block> points, final Block continued,
                                               final int linesPerPage) {
        final List<List<Block>> pages = new ArrayList<>();
        List<Block> page = new ArrayList<>(header);
        int used = header.stream().mapToInt(Block::lines).sum();
        for (final Block p : points) {
            if (used + p.lines() > linesPerPage) {
                pages.add(page);
                page = new ArrayList<>();
                page.add(continued);
                used = continued.lines();
            }
            page.add(p);
            used += p.lines();
        }
        pages.add(page);
        return pages;
    }

    public static int lines(final List<Block> page) {
        return page.stream().mapToInt(Block::lines).sum();
    }

    /** One page component: blocks joined with new lines. */
    public static Component compose(final List<Block> page) {
        return Component.join(JoinConfiguration.newlines(), page.stream().map(Block::content).toList());
    }

    static boolean containsBold(final Component component) {
        if (component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        for (final Component child : component.children()) {
            if (containsBold(child)) {
                return true;
            }
        }
        return false;
    }
}
