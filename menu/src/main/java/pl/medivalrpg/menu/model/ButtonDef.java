// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.List;

/**
 * One button of a menu (an entry of {@code menus.<id>.items}).
 *
 * @param id              key in the config (for messages)
 * @param slot            fixed slot, or {@link #AUTO} = next free slot of the menu's {@code auto-slots}
 * @param material        Material name (validated when the item is built; unknown → BARRIER + warning)
 * @param name            MiniMessage display name
 * @param lore            MiniMessage lore lines
 * @param glow            enchantment glint
 * @param viewerHead      {@code PLAYER_HEAD} showing the viewer's own skin ({@code head: viewer})
 * @param requiresPlugins requirement tokens: plugins that must be installed and enabled ({@code requires-plugin}) and
 *                        {@code /command} paths that must exist on the server ({@code requires-command}); otherwise
 *                        the button is hidden
 * @param permission      permission required to see the button ({@code null} = none)
 * @param condition       extra condition (country membership)
 * @param action          click action
 * @param keepOpen        keep the menu open after a {@code command:} action
 * @param progress        tutorial step id ({@code progress:}); the button shows whether the viewer has done that step
 *                        ({@code summary} = the "done/total" line), {@code null} = an ordinary button
 */
public record ButtonDef(
    String id,
    int slot,
    String material,
    String name,
    List<String> lore,
    boolean glow,
    boolean viewerHead,
    List<String> requiresPlugins,
    String permission,
    Condition condition,
    MenuAction action,
    boolean keepOpen,
    String progress
) {

    /** {@code progress: summary}: the button carries the "done/total" line instead of one step. */
    public static final String PROGRESS_SUMMARY = "summary";


    public static final int AUTO = -1;

    public boolean autoSlot() {
        return this.slot == AUTO;
    }
}
