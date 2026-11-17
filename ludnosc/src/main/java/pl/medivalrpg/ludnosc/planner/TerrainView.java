// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

/**
 * Surface information for the planner (backed by chunk snapshots in game, by synthetic maps in tests).
 */
public interface TerrainView {

    int UNKNOWN = Integer.MIN_VALUE;

    /** y of the top solid ground block at a column, {@link #UNKNOWN} when not scanned yet. */
    int height(int x, int z);

    /** Water or lava at the surface. */
    boolean liquid(int x, int z);

    /** Something that must not be dug away (tree trunk, player building...). */
    boolean obstacle(int x, int z);

    /** The column belongs to the planning country. */
    boolean own(int x, int z);

    /** Bare rock at the surface (mountains: a good place for a mine). */
    default boolean stone(final int x, final int z) {
        return false;
    }

    /** Trees grow here (forest: a good place for a sawmill). */
    default boolean tree(final int x, final int z) {
        return false;
    }
}
