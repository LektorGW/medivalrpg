// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.Locale;
import java.util.Optional;

/** Category of an AI decision log entry (see {@link ArmiaApi#npcLog(java.util.UUID, String, String, int)}). */
public enum NpcLogCategory {
    WAR("wojna"),
    DIPLOMACY("dyplomacja"),
    BUILD("budowy"),
    ARMY("armia"),
    ECONOMY("gospodarka"),
    OTHER("inne");

    private final String polish;

    NpcLogCategory(final String polish) {
        this.polish = polish;
    }

    /** Polish name (filter chip). */
    public String polish() {
        return this.polish;
    }

    /** Accepts the enum name ({@code WAR}) or the Polish name ({@code wojna}), any case. */
    public static Optional<NpcLogCategory> parse(final String text) {
        if (text == null) {
            return Optional.empty();
        }
        final String s = text.trim().toLowerCase(Locale.ROOT);
        for (final NpcLogCategory v : values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(s) || v.polish.equals(s)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
}
