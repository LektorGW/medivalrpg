// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ConstructionSite;

/** Full earthworks domain, including the neighbour probe outside the last ramp ring. */
public final class TerrainFootprint {
    public static final int MARGIN = 2;
    /** Earthworks planning version of a site (see {@link #refreshPending}). */
    public static final int VERSION = 2;
    /** Slope rings around the plot (8 since 2026-11-03: natural 1:1 embankments instead of retaining walls). */
    public static final int RAMP = 8;
    private TerrainFootprint() { }

    public static BlockBox expand(final BlockBox box, final int margin) {
        return new BlockBox(box.world(), box.minX() - margin, box.minY(), box.minZ() - margin,
            box.maxX() + margin, box.maxY(), box.maxZ() + margin);
    }

    public static BlockBox building(final BlockBox box) {
        return expand(box, MARGIN + RAMP + 1);
    }

    /**
     * Refresh only unfinished earthworks; never rewind foundations, walls or completed buildings. Version 2 (2026-11-03):
     * plots still in their earthworks are planned again with natural embankments instead of retaining walls.
     */
    public static boolean refreshPending(final ConstructionSite site) {
        if (!site.is(ConstructionSite.BUILD) || site.phase != 0 || site.terrainVersion >= VERSION) return false;
        site.terrain = null;
        site.cursor = 0;
        site.terrainVersion = VERSION;
        return true;
    }
}
