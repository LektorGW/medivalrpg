// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.List;

/**
 * One menu from {@code menus.yml}.
 *
 * @param id              menu id ({@code menu:<id>}, {@code /menu <id>})
 * @param title           MiniMessage title
 * @param rows            1–6
 * @param type            static or generated content
 * @param permission      permission required to open ({@code null} = none)
 * @param requiresPlugins requirement tokens (plugins, {@code /command} paths) required to open
 * @param border          material of the frame, {@code null} = no frame
 * @param borderCorners   material of the four corners, {@code null} = same as border
 * @param filler          material of the remaining empty slots, {@code null} = leave empty
 * @param autoSlots       slots filled in order by buttons without a fixed slot (a partial last row is centred)
 * @param contentSlots    slots for generated content ({@link MenuType#INTERACTIONS})
 * @param buttons         buttons in config order
 */
public record MenuDef(
    String id,
    String title,
    int rows,
    MenuType type,
    String permission,
    List<String> requiresPlugins,
    String border,
    String borderCorners,
    String filler,
    List<Integer> autoSlots,
    List<Integer> contentSlots,
    List<ButtonDef> buttons
) {

    public int size() {
        return this.rows * 9;
    }
}
