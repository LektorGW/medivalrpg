// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.model;

/** What a soldier does when it has no target. */
public enum SoldierMode {
    /** Straż: stays within {@code radius} of its post. */
    GUARD("straż"),
    /** Patrol: walks between patrol points. */
    PATROL("patrol"),
    /** Oddział: follows its commander. */
    FOLLOW("za dowódcą"),
    /** Stój / idź tam: holds a position given by the commander. */
    HOLD("stoi");

    private final String displayName;

    SoldierMode(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
