// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

/** Which siege an API caller (e.g. the NPC AI through {@code KrajeApi.startSiege}) starts. Pure. */
public final class SiegeMode {

    private SiegeMode() {
    }

    /**
     * A landing when the chunk does not border the attacker's land, landings are enabled and normal sieges need
     * adjacency. Otherwise the normal siege (with its usual NOT_ADJACENT refusal when applicable).
     */
    public static boolean landing(final boolean adjacent, final boolean landingEnabled, final boolean requireAdjacent) {
        return !adjacent && landingEnabled && requireAdjacent;
    }
}
