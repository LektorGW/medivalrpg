// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.geom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A 3D path with cumulative arc length. Positions outside {@code [0, length]} are extrapolated along the first/last
 * piece, so a train standing at the start of a route (body behind the first node) still gets sensible positions.
 */
public final class Polyline {

    private final Vec3[] points;
    private final double[] cumulative;

    public Polyline(final List<Vec3> points) {
        final List<Vec3> clean = new ArrayList<>(points.size());
        for (final Vec3 p : points) {
            if (clean.isEmpty() || clean.getLast().distance(p) > 1e-6) {
                clean.add(p);
            }
        }
        if (clean.isEmpty()) {
            clean.add(Vec3.ZERO);
        }
        if (clean.size() == 1) {
            clean.add(clean.getFirst().add(new Vec3(0, 0, 1e-3)));
        }
        this.points = clean.toArray(Vec3[]::new);
        this.cumulative = new double[this.points.length];
        for (int i = 1; i < this.points.length; i++) {
            this.cumulative[i] = this.cumulative[i - 1] + this.points[i].distance(this.points[i - 1]);
        }
    }

    public double length() {
        return this.cumulative[this.cumulative.length - 1];
    }

    public int size() {
        return this.points.length;
    }

    public Vec3 point(final int i) {
        return this.points[i];
    }

    public List<Vec3> points() {
        return List.of(this.points);
    }

    public Vec3 first() {
        return this.points[0];
    }

    public Vec3 last() {
        return this.points[this.points.length - 1];
    }

    /** Index i of the piece [i, i+1] containing arc length {@code s} (clamped). */
    private int piece(final double s) {
        if (s <= 0) {
            return 0;
        }
        if (s >= this.length()) {
            return this.points.length - 2;
        }
        int idx = Arrays.binarySearch(this.cumulative, s);
        if (idx < 0) {
            idx = -idx - 2;
        }
        return Math.max(0, Math.min(this.points.length - 2, idx));
    }

    /** Point at arc length {@code s}; extrapolated linearly beyond both ends. */
    public Vec3 pointAt(final double s) {
        final int i = this.piece(s);
        final Vec3 a = this.points[i];
        final Vec3 b = this.points[i + 1];
        final double len = this.cumulative[i + 1] - this.cumulative[i];
        final double t = len < 1e-9 ? 0 : (s - this.cumulative[i]) / len;
        return a.lerp(b, t);
    }

    /** Unit direction of travel around {@code s}, averaged over a small window to hide raster zigzags. */
    public Vec3 directionAt(final double s, final double window) {
        final Vec3 d = this.pointAt(s + window).sub(this.pointAt(s - window));
        return d.length() < 1e-9 ? this.points[this.piece(s) + 1].sub(this.points[this.piece(s)]).normalized() : d.normalized();
    }

    /** Gradient (rise over horizontal run) around {@code s}; positive = uphill in the direction of travel. */
    public double gradientAt(final double s, final double window) {
        final Vec3 d = this.pointAt(s + window).sub(this.pointAt(s - window));
        final double h = d.horizontalLength();
        return h < 1e-6 ? 0 : d.y() / h;
    }

    /** The same path walked backwards. */
    public Polyline reversed() {
        final List<Vec3> list = new ArrayList<>(List.of(this.points));
        java.util.Collections.reverse(list);
        return new Polyline(list);
    }

    /** Concatenation; joints closer than 1e-6 are merged. */
    public static Polyline concat(final List<Polyline> parts) {
        final List<Vec3> all = new ArrayList<>();
        for (final Polyline p : parts) {
            all.addAll(List.of(p.points));
        }
        return new Polyline(all);
    }

    /** Arc length of the point of this path closest to {@code q} (horizontal distance), and that distance. */
    public double[] project(final Vec3 q) {
        double best = Double.MAX_VALUE;
        double bestS = 0;
        for (int i = 0; i < this.points.length - 1; i++) {
            final Vec3 a = this.points[i];
            final Vec3 ab = this.points[i + 1].sub(a);
            final double len2 = ab.x() * ab.x() + ab.z() * ab.z();
            double t = len2 < 1e-12 ? 0 : ((q.x() - a.x()) * ab.x() + (q.z() - a.z()) * ab.z()) / len2;
            t = Math.max(0, Math.min(1, t));
            final double px = a.x() + ab.x() * t - q.x();
            final double pz = a.z() + ab.z() * t - q.z();
            final double d = Math.hypot(px, pz);
            if (d < best) {
                best = d;
                bestS = this.cumulative[i] + t * (this.cumulative[i + 1] - this.cumulative[i]);
            }
        }
        return new double[] {bestS, best};
    }

    /** Chaikin corner cutting ({@code iterations} times), keeping both end points. */
    public static List<Vec3> chaikin(final List<Vec3> in, final int iterations) {
        List<Vec3> cur = in;
        for (int it = 0; it < iterations && cur.size() > 2; it++) {
            final List<Vec3> next = new ArrayList<>(cur.size() * 2);
            next.add(cur.getFirst());
            for (int i = 0; i < cur.size() - 1; i++) {
                final Vec3 a = cur.get(i);
                final Vec3 b = cur.get(i + 1);
                next.add(a.lerp(b, 0.25));
                next.add(a.lerp(b, 0.75));
            }
            next.add(cur.getLast());
            cur = next;
        }
        return cur;
    }
}
