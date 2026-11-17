// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.Locale;
import java.util.Optional;

/** Kinds of quests an NPC ruler gives. Pure. */
public enum QuestType {
    /** Bring N items for the court to the ruler / capital. */
    DOSTAWA("dostawa", "Dostawa"),
    /** Bring resources the country lacks (from Ludnosc stores), to the ruler / capital. */
    SUROWCE("surowce", "Surowce"),
    /** Carry the ruler's sealed cargo to another country's capital within a time limit (overland). */
    TRANSPORT("transport", "Transport");

    private final String key;
    private final String displayName;

    QuestType(final String key, final String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return this.key;
    }

    public String displayName() {
        return this.displayName;
    }

    public static Optional<QuestType> byKey(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final String k = key.trim().toLowerCase(Locale.ROOT);
        for (final QuestType t : values()) {
            if (t.key.equals(k)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
