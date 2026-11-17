// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans the slope around the pasted spawn so there is no hanging foundation and no dirt wall at the edge.
 *
 * <p>For every column in a band of {@code width} blocks around the footprint, at Chebyshev distance {@code d}
 * (1 … width) from it, the terrain surface is clamped to {@code edge − d … edge + d}: lower ground is raised
 * (filled with dirt and grass), higher ground is cut down. The slope therefore changes by at most one block per
 * block when walking away from the wall, and columns already within that cone are left alone. Terrain differing
 * from the edge by more than the band can absorb still meets the band's outer ring with a step (a 4–6 block band
 * cannot hide a cliff). Pure.</p>
 */
public final class EdgeSmoother {

    public static final int MIN_WIDTH = 4;
    public static final int MAX_WIDTH = 6;

    /** Surface height of a column (y of the top ground block). */
    @FunctionalInterface
    public interface HeightMap {
        int surface(int x, int z);
    }

    /** One column to change: the top ground block goes from {@code from} to {@code to}. */
    public record Column(int x, int z, int from, int to) {
        public boolean raise() {
            return this.to > this.from;
        }
    }

    private EdgeSmoother() {
    }

    public static int clampWidth(final int width) {
        return Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    }

    /** Target surface of a column {@code distance} blocks from the edge. */
    public static int target(final int original, final int edge, final int distance) {
        return Math.clamp(original, edge - distance, edge + distance);
    }

    /**
     * @param footprint schematic footprint (only X/Z are used)
     * @param edge      ground level of the schematic edge
     * @param width     band width (clamped to 4…6)
     */
    public static List<Column> plan(final Box footprint, final int edge, final int width, final HeightMap heights) {
        final int w = clampWidth(width);
        final List<Column> out = new ArrayList<>();
        for (int x = footprint.minX() - w; x <= footprint.maxX() + w; x++) {
            for (int z = footprint.minZ() - w; z <= footprint.maxZ() + w; z++) {
                final int d = footprint.columnDistance(x, z);
                if (d < 1 || d > w) {
                    continue;
                }
                final int original = heights.surface(x, z);
                final int to = target(original, edge, d);
                if (to != original) {
                    out.add(new Column(x, z, original, to));
                }
            }
        }
        return out;
    }
}
