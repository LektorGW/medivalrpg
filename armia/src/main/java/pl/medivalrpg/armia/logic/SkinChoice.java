// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Skin of an NPC body from the config value: empty / {@code losowy} = no Mojang skin (a profile with a stable random
 * UUID and no textures — the client shows one of its default skins for that UUID), one nick = that skin, several nicks
 * separated by commas = one of them, chosen stably by the entity's id. Pure.
 */
public final class SkinChoice {

    private SkinChoice() {
    }

    public static boolean isRandom(final String spec) {
        return names(spec).isEmpty();
    }

    /** Nicks of the spec ({@code losowy}/{@code random}/empty = none). */
    public static List<String> names(final String spec) {
        final List<String> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (final String part : spec.split("[,;\\s]+")) {
            final String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            final String l = p.toLowerCase(Locale.ROOT);
            if (l.equals("losowy") || l.equals("random") || l.equals("domyslny") || l.equals("domyślny")) {
                continue;
            }
            if (p.matches("[A-Za-z0-9_]{1,16}")) {
                out.add(p);
            }
        }
        return out;
    }

    /** The Mojang nick for an entity with this stable id; empty = use a random-UUID profile. */
    public static Optional<String> pick(final String spec, final UUID stableId) {
        final List<String> n = names(spec);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        final int i = stableId == null ? 0 : Math.floorMod(stableId.hashCode(), n.size());
        return Optional.of(n.get(i));
    }
}
