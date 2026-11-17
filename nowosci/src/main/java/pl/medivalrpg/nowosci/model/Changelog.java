// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Immutable list of entries, newest (highest id) first, plus the next free id. The next id never goes down,
 * so an id is never reused after an entry is deleted (players who saw the deleted entry still see the new one).
 */
public final class Changelog {

    private static final Changelog EMPTY = new Changelog(List.of(), 1);

    private final List<Entry> newestFirst;
    private final int nextId;

    public Changelog(final List<Entry> entries, final int nextId) {
        final List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::id).reversed());
        this.newestFirst = List.copyOf(sorted);
        final int maxId = this.newestFirst.isEmpty() ? 0 : this.newestFirst.getFirst().id();
        this.nextId = Math.max(nextId, maxId + 1);
    }

    public static Changelog empty() {
        return EMPTY;
    }

    /** All entries, newest first. */
    public List<Entry> entries() {
        return this.newestFirst;
    }

    public boolean isEmpty() {
        return this.newestFirst.isEmpty();
    }

    public int size() {
        return this.newestFirst.size();
    }

    public int nextId() {
        return this.nextId;
    }

    /** Highest id present, 0 when empty. */
    public int maxId() {
        return this.newestFirst.isEmpty() ? 0 : this.newestFirst.getFirst().id();
    }

    public Optional<Entry> newest() {
        return this.newestFirst.isEmpty() ? Optional.empty() : Optional.of(this.newestFirst.getFirst());
    }

    public Optional<Entry> byId(final int id) {
        return this.newestFirst.stream().filter(e -> e.id() == id).findFirst();
    }

    /** Entries with an id greater than {@code lastSeenId}, newest first. */
    public List<Entry> newerThan(final int lastSeenId) {
        return this.newestFirst.stream().filter(e -> e.id() > lastSeenId).toList();
    }

    /** New changelog with {@code entry} added (its id should be {@link #nextId()}). */
    public Changelog with(final Entry entry) {
        final List<Entry> copy = new ArrayList<>(this.newestFirst);
        copy.removeIf(e -> e.id() == entry.id());
        copy.add(entry);
        return new Changelog(copy, Math.max(this.nextId, entry.id() + 1));
    }

    public Changelog without(final int id) {
        final List<Entry> copy = new ArrayList<>(this.newestFirst);
        copy.removeIf(e -> e.id() == id);
        return new Changelog(copy, this.nextId);
    }
}
