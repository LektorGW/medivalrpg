// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Entry category from {@code categories.<key>} in config.yml.
 *
 * @param chatColor colour on the chat (dark background)
 * @param bookColor colour in the book (light paper)
 */
public record Category(String key, String name, String icon, TextColor chatColor, TextColor bookColor) {

    /** Style for a key that is not configured (typo in changelog.yml): shown, but neutral. */
    public static Category fallback(final String key) {
        return new Category(key, key, "•", NamedTextColor.GRAY, NamedTextColor.DARK_GRAY);
    }
}
