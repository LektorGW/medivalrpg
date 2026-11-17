// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import java.util.Optional;
import pl.medivalrpg.turnieje.game.Texts;

/**
 * Equipment during fights.
 * <ul>
 *   <li>{@link #KIT} — the inventory is stored, cleared and replaced by the configured kit for each fight; the stored
 *       inventory comes back afterwards (kit items are PDC-marked and can never leave the event);</li>
 *   <li>{@link #OWN} — players fight in their own gear; nothing is stored or restored except position and health,
 *       nothing is dropped (consumed arrows/durability are a real cost).</li>
 * </ul>
 */
public enum GearMode {
    KIT("zestaw"),
    OWN("wlasny");

    private final String id;

    GearMode(final String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<GearMode> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String s = Texts.normalize(raw);
        return switch (s) {
            case "zestaw", "kit" -> Optional.of(KIT);
            case "wlasny", "own", "own-gear", "wlasne" -> Optional.of(OWN);
            default -> Optional.empty();
        };
    }
}
