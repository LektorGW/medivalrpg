// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import java.util.Locale;

/** Kinds of Skarbiec NPCs; {@link #id()} is the Polish command argument and the stored value. */
public enum NpcType {
    BANKER("bankier"),
    MERCHANT("kupiec");

    private final String id;

    NpcType(final String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static NpcType byId(final String id) {
        if (id == null) {
            return null;
        }
        final String lower = id.toLowerCase(Locale.ROOT);
        for (final NpcType t : values()) {
            if (t.id.equals(lower) || t.name().toLowerCase(Locale.ROOT).equals(lower)) {
                return t;
            }
        }
        return null;
    }
}
