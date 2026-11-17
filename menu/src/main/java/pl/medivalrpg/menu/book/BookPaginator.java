// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.book;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Splits formatted text into book pages that fit the client: lines are word-wrapped by pixel width
 * ({@link FontWidth}) and at most {@code linesPerPage} lines go on one page. Works on Adventure components,
 * so colours, bold text, click and hover events survive the split (every character keeps its own style).
 *
 * <p>Each paragraph starts on a new line; an empty paragraph is an empty line. Empty lines at the top of a
 * continued page are dropped. Pure – no server access.</p>
 */
public final class BookPaginator {

    /** Book page geometry. */
    public record Layout(int lineWidth, int linesPerPage) {

        public static final Layout DEFAULT = new Layout(112, 14);

        public Layout {
            if (lineWidth < 10 || linesPerPage < 1) {
                throw new IllegalArgumentException("invalid book layout");
            }
        }
    }

    /** One character with its effective style. */
    record Glyph(int codepoint, Style style) {

        int width() {
            return FontWidth.of(this.codepoint, this.style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
        }
    }

    private BookPaginator() {
    }

    /** Lays paragraphs out on pages. Returns at least one (possibly empty) page. */
    public static List<Component> paginate(final List<Component> paragraphs, final Layout layout) {
        final List<List<List<Glyph>>> pages = new ArrayList<>();
        List<List<Glyph>> page = new ArrayList<>();
        for (final Component paragraph : paragraphs) {
            for (final List<Glyph> line : wrap(paragraph, layout.lineWidth())) {
                if (page.size() == layout.linesPerPage()) {
                    pages.add(page);
                    page = new ArrayList<>();
                }
                if (page.isEmpty() && line.isEmpty() && !pages.isEmpty()) {
                    continue; // no blank line at the top of a continued page
                }
                page.add(line);
            }
        }
        if (!page.isEmpty() || pages.isEmpty()) {
            pages.add(page);
        }
        final List<Component> out = new ArrayList<>(pages.size());
        for (final List<List<Glyph>> p : pages) {
            out.add(render(p));
        }
        return out;
    }

    /** Number of lines a paragraph takes. */
    public static int lineCount(final Component paragraph, final int width) {
        return wrap(paragraph, width).size();
    }

    /** Word-wraps one paragraph; explicit {@code \n} breaks the line. Always at least one (maybe empty) line. */
    static List<List<Glyph>> wrap(final Component paragraph, final int width) {
        final List<Glyph> glyphs = new ArrayList<>();
        flatten(paragraph, Style.empty(), glyphs);

        final List<List<Glyph>> lines = new ArrayList<>();
        final LineBuilder b = new LineBuilder(width, lines);
        for (final Glyph g : glyphs) {
            if (g.codepoint() == '\n') {
                b.flushWord();
                b.newLine();
            } else if (g.codepoint() == ' ') {
                b.flushWord();
                b.space = g;
            } else {
                b.word.add(g);
            }
        }
        b.flushWord();
        lines.add(b.line);
        return lines;
    }

    private static final class LineBuilder {
        private final int width;
        private final List<List<Glyph>> lines;
        private List<Glyph> line = new ArrayList<>();
        private int lineWidth;
        private final List<Glyph> word = new ArrayList<>();
        private Glyph space;

        LineBuilder(final int width, final List<List<Glyph>> lines) {
            this.width = width;
            this.lines = lines;
        }

        void newLine() {
            this.lines.add(this.line);
            this.line = new ArrayList<>();
            this.lineWidth = 0;
            this.space = null;
        }

        void flushWord() {
            if (this.word.isEmpty()) {
                return;
            }
            int wordWidth = 0;
            for (final Glyph g : this.word) {
                wordWidth += g.width();
            }
            final Glyph sp = this.space != null ? this.space : new Glyph(' ', this.word.getFirst().style());
            if (!this.line.isEmpty() && this.lineWidth + sp.width() + wordWidth <= this.width) {
                this.line.add(sp);
                this.line.addAll(this.word);
                this.lineWidth += sp.width() + wordWidth;
            } else {
                if (!this.line.isEmpty()) {
                    this.newLine();
                }
                for (final Glyph g : this.word) { // words longer than a line are cut by characters
                    if (!this.line.isEmpty() && this.lineWidth + g.width() > this.width) {
                        this.newLine();
                    }
                    this.line.add(g);
                    this.lineWidth += g.width();
                }
            }
            this.word.clear();
            this.space = null;
        }
    }

    /** Depth-first walk collecting characters with their inherited style. */
    private static void flatten(final Component c, final Style parent, final List<Glyph> out) {
        final Style style = parent.merge(c.style());
        final String text;
        if (c instanceof TextComponent t) {
            text = t.content();
        } else {
            text = PlainTextComponentSerializer.plainText().serialize(c.children(List.of()));
        }
        text.codePoints().forEach(cp -> out.add(new Glyph(cp == '\r' ? ' ' : cp, style)));
        for (final Component child : c.children()) {
            flatten(child, style, out);
        }
    }

    /** Rebuilds a page: runs of equally styled characters become one text component, lines joined by newlines. */
    private static Component render(final List<List<Glyph>> lines) {
        final var page = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                page.append(Component.newline());
            }
            final List<Glyph> line = lines.get(i);
            int start = 0;
            while (start < line.size()) {
                final Style style = line.get(start).style();
                int end = start;
                final StringBuilder sb = new StringBuilder();
                while (end < line.size() && line.get(end).style().equals(style)) {
                    sb.appendCodePoint(line.get(end).codepoint());
                    end++;
                }
                page.append(Component.text(sb.toString(), style));
                start = end;
            }
        }
        return page.build();
    }
}
