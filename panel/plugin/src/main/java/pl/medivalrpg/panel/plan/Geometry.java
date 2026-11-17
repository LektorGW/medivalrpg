// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

/** Planar helpers for plan polygons and polylines in block coordinates ({@code [x, z]} pairs). Pure. */
public final class Geometry {

    private Geometry() {
    }

    /** Signed area (shoelace). */
    public static double area(final int[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            final int[] a = p[i];
            final int[] b = p[(i + 1) % p.length];
            s += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return s / 2;
    }

    /** Point in polygon (even-odd rule). */
    public static boolean contains(final int[][] p, final double x, final double z) {
        boolean in = false;
        for (int i = 0, j = p.length - 1; i < p.length; j = i++) {
            final double xi = p[i][0];
            final double zi = p[i][1];
            final double xj = p[j][0];
            final double zj = p[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /** Distance from a point to a segment. */
    public static double distanceToSegment(final double px, final double pz, final double ax, final double az, final double bx, final double bz) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len;
        t = Math.max(0, Math.min(1, t));
        final double cx = ax + t * dx - px;
        final double cz = az + t * dz - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /** Distance from a point to a polyline (with the closing segment when {@code closed}). */
    public static double distanceToPolyline(final int[][] p, final boolean closed, final double x, final double z) {
        double best = Double.MAX_VALUE;
        final int n = closed ? p.length : p.length - 1;
        for (int i = 0; i < n; i++) {
            final int[] a = p[i];
            final int[] b = p[(i + 1) % p.length];
            best = Math.min(best, distanceToSegment(x, z, a[0], a[1], b[0], b[1]));
        }
        if (p.length == 1) {
            best = Math.hypot(x - p[0][0], z - p[0][1]);
        }
        return best;
    }

    private static long orient(final int[] a, final int[] b, final int[] c) {
        final long v = (long) (b[0] - a[0]) * (c[1] - a[1]) - (long) (b[1] - a[1]) * (c[0] - a[0]);
        return Long.signum(v);
    }

    /** Proper crossing of segments ab and cd (touching at shared endpoints does not count). */
    static boolean crosses(final int[] a, final int[] b, final int[] c, final int[] d) {
        final long o1 = orient(a, b, c);
        final long o2 = orient(a, b, d);
        final long o3 = orient(c, d, a);
        final long o4 = orient(c, d, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    /** Whether two non-adjacent edges of the closed polygon cross. O(n²), n ≤ 256. */
    public static boolean selfIntersects(final int[][] p) {
        final int n = p.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) {
                    continue;
                }
                if (crosses(p[i], p[(i + 1) % n], p[j], p[(j + 1) % n])) {
                    return true;
                }
            }
        }
        return false;
    }
}
