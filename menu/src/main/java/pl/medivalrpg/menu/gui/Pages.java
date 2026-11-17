// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.List;

/** Splits a list into pages of the menu's content slots. Pure. */
public final class Pages {

    /**
     * @param items items of this page
     * @param index 0-based page index (clamped into range)
     * @param pages page count, at least 1 (an empty list has one empty page)
     */
    public record Page<T>(List<T> items, int index, int pages) {

        public boolean hasPrevious() {
            return this.index > 0;
        }

        public boolean hasNext() {
            return this.index < this.pages - 1;
        }

        /** Is {@code index + delta} an existing page? */
        public boolean canMove(final int delta) {
            final int target = this.index + delta;
            return delta != 0 && target >= 0 && target < this.pages;
        }
    }

    private Pages() {
    }

    /**
     * @param requested requested 0-based page; out of range → nearest existing page
     * @param size      items per page; {@code <= 0} → everything on one page of nothing (no content slots)
     */
    public static <T> Page<T> of(final List<T> all, final int requested, final int size) {
        if (size <= 0) {
            return new Page<>(List.of(), 0, 1);
        }
        final int pages = Math.max(1, (all.size() + size - 1) / size);
        final int index = Math.max(0, Math.min(requested, pages - 1));
        final int from = index * size;
        final int to = Math.min(all.size(), from + size);
        return new Page<>(List.copyOf(all.subList(Math.min(from, to), to)), index, pages);
    }
}
