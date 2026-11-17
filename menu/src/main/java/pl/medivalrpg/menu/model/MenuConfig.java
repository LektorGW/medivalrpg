// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.List;
import java.util.Map;

/**
 * Parsed {@code menus.yml}.
 *
 * @param menus    menus by id (config order)
 * @param books    books by id
 * @param warnings human-readable problems found while parsing (logged, never fatal)
 */
public record MenuConfig(Map<String, MenuDef> menus, Map<String, BookDef> books, List<String> warnings) {

    public static final String MAIN = "main";

    public MenuDef menu(final String id) {
        return id == null ? null : this.menus.get(id);
    }

    public BookDef book(final String id) {
        return id == null ? null : this.books.get(id);
    }
}
