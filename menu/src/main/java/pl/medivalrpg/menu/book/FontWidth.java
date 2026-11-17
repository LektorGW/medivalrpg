// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.book;

/**
 * Approximate pixel widths of the vanilla Minecraft font (glyph advance including the 1 px spacing).
 * Bold text is 1 px wider per character. Unknown symbols (rendered by the unifont fallback) are over-estimated,
 * which only makes lines a little shorter – never longer than the book page.
 */
public final class FontWidth {

    /** Width of a book page's text area in pixels. */
    public static final int BOOK_PAGE_WIDTH = 114;

    private FontWidth() {
    }

    public static int of(final int codepoint, final boolean bold) {
        return base(codepoint) + (bold ? 1 : 0);
    }

    static int base(final int c) {
        switch (c) {
            case ' ':
                return 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|':
                return 2;
            case '`', 'l':
                return 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}':
                return 4;
            case '<', '>', 'f', 'k', 'ł', 'í', 'ì':
                return 5;
            case '@', '~':
                return 7;
            case 0x00B7: // ·
                return 3;
            case 0x2022: // •
                return 5;
            case 0x2013: // –
                return 7;
            default:
                break;
        }
        if (c < 0x20) {
            return 0;
        }
        if (c <= 0x024F) {
            return 6; // printable ASCII, Latin-1, Latin Extended (ą ć ę ń ó ś ź ż Ł, », « ...)
        }
        return 9; // other symbols and emoji come from the unifont fallback
    }
}
