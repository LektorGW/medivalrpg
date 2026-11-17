// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

/** Vanilla rail shapes for four-neighbour paths. Slopes require a straight horizontal alignment. */
public final class RailGeometry {
    private RailGeometry() { }
    public static String shape(int ax, int ay, int az, int x, int y, int z, int bx, int by, int bz) {
        int dxA = Integer.signum(ax - x), dzA = Integer.signum(az - z);
        int dxB = Integer.signum(bx - x), dzB = Integer.signum(bz - z);
        if (ay > y || by > y) {
            int dx = ay > y ? dxA : dxB, dz = ay > y ? dzA : dzB;
            if (dx != 0) return dx > 0 ? "ascending_east" : "ascending_west";
            return dz > 0 ? "ascending_south" : "ascending_north";
        }
        boolean east = dxA > 0 || dxB > 0, west = dxA < 0 || dxB < 0;
        boolean south = dzA > 0 || dzB > 0, north = dzA < 0 || dzB < 0;
        if (south && east) return "south_east";
        if (south && west) return "south_west";
        if (north && east) return "north_east";
        if (north && west) return "north_west";
        return east || west ? "east_west" : "north_south";
    }
}
