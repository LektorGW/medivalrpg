// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import pl.medivalrpg.armia.api.NpcLogCategory;

/** Bounded, thread-safe log of the last AI decisions of one country (newest last), with categories. */
public final class DecisionLog {

    public record Entry(long time, NpcLogCategory category, String text) {

        public Entry {
            category = category == null ? NpcLogCategory.OTHER : category;
            text = text == null ? "" : text;
        }

        /** Entry without a category: guessed from the text. */
        public Entry(final long time, final String text) {
            this(time, LogText.classify(text), text);
        }
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public DecisionLog(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(final long time, final String text) {
        this.add(new Entry(time, text));
    }

    public synchronized void add(final long time, final NpcLogCategory category, final String text) {
        this.add(new Entry(time, category, text));
    }

    private void add(final Entry e) {
        this.entries.addLast(e);
        while (this.entries.size() > this.capacity) {
            this.entries.removeFirst();
        }
    }

    /** The last {@code n} entries, oldest first. */
    public synchronized List<Entry> last(final int n) {
        final List<Entry> all = new ArrayList<>(this.entries);
        return List.copyOf(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
    }

    /** Entries matching the category (null = all) and the query, newest first, at most {@code limit}. */
    public synchronized List<Entry> find(final NpcLogCategory category, final String query, final int limit) {
        final List<Entry> out = new ArrayList<>();
        final Object[] arr = this.entries.toArray();
        for (int i = arr.length - 1; i >= 0 && out.size() < limit; i--) {
            final Entry e = (Entry) arr[i];
            if ((category == null || e.category() == category) && LogText.matches(e.text(), query)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized int size() {
        return this.entries.size();
    }
}
