// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

/**
 * How good the relation of two countries is, from worst to best (see {@link KrajeApi#getRelationQuality}). Other
 * plugins (e.g. roads, trade) can require a minimum quality with {@link #atLeast}. Since API 1.5.
 */
public enum RelationQuality {
    /** The countries are at war. */
    WAR,
    /** No special relation (peace). */
    NEUTRAL,
    /** One is the vassal of the other (and they are not allied). */
    VASSALAGE,
    /** Alliance. */
    ALLIANCE,
    /** Personal union: one shared ruler. */
    UNION,
    /** The same country. */
    SAME;

    public boolean atLeast(final RelationQuality other) {
        return this.ordinal() >= other.ordinal();
    }

    /** Peace or better (not at war). */
    public boolean peaceful() {
        return this != WAR;
    }
}
