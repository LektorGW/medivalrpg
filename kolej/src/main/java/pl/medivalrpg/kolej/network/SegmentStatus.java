// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

/** Life cycle of a segment. */
public enum SegmentStatus {
    /** Drawn by the manager, terrain survey pending. */
    PLANNED("zaplanowany"),
    /** Route surveyed (cells known), waiting for construction. */
    SURVEYED("wytyczony"),
    /** Crews are building it. */
    BUILDING("w budowie"),
    /** Finished — trains may run. */
    BUILT("gotowy");

    private final String polish;

    SegmentStatus(final String polish) {
        this.polish = polish;
    }

    public String polish() {
        return this.polish;
    }
}
