// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.menu.integration.CommandPaths;
import pl.medivalrpg.menu.integration.Requirements;
import pl.medivalrpg.menu.model.BookDef;
import pl.medivalrpg.menu.model.ButtonDef;
import pl.medivalrpg.menu.model.Condition;
import pl.medivalrpg.menu.model.MenuAction;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.model.MenuDef;
import pl.medivalrpg.menu.model.MenuType;

/**
 * Parses {@code menus.yml} into {@link MenuConfig}. Pure (no server access), so it is unit-tested.
 * Problems never abort loading: the broken element is skipped or corrected and a Polish warning is recorded.
 */
public final class MenusLoader {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");

    private MenusLoader() {
    }

    public static MenuConfig load(final ConfigurationSection root) {
        final List<String> warnings = new ArrayList<>();
        final Map<String, MenuDef> menus = new LinkedHashMap<>();
        final Map<String, BookDef> books = new LinkedHashMap<>();
        if (root == null) {
            warnings.add("Pusty plik menus.yml.");
            return new MenuConfig(menus, books, warnings);
        }

        final ConfigurationSection menusSection = root.getConfigurationSection("menus");
        if (menusSection == null) {
            warnings.add("Brak sekcji 'menus' w menus.yml.");
        } else {
            for (final String key : menusSection.getKeys(false)) {
                final String id = key.toLowerCase(Locale.ROOT);
                final ConfigurationSection s = menusSection.getConfigurationSection(key);
                if (s == null) {
                    warnings.add("menus." + key + ": to nie jest sekcja – pomijam.");
                    continue;
                }
                if (!ID.matcher(id).matches()) {
                    warnings.add("menus." + key + ": id może zawierać tylko a-z, 0-9, _ i - – pomijam.");
                    continue;
                }
                final MenuDef menu = parseMenu(id, s, warnings);
                if (menu != null) {
                    menus.put(id, menu);
                }
            }
        }

        final ConfigurationSection booksSection = root.getConfigurationSection("books");
        if (booksSection != null) {
            for (final String key : booksSection.getKeys(false)) {
                final String id = key.toLowerCase(Locale.ROOT);
                final ConfigurationSection s = booksSection.getConfigurationSection(key);
                if (s == null || !ID.matcher(id).matches()) {
                    warnings.add("books." + key + ": niepoprawna książka – pomijam.");
                    continue;
                }
                books.put(id, parseBook(id, s, warnings));
            }
        }

        if (!menus.containsKey(MenuConfig.MAIN)) {
            warnings.add("Brak menu 'main' – /menu nie będzie działać.");
        }
        validateReferences(menus, books, warnings);
        return new MenuConfig(Collections.unmodifiableMap(menus), Collections.unmodifiableMap(books),
            List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ menus

    static MenuDef parseMenu(final String id, final ConfigurationSection s, final List<String> warnings) {
        final String where = "menus." + id;
        int rows = s.getInt("rows", 6);
        if (rows < 1 || rows > 6) {
            warnings.add(where + ".rows musi być 1–6 – ustawiam 6.");
            rows = 6;
        }
        final int size = rows * 9;

        MenuType type = MenuType.STATIC;
        try {
            type = MenuType.parse(s.getString("type"));
        } catch (final IllegalArgumentException e) {
            warnings.add(where + ".type: " + e.getMessage() + " – ustawiam static.");
        }

        final List<Integer> autoSlots = slots(s, "auto-slots", size, where, warnings);
        final List<Integer> contentSlots = slots(s, "content-slots", size, where, warnings);
        if (type == MenuType.INTERACTIONS && contentSlots.isEmpty()) {
            warnings.add(where + ": menu typu interactions nie ma content-slots – interakcje się nie pokażą.");
        }
        if (type == MenuType.MOTIONS && contentSlots.isEmpty()) {
            warnings.add(where + ": menu typu motions nie ma content-slots – tańce i emotki się nie pokażą.");
        }
        if (type == MenuType.COUNTRIES && contentSlots.isEmpty()) {
            warnings.add(where + ": menu typu countries nie ma content-slots – kraje się nie pokażą.");
        }

        final List<ButtonDef> buttons = new ArrayList<>();
        final Set<Integer> usedSlots = new HashSet<>();
        int autoCount = 0;
        final ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (final String key : items.getKeys(false)) {
                final ConfigurationSection b = items.getConfigurationSection(key);
                if (b == null) {
                    warnings.add(where + ".items." + key + ": to nie jest sekcja – pomijam.");
                    continue;
                }
                final ButtonDef button = parseButton(where + ".items." + key, key, b, size, warnings);
                if (button == null) {
                    continue;
                }
                if (button.autoSlot()) {
                    autoCount++;
                } else if (!usedSlots.add(button.slot()) && button.requiresPlugins().isEmpty()
                    && button.condition() == Condition.NONE && button.permission() == null) {
                    // Two unconditional buttons in one slot: the later one would always hide the earlier one.
                    warnings.add(where + ".items." + key + ": slot " + button.slot()
                        + " jest już zajęty przez inny przycisk (wygrywa późniejszy).");
                }
                buttons.add(button);
            }
        }
        if (autoCount > autoSlots.size()) {
            warnings.add(where + ": " + autoCount + " przycisków bez slotu, a auto-slots ma tylko "
                + autoSlots.size() + " miejsc – nadmiarowe przyciski się nie pokażą.");
        }

        return new MenuDef(id,
            s.getString("title", "<dark_gray>Menu"),
            rows,
            type,
            blankToNull(s.getString("permission")),
            requirements(s, where, warnings),
            material(s.getString("border")),
            material(s.getString("border-corners")),
            material(s.getString("filler")),
            autoSlots,
            contentSlots,
            List.copyOf(buttons));
    }

    static ButtonDef parseButton(final String where, final String id, final ConfigurationSection b, final int size,
                                 final List<String> warnings) {
        final int slot;
        final Object rawSlot = b.get("slot");
        if (rawSlot == null || "auto".equalsIgnoreCase(String.valueOf(rawSlot).strip())) {
            slot = ButtonDef.AUTO;
        } else {
            final Integer parsed = toInt(rawSlot);
            if (parsed == null || parsed < 0 || parsed >= size) {
                warnings.add(where + ".slot '" + rawSlot + "' poza menu (0–" + (size - 1) + ") – pomijam przycisk.");
                return null;
            }
            slot = parsed;
        }

        final MenuAction action;
        try {
            action = MenuAction.parse(b.getString("action"));
        } catch (final IllegalArgumentException e) {
            warnings.add(where + ".action: " + e.getMessage() + " – pomijam przycisk.");
            return null;
        }

        Condition condition = Condition.NONE;
        try {
            condition = Condition.parse(b.getString("condition"));
        } catch (final IllegalArgumentException e) {
            warnings.add(where + ".condition: " + e.getMessage() + " – pomijam przycisk.");
            return null;
        }

        final String head = b.getString("head", "");
        final boolean viewerHead = "viewer".equalsIgnoreCase(head.strip()) || "gracz".equalsIgnoreCase(head.strip());
        if (!head.isBlank() && !viewerHead) {
            warnings.add(where + ".head: obsługiwana jest tylko wartość 'viewer' – ignoruję.");
        }

        String material = material(b.getString("material"));
        if (material == null) {
            material = viewerHead ? "PLAYER_HEAD" : "PAPER";
        }

        return new ButtonDef(id,
            slot,
            material,
            b.getString("name", ""),
            strings(b, "lore"),
            b.getBoolean("glow", false),
            viewerHead,
            requirements(b, where, warnings),
            blankToNull(b.getString("permission")),
            condition,
            action,
            b.getBoolean("keep-open", false),
            progress(b.getString("progress")));
    }

    // ------------------------------------------------------------------ books

    static BookDef parseBook(final String id, final ConfigurationSection s, final List<String> warnings) {
        final List<BookDef.SectionDef> sections = new ArrayList<>();
        final ConfigurationSection secs = s.getConfigurationSection("sections");
        if (secs == null) {
            if (names(s, "chapters").isEmpty()) {
                warnings.add("books." + id + ": brak sekcji 'sections' – książka będzie pusta.");
            }
        } else {
            for (final String key : secs.getKeys(false)) {
                final ConfigurationSection sec = secs.getConfigurationSection(key);
                if (sec == null) {
                    warnings.add("books." + id + ".sections." + key + ": to nie jest sekcja – pomijam.");
                    continue;
                }
                sections.add(new BookDef.SectionDef(key,
                    sec.getString("title", key),
                    requirements(sec, "books." + id + ".sections." + key, warnings),
                    blankToNull(sec.getString("permission")),
                    strings(sec, "text")));
            }
        }
        final List<String> chapters = names(s, "chapters").stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
        return new BookDef(id,
            s.getString("title", id),
            s.getString("author", "MEDIVALRPG"),
            strings(s, "intro"),
            s.getString("toc-title", "<bold>Spis treści</bold>"),
            List.copyOf(sections),
            blankToNull(s.getString("name")),
            blankToNull(s.getString("description")),
            requirements(s, "books." + id, warnings),
            blankToNull(s.getString("permission")),
            chapters);
    }

    // ------------------------------------------------------------------ validation

    private static void validateReferences(final Map<String, MenuDef> menus, final Map<String, BookDef> books,
                                           final List<String> warnings) {
        for (final BookDef book : books.values()) {
            for (final String chapter : book.chapters()) {
                if (!books.containsKey(book.chapterBookId(chapter))) {
                    warnings.add("books." + book.id() + ".chapters: rozdział '" + chapter + "' – nie ma książki "
                        + book.chapterBookId(chapter) + ".");
                }
            }
        }
        for (final MenuDef menu : menus.values()) {
            for (final ButtonDef b : menu.buttons()) {
                final String where = "menus." + menu.id() + ".items." + b.id();
                if (b.action() instanceof MenuAction.Page && menu.type() != MenuType.COUNTRIES) {
                    warnings.add(where + ": akcja page działa tylko w menu typu countries – przycisk będzie ukryty.");
                } else if (b.action() instanceof MenuAction.OpenMenu(final String target) && !menus.containsKey(target)) {
                    warnings.add(where + ": akcja menu:" + target + " – nie ma takiego menu.");
                } else if (b.action() instanceof MenuAction.OpenBook(final String target) && !books.containsKey(target)) {
                    warnings.add(where + ": akcja book:" + target + " – nie ma takiej książki.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Slot list: numbers and ranges ({@code 10-16}), as a YAML list or a comma separated string.
     * Out-of-range and duplicate entries are dropped with a warning.
     */
    static List<Integer> slots(final ConfigurationSection s, final String key, final int size, final String where,
                               final List<String> warnings) {
        final List<Object> raw = new ArrayList<>();
        if (s.isList(key)) {
            raw.addAll(s.getList(key, List.of()));
        } else if (s.isString(key)) {
            raw.addAll(List.of(s.getString(key, "").split(",")));
        } else if (s.isInt(key)) {
            raw.add(s.getInt(key));
        }
        final Set<Integer> out = new LinkedHashSet<>();
        for (final Object o : raw) {
            final String text = String.valueOf(o).strip();
            if (text.isEmpty()) {
                continue;
            }
            final int dash = text.indexOf('-', 1);
            final Integer from = toInt(dash < 0 ? text : text.substring(0, dash));
            final Integer to = dash < 0 ? from : toInt(text.substring(dash + 1));
            if (from == null || to == null || from > to) {
                warnings.add(where + "." + key + ": niepoprawny wpis '" + text + "' – pomijam.");
                continue;
            }
            for (int i = from; i <= to; i++) {
                if (i < 0 || i >= size) {
                    warnings.add(where + "." + key + ": slot " + i + " poza menu (0–" + (size - 1) + ") – pomijam.");
                } else if (!out.add(i)) {
                    warnings.add(where + "." + key + ": slot " + i + " powtórzony – pomijam.");
                }
            }
        }
        return List.copyOf(out);
    }

    /** A string or a list of strings. */
    static List<String> strings(final ConfigurationSection s, final String key) {
        if (s.isList(key)) {
            final List<String> out = new ArrayList<>();
            for (final Object o : s.getList(key, List.of())) {
                out.add(o == null ? "" : String.valueOf(o));
            }
            return List.copyOf(out);
        }
        final String single = s.getString(key);
        return single == null ? List.of() : List.of(single);
    }

    /**
     * Requirement tokens: {@code requires-plugin} names plus {@code requires-command} literal paths as
     * {@code "/path"} tokens (see {@link Requirements}). Paths with arguments are rejected with a warning.
     */
    static List<String> requirements(final ConfigurationSection s, final String where, final List<String> warnings) {
        final List<String> out = new ArrayList<>(names(s, "requires-plugin"));
        for (final String command : names(s, "requires-command")) {
            if (!CommandPaths.literal(command)) {
                warnings.add(where + ".requires-command '" + command
                    + "': podaj same słowa komendy bez argumentów, np. \"firma kredyt\" – pomijam.");
                continue;
            }
            out.add(Requirements.commandToken(command));
        }
        return List.copyOf(out);
    }

    /** Plugin names: a string or a list, blanks dropped. */
    static List<String> names(final ConfigurationSection s, final String key) {
        return strings(s, key).stream().map(String::strip).filter(n -> !n.isEmpty()).toList();
    }

    private static Integer toInt(final Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).strip());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String material(final String raw) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        return raw.strip().toUpperCase(Locale.ROOT);
    }

    private static String progress(final String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
