// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.Locale;
import java.util.Optional;

/**
 * Military posture of an NPC country. {@link #DEFENSIVE}: never starts wars, more soldiers guard the border;
 * {@link #OFFENSIVE}: wars and sieges more often; {@link #BALANCED}: the personality decides alone.
 */
public enum NpcPosture {
    DEFENSIVE("obronna"),
    BALANCED("zrownowazona"),
    OFFENSIVE("ofensywna");

    private final String polish;

    NpcPosture(final String polish) {
        this.polish = polish;
    }

    /** Polish code (e.g. {@code obronna}). */
    public String polish() {
        return this.polish;
    }

    /** Accepts the enum name or the Polish code (also {@code zrównoważona}), any case. */
    public static Optional<NpcPosture> parse(final String text) {
        if (text == null) {
            return Optional.empty();
        }
        final String s = text.trim().toLowerCase(Locale.ROOT).replace('ó', 'o').replace('ż', 'z').replace('ę', 'e');
        for (final NpcPosture v : values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(s) || v.polish.equals(s)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
}
