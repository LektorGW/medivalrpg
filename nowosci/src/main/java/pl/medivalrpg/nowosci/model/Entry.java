// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One changelog entry.
 *
 * @param id       unique, increasing; players see entries with an id greater than the last one they saw
 * @param date     date of the entry, {@code null} when missing or invalid in changelog.yml
 * @param title    plain text (never parsed as MiniMessage)
 * @param category category key ({@code nowe} / {@code zmiana} / {@code poprawka}, see config.yml)
 * @param points   MiniMessage strings (admin text)
 */
public record Entry(int id, LocalDate date, String title, String category, List<String> points) {

    public Entry {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(category, "category");
        points = List.copyOf(points);
    }

    public Entry withPoint(final String point) {
        final List<String> copy = new ArrayList<>(this.points);
        copy.add(point);
        return new Entry(this.id, this.date, this.title, this.category, copy);
    }
}
