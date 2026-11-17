// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative estimate of how many lines a text takes on a book page. The vanilla font is proportional
 * (a page is ~114 px, a typical letter 6 px → ~19 letters), so we count in letters with safety margins:
 * <ul>
 *   <li>Latin letters incl. Polish ones, digits and punctuation = 1,</li>
 *   <li>anything from U+2000 up (icons ✦ ⚙ ✔, bullets, dashes, quotes) = 2 (they come from the wider fallback font),</li>
 *   <li>bold text is 1 px wider per letter → +1/6 per letter.</li>
 * </ul>
 * Wrapping mimics the client: greedy by words, a space at a line break disappears, a word longer than a line
 * is cut. Widths are kept in sixths of a letter to stay in integers. Pure functions.
 */
public final class LineWrap {

    private static final int UNIT = 6;

    private LineWrap() {
    }

    /** Width of one character in sixths of a letter. */
    static int charWidth(final int codePoint, final boolean bold) {
        final int base = codePoint >= 0x2000 ? 2 * UNIT : UNIT;
        return bold ? base + 1 : base;
    }

    static int wordWidth(final String word, final boolean bold) {
        int width = 0;
        for (int i = 0; i < word.length(); ) {
            final int cp = word.codePointAt(i);
            width += charWidth(cp, bold);
            i += Character.charCount(cp);
        }
        return width;
    }

    /** Lines taken by {@code plain} (may contain {@code \n}) at {@code charsPerLine} letters per line; at least 1. */
    public static int lines(final String plain, final int charsPerLine, final boolean bold) {
        final int max = Math.max(1, charsPerLine) * UNIT;
        final int space = UNIT; // a space is narrower (4 px) — counting it as a full letter is the safe side
        int lines = 0;
        for (final String paragraph : plain.split("\n", -1)) {
            lines++;
            int current = 0;
            for (final String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                int w = wordWidth(word, bold);
                if (current > 0 && current + space + w <= max) {
                    current += space + w;
                    continue;
                }
                if (current > 0) {
                    lines++;
                }
                while (w > max) { // the client cuts words longer than a line
                    lines++;
                    w -= max;
                }
                current = w;
            }
        }
        return Math.max(1, lines);
    }

    /**
     * Splits {@code plain} into chunks of at most {@code maxLines} lines each (by words). Used only for a single
     * point that would not fit even on an empty page.
     */
    public static List<String> splitToFit(final String plain, final int charsPerLine, final int maxLines) {
        final List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (final String word : plain.replace('\n', ' ').split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            final String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || lines(candidate, charsPerLine, false) <= maxLines) {
                current = new StringBuilder(candidate);
            } else {
                chunks.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
