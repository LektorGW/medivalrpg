// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places buttons without a fixed slot into the menu's {@code auto-slots}: rows are filled in order and a
 * partially filled row is centred inside its own auto slots, so hidden optional buttons (e.g. Skarbiec, Armia
 * not installed) never leave holes. Pure.
 */
public final class AutoLayout {

    private AutoLayout() {
    }

    /**
     * @param autoSlots slots in config order
     * @param count     number of visible auto buttons
     * @return the slot of each button (size = min(count, autoSlots.size()))
     */
    public static List<Integer> assign(final List<Integer> autoSlots, final int count) {
        final Map<Integer, List<Integer>> rows = new LinkedHashMap<>();
        for (final int slot : autoSlots) {
            rows.computeIfAbsent(slot / 9, r -> new ArrayList<>()).add(slot);
        }
        final List<Integer> out = new ArrayList<>();
        int remaining = Math.min(count, autoSlots.size());
        for (final List<Integer> row : rows.values()) {
            if (remaining <= 0) {
                break;
            }
            final int take = Math.min(remaining, row.size());
            final int offset = (row.size() - take) / 2;
            out.addAll(row.subList(offset, offset + take));
            remaining -= take;
        }
        return out;
    }
}
