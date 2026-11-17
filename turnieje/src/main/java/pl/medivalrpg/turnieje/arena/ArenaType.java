// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import java.util.Optional;
import pl.medivalrpg.turnieje.game.Texts;

/** What an arena is for. The id is the Polish word used in commands and files. */
public enum ArenaType {
    /** 1v1 duels between two players ({@code /turniej wyzwij}). */
    POJEDYNEK("pojedynek"),
    /** Knight tournament: a bracket of 1v1 rounds, optionally mounted (joust). */
    TURNIEJ("turniej"),
    /** Free-for-all melee: everybody at once, last one standing. */
    MELEE("melee");

    private final String id;

    ArenaType(final String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /** Arenas that can host a tournament (sign-ups, prizes). */
    public boolean hostsTournaments() {
        return this != POJEDYNEK;
    }

    public static Optional<ArenaType> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String s = Texts.normalize(raw);
        for (final ArenaType t : values()) {
            if (t.id.equals(s) || t.name().toLowerCase(java.util.Locale.ROOT).equals(s)) {
                return Optional.of(t);
            }
        }
        return switch (s) {
            case "duel", "1v1" -> Optional.of(POJEDYNEK);
            case "turniej-rycerski", "rycerski", "tournament", "joust" -> Optional.of(TURNIEJ);
            case "ffa", "free-for-all", "bitwa" -> Optional.of(MELEE);
            default -> Optional.empty();
        };
    }
}
