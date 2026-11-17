// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

/** How a track cell is carried over the terrain. */
public enum CellKind {
    /** Track laid on the existing ground (ground top replaced by the bed). */
    GROUND("teren"),
    /** Track on an embankment (nasyp) filled up from the ground. */
    EMBANKMENT("nasyp"),
    /** Track in a cutting (przekop): terrain above the track removed. */
    CUTTING("przekop"),
    /** Bridge or viaduct over water or a deep valley. */
    BRIDGE("most"),
    /** Short tunnel: terrain kept above a lined bore. */
    TUNNEL("tunel"),
    /** Station platform track (straight, part of a station). */
    PLATFORM("peron");

    private final String polish;

    CellKind(final String polish) {
        this.polish = polish;
    }

    public String polish() {
        return this.polish;
    }
}
