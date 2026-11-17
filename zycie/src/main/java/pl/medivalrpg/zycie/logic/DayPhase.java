// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.Locale;
import java.util.Optional;

/** Part of a Minecraft day as seen by the citizens' daily plan. {@link #key()} is the Polish config id. */
public enum DayPhase {
    MORNING("rano"),
    WORK("praca"),
    NOON("poludnie"),
    MEET("spotkanie"),
    EVENING("wieczor"),
    NIGHT("noc"),
    /** Sunday morning: mass in the church. */
    MASS("msza");

    private final String key;

    DayPhase(final String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public static Optional<DayPhase> byKey(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final String k = Texts.normalize(key);
        for (final DayPhase p : values()) {
            if (p.key.equals(k) || p.name().toLowerCase(Locale.ROOT).equals(k)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
