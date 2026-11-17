// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import pl.medivalrpg.kolej.geom.Vec3;

/**
 * A node of the track graph: a station or a junction at rail block {@code (x, y, z)}.
 *
 * @param axis     for stations: platform axis 0 = north/south (Z), 1 = east/west (X); -1 for junctions
 * @param capacity number of trains that may stand at the node at once (station tracks); junctions 1 (pass only)
 */
public record TrackNode(String id, String name, NodeType type, String world, int x, int y, int z, int axis, int capacity) {

    public Vec3 point() {
        return new Vec3(this.x + 0.5, this.y + TrackSegment.RAIL_TOP, this.z + 0.5);
    }

    public boolean isStation() {
        return this.type == NodeType.STATION;
    }

    public boolean isDepot() {
        return this.type == NodeType.DEPOT;
    }

    /** Stations and depots: trains may stop and wait there (signalling waiting points, have tracks/capacity). */
    public boolean isWaitingPoint() {
        return this.type != NodeType.JUNCTION;
    }

    public TrackNode withCapacity(final int newCapacity) {
        return new TrackNode(this.id, this.name, this.type, this.world, this.x, this.y, this.z, this.axis, newCapacity);
    }
}
