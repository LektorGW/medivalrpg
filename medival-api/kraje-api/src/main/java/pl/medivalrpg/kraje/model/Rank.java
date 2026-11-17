// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

/**
 * Member rank inside a country. Higher {@link #weight()} means more power.
 */
public enum Rank {
    CITIZEN("Obywatel", 1),
    ADVISOR("Doradca", 2),
    RULER("Władca", 3);

    private final String displayName;
    private final int weight;

    Rank(final String displayName, final int weight) {
        this.displayName = displayName;
        this.weight = weight;
    }

    public String displayName() {
        return this.displayName;
    }

    public int weight() {
        return this.weight;
    }

    public boolean atLeast(final Rank other) {
        return this.weight >= other.weight;
    }
}
