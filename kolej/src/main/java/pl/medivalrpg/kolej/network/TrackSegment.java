// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.kolej.geom.Polyline;
import pl.medivalrpg.kolej.geom.Vec3;

/**
 * Track between two nodes. {@link #cells()} run from {@link #from()} to {@link #to()}; the first cell is the
 * {@code from} node cell and the last one the {@code to} node cell. The smooth train path is derived from the cells.
 */
public final class TrackSegment {

    /** Height of the rail surface above the rail block's bottom. */
    public static final double RAIL_TOP = 0.0625;

    private final String id;
    private final String from;
    private final String to;
    private final List<Vec3> via = new ArrayList<>();
    private List<TrackCell> cells = List.of();
    private SegmentStatus status = SegmentStatus.PLANNED;
    private double speedLimit;
    private transient Polyline path;

    public TrackSegment(final String id, final String from, final String to) {
        this.id = id;
        this.from = from;
        this.to = to;
    }

    public String id() {
        return this.id;
    }

    public String from() {
        return this.from;
    }

    public String to() {
        return this.to;
    }

    public String other(final String node) {
        return this.from.equals(node) ? this.to : this.from;
    }

    public boolean touches(final String node) {
        return this.from.equals(node) || this.to.equals(node);
    }

    /** Manager's via points (only x and z matter) that steer the survey. */
    public List<Vec3> via() {
        return this.via;
    }

    public List<TrackCell> cells() {
        return this.cells;
    }

    public void cells(final List<TrackCell> newCells) {
        this.cells = List.copyOf(newCells);
        this.path = null;
    }

    public SegmentStatus status() {
        return this.status;
    }

    public void status(final SegmentStatus s) {
        this.status = s;
    }

    /** Speed limit in m/s; 0 = line default. */
    public double speedLimit() {
        return this.speedLimit;
    }

    public void speedLimit(final double v) {
        this.speedLimit = v;
    }

    public boolean isBuilt() {
        return this.status == SegmentStatus.BUILT;
    }

    /** Smooth path from {@code from} to {@code to} (rail surface), cached. */
    public Polyline path() {
        if (this.path == null) {
            this.path = pathOf(this.cells);
        }
        return this.path;
    }

    public double length() {
        return this.cells.size() < 2 ? 0 : this.path().length();
    }

    /** Rail-surface centre points of the cells (ascending rails at half height), smoothed by Chaikin corner cutting. */
    public static Polyline pathOf(final List<TrackCell> cells) {
        final List<Vec3> raw = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            final TrackCell c = cells.get(i);
            double y = c.y() + RAIL_TOP;
            if (ascending(cells, i)) {
                y += 0.5;
            }
            raw.add(new Vec3(c.x() + 0.5, y, c.z() + 0.5));
        }
        return new Polyline(Polyline.chaikin(raw, 2));
    }

    /** True when the rail of cell {@code i} is an ascending rail (a neighbour cell is one block higher). */
    public static boolean ascending(final List<TrackCell> cells, final int i) {
        final TrackCell c = cells.get(i);
        if (i > 0 && cells.get(i - 1).y() == c.y() + 1) {
            return true;
        }
        return i < cells.size() - 1 && cells.get(i + 1).y() == c.y() + 1;
    }

    /** Direction leaving {@code node} along this segment (unit vector). */
    public Vec3 directionFrom(final String node) {
        final Polyline p = this.path();
        final double probe = Math.min(4, p.length() / 2);
        if (node.equals(this.from)) {
            return p.pointAt(probe).sub(p.pointAt(0)).normalized();
        }
        return p.pointAt(p.length() - probe).sub(p.pointAt(p.length())).normalized();
    }
}
