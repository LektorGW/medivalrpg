// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

/** Kind of a network node. */
public enum NodeType {
    /** Station (stacja): trains stop, passengers board, coal and water are stored. Waiting point for signalling. */
    STATION("stacja"),
    /** Junction (rozjazd): switches between segments; trains never wait on it. */
    JUNCTION("rozjazd"),
    /**
     * Company depot (baza przewoźnika): roundhouse with turntable, repair shop, coal stage and yard. A waiting point
     * like a station (capacity = parking tracks); trains go here for maintenance, refuelling and overnight parking.
     */
    DEPOT("baza");

    private final String polish;

    NodeType(final String polish) {
        this.polish = polish;
    }

    public String polish() {
        return this.polish;
    }
}
