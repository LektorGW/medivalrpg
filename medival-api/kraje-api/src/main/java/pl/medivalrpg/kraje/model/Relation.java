// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

/**
 * Diplomatic relation between two countries (always symmetric).
 */
public enum Relation {
    NEUTRAL("neutralne"),
    ALLIANCE("sojusz"),
    WAR("wojna");

    private final String displayName;

    Relation(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
