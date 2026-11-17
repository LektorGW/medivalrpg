// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeping a construction site dry (pure, unit tested). The work region is the building's footprint plus a margin;
 * every liquid cell just outside the region (sides and below — never above) becomes a dam block, then the liquid
 * inside the region is removed: inside the footprint and above the ground level it becomes air (the excavation and
 * the building fill it as planned), in the margin below the ground level it is filled with earth (no pits in the
 * plot). Water the template itself places (a well, a fountain) is kept.
 */
public final class Drainage {

    /** What is at a position. */
    @FunctionalInterface
    public interface Blocks {
        /** {@link #SOLID}, {@link #LIQUID} (water, lava, water plants), {@link #WATERLOGGED} or {@link #OPEN}. */
        int at(int x, int y, int z);
    }

    /** Liquid the building wants to keep (template water). */
    @FunctionalInterface
    public interface Keep {
        boolean keep(int x, int y, int z);
    }

    public static final int SOLID = 0;
    public static final int LIQUID = 1;
    public static final int OPEN = 2;
    /** A waterlogged block: dried inside the region, never replaced by a dam outside. */
    public static final int WATERLOGGED = 3;

    /** Operation types: dam block outside, air inside, earth in the margin below the ground. */
    public enum Type { DAM, DRY, FILL }

    public record Op(Type type, int x, int y, int z) {
    }

    /** Region of a site: footprint + margin, from the lowest template layer to the top. */
    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int fMinX, int fMinZ, int fMaxX, int fMaxZ,
                         int groundY) {

        public static Region of(final int fMinX, final int minY, final int fMinZ, final int fMaxX, final int maxY, final int fMaxZ,
                                final int margin, final int groundY) {
            return new Region(fMinX - margin, minY, fMinZ - margin, fMaxX + margin, maxY, fMaxZ + margin, fMinX, fMinZ, fMaxX, fMaxZ,
                groundY);
        }

        public boolean contains(final int x, final int y, final int z) {
            return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
        }

        boolean footprint(final int x, final int z) {
            return x >= this.fMinX && x <= this.fMaxX && z >= this.fMinZ && z <= this.fMaxZ;
        }
    }

    private Drainage() {
    }

    /** The operations (dams first, then drying top-down and filling bottom-up); empty when the site is dry. */
    public static List<Op> plan(final Blocks w, final Region r, final Keep keep) {
        final List<Op> dams = new ArrayList<>();
        final List<Op> inside = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        for (int y = r.minY(); y <= r.maxY(); y++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                for (int x = r.minX(); x <= r.maxX(); x++) {
                    final boolean edge = x == r.minX() || x == r.maxX() || z == r.minZ() || z == r.maxZ() || y == r.minY();
                    final int here = w.at(x, y, z);
                    if ((here == LIQUID || here == WATERLOGGED) && !keep.keep(x, y, z)) {
                        inside.add(new Op(r.footprint(x, z) || y > r.groundY() ? Type.DRY : Type.FILL, x, y, z));
                    }
                    if (!edge) {
                        continue;
                    }
                    for (final int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}}) {
                        final int nx = x + d[0];
                        final int ny = y + d[1];
                        final int nz = z + d[2];
                        if (r.contains(nx, ny, nz) || !seen.add(MineDigger.key(nx, ny, nz))) {
                            continue;
                        }
                        if (w.at(nx, ny, nz) == LIQUID) {
                            dams.add(new Op(Type.DAM, nx, ny, nz));
                        }
                    }
                }
            }
        }
        final List<Op> out = new ArrayList<>(dams);
        inside.sort(Comparator.comparingInt(o -> o.type() == Type.DRY ? -o.y() : 10_000 + o.y()));
        out.addAll(inside);
        return out;
    }

    /**
     * Water next to a site: the highest liquid surface within {@code margin} blocks of the footprint, or
     * {@link TerrainView#UNKNOWN} when there is none (for site choice).
     */
    public static int waterLevelNear(final TerrainView view, final int minX, final int minZ, final int maxX, final int maxZ,
                                     final int margin) {
        int best = TerrainView.UNKNOWN;
        for (int z = minZ - margin; z <= maxZ + margin; z++) {
            for (int x = minX - margin; x <= maxX + margin; x++) {
                if (view.liquid(x, z)) {
                    best = Math.max(best, view.height(x, z));
                }
            }
        }
        return best;
    }
}
