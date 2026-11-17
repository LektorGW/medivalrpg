// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.Locale;

/**
 * What a button does when clicked. Parsed from the {@code action} string of a button in {@code menus.yml}:
 *
 * <ul>
 *   <li>{@code command:<komenda>} – runs the command as the player (leading {@code /} optional),</li>
 *   <li>{@code menu:<id>} – opens another menu,</li>
 *   <li>{@code book:<id>} – opens a book from the {@code books} section,</li>
 *   <li>{@code url:<adres>} – closes the menu and sends a clickable link in chat (GUIs cannot open URLs),</li>
 *   <li>{@code suggest:<tekst>} – closes the menu and sends a clickable chat line that puts the text into the chat box,</li>
 *   <li>{@code player:<zasięg>:<komenda>} – picks a nearby player, then runs {@code /<komenda> <nick>},</li>
 *   <li>{@code page:prev} / {@code page:next} – previous / next page of a menu with generated content
 *       ({@code type: countries}); the button is hidden when that page does not exist,</li>
 *   <li>{@code help:<kategoria>} – opens the generated help GUI of a category from {@code pomoc.yml},</li>
 *   <li>{@code close}, {@code none} (decoration, the default).</li>
 * </ul>
 */
public sealed interface MenuAction {

    record Command(String command) implements MenuAction {
    }

    record OpenMenu(String menuId) implements MenuAction {
    }

    record OpenBook(String bookId) implements MenuAction {
    }

    record Url(String url) implements MenuAction {
    }

    record Suggest(String text) implements MenuAction {
    }

    record PickPlayer(double range, String command) implements MenuAction {
    }

    /** {@code delta} = -1 (previous page) or +1 (next page). */
    record Page(int delta) implements MenuAction {
    }

    /** Help GUI of one {@code pomoc.yml} category. */
    record Help(String category) implements MenuAction {
    }

    record Close() implements MenuAction {
    }

    record None() implements MenuAction {
    }

    MenuAction NONE = new None();
    MenuAction CLOSE = new Close();

    /**
     * Parses an action string.
     *
     * @throws IllegalArgumentException with a Polish message when the string is invalid
     */
    static MenuAction parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        final String value = raw.stripLeading(); // trailing spaces matter for suggest: ("/kraj zaloz ")
        final int colon = value.indexOf(':');
        final String type = (colon < 0 ? value : value.substring(0, colon)).toLowerCase(Locale.ROOT).strip();
        final String arg = colon < 0 ? "" : value.substring(colon + 1);
        return switch (type) {
            case "none", "brak" -> NONE;
            case "close", "zamknij" -> CLOSE;
            case "command", "cmd", "komenda" -> new Command(stripSlash(require(arg, type)));
            case "menu" -> new OpenMenu(require(arg, type).strip().toLowerCase(Locale.ROOT));
            case "book", "ksiazka" -> new OpenBook(require(arg, type).strip().toLowerCase(Locale.ROOT));
            case "url", "link" -> new Url(require(arg, type).strip());
            case "suggest", "podpowiedz" -> new Suggest(require(arg, type).stripLeading());
            case "player", "gracz" -> parsePick(require(arg, type));
            case "page", "strona" -> parsePage(require(arg, type));
            case "help", "pomoc" -> new Help(require(arg, type).strip().toLowerCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("nieznany typ akcji '" + type
                + "' (dostępne: command, menu, book, url, suggest, player, page, help, close, none)");
        };
    }

    private static MenuAction parsePage(final String arg) {
        return switch (arg.strip().toLowerCase(Locale.ROOT)) {
            case "prev", "previous", "poprzednia", "wstecz" -> new Page(-1);
            case "next", "nastepna", "dalej" -> new Page(1);
            default -> throw new IllegalArgumentException("akcja page wymaga page:prev albo page:next");
        };
    }

    private static MenuAction parsePick(final String arg) {
        final int colon = arg.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("akcja player wymaga formatu player:<zasięg>:<komenda>");
        }
        final double range;
        try {
            range = Double.parseDouble(arg.substring(0, colon).strip());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("niepoprawny zasięg w akcji player: '" + arg.substring(0, colon) + "'");
        }
        if (!(range > 0) || range > 512) {
            throw new IllegalArgumentException("zasięg w akcji player musi być w przedziale (0, 512]");
        }
        return new PickPlayer(range, stripSlash(require(arg.substring(colon + 1), "player")));
    }

    private static String require(final String arg, final String type) {
        if (arg.isBlank()) {
            throw new IllegalArgumentException("akcja '" + type + "' wymaga argumentu po dwukropku");
        }
        return arg;
    }

    private static String stripSlash(final String command) {
        String c = command.strip();
        while (c.startsWith("/")) {
            c = c.substring(1);
        }
        if (c.isBlank()) {
            throw new IllegalArgumentException("pusta komenda");
        }
        return c;
    }
}
