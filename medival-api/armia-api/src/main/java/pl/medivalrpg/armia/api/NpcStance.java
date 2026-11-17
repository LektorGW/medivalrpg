// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.Locale;
import java.util.Optional;

/**
 * Attitude of an NPC country towards one other country, set by an admin / manager (web panel). The AI respects it:
 * {@link #NEVER_WAR} blocks every war declaration (also an admin one through {@link ArmiaApi#npcDeclareWar}),
 * {@link #PREFERRED_TARGET} strongly raises the target's weight for wars and sieges, {@link #FRIENDLY} makes alliances
 * easier and wars rarer, {@link #HOSTILE} the opposite. {@link #AUTO}: only the AI's own opinion counts.
 */
public enum NpcStance {
    AUTO("auto"),
    FRIENDLY("przyjazne"),
    HOSTILE("wrogie"),
    NEVER_WAR("nigdy_wojny"),
    PREFERRED_TARGET("cel_ataku");

    private final String polish;

    NpcStance(final String polish) {
        this.polish = polish;
    }

    /** Polish code (e.g. {@code nigdy_wojny}). */
    public String polish() {
        return this.polish;
    }

    /** Accepts the enum name ({@code NEVER_WAR}) or the Polish code ({@code nigdy_wojny}, also with {@code -}), any case. */
    public static Optional<NpcStance> parse(final String text) {
        if (text == null) {
            return Optional.empty();
        }
        final String s = text.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (final NpcStance v : values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(s) || v.polish.equals(s)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
}
