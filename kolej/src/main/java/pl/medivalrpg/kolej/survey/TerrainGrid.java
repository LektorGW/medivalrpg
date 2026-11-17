// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.survey;

/**
 * Read-only terrain for the route planner. Implementations must be thread-safe for reads (the planner runs off the
 * main thread on data captured from chunk snapshots).
 */
public interface TerrainGrid {

    /** Marker for "no water here". */
    int NO_WATER = Integer.MIN_VALUE;

    /** True when the column was sampled (unknown columns are never used by the planner). */
    boolean known(int x, int z);

    /** Y of the top solid ground block (leaves, logs and plants ignored); under water: the sea bed. */
    int ground(int x, int z);

    /** Y of the water surface block, or {@link #NO_WATER}. */
    int water(int x, int z);

    /** True when the crews may not work in this column (foreign land without permission, protected region...). */
    boolean blocked(int x, int z);
}
