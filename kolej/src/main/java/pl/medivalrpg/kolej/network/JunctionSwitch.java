// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Switch (zwrotnica) positions of junctions. The rail block on a junction cell can only connect two of its four
 * neighbours; when the interlocking sets a route over a junction, the rail is re-shaped to connect the arriving and
 * leaving segment. {@link #shapeFor} is the pure mapping to vanilla rail shapes.
 */
public final class JunctionSwitch {

    /** Horizontal neighbour direction of a cell. */
    public enum Side { NORTH, SOUTH, EAST, WEST }

    /** Current setting per junction node: arriving segment → leaving segment. */
    private final Map<String, String[]> settings = new HashMap<>();

    /** Records the setting; returns true when it changed (the rail block must be updated). */
    public boolean set(final String node, final String inSegment, final String outSegment) {
        final String[] old = this.settings.get(node);
        if (old != null && old[0].equals(inSegment) && old[1].equals(outSegment)) {
            return false;
        }
        this.settings.put(node, new String[] {inSegment, outSegment});
        return true;
    }

    public Optional<String[]> setting(final String node) {
        return Optional.ofNullable(this.settings.get(node));
    }

    /** Physical setting for an interior route junction. Sloped or malformed approaches fail closed. */
    public static Optional<String> routeShape(final TrackGraph graph, final Route route, final int index) {
        if (index <= 0 || index >= route.nodes().size() - 1) return Optional.empty();
        final TrackNode node = graph.node(route.nodes().get(index));
        if (node == null || node.type() != NodeType.JUNCTION) return Optional.empty();
        final TrackSegment incoming = graph.segment(route.legs().get(index - 1).segmentId());
        final TrackSegment outgoing = graph.segment(route.legs().get(index).segmentId());
        final Optional<Side> a = approach(node, incoming);
        final Optional<Side> b = approach(node, outgoing);
        return a.isPresent() && b.isPresent() ? shapeFor(a.get(), b.get()) : Optional.empty();
    }

    private static Optional<Side> approach(final TrackNode node, final TrackSegment segment) {
        if (segment == null || !segment.isBuilt() || segment.cells().size() < 2) return Optional.empty();
        final boolean first = segment.from().equals(node.id());
        if (!first && !segment.to().equals(node.id())) return Optional.empty();
        final int end = first ? 0 : segment.cells().size() - 1;
        final TrackCell cell = segment.cells().get(end);
        final TrackCell next = segment.cells().get(first ? 1 : end - 1);
        if (cell.x() != node.x() || cell.y() != node.y() || cell.z() != node.z()
            || next.y() != node.y()) return Optional.empty();
        return side(node.x(), node.z(), next.x(), next.z());
    }

    /** Neighbour side of {@code to} as seen from {@code from} (4-neighbours only). */
    public static Optional<Side> side(final int fromX, final int fromZ, final int toX, final int toZ) {
        final int dx = toX - fromX;
        final int dz = toZ - fromZ;
        if (dx == 0 && dz == -1) {
            return Optional.of(Side.NORTH);
        }
        if (dx == 0 && dz == 1) {
            return Optional.of(Side.SOUTH);
        }
        if (dx == 1 && dz == 0) {
            return Optional.of(Side.EAST);
        }
        if (dx == -1 && dz == 0) {
            return Optional.of(Side.WEST);
        }
        return Optional.empty();
    }

    /**
     * Vanilla rail shape name ({@code org.bukkit.block.data.Rail.Shape}) connecting the two sides; empty when the sides
     * are equal.
     */
    public static Optional<String> shapeFor(final Side a, final Side b) {
        if (a == b) {
            return Optional.empty();
        }
        final boolean ns = (a == Side.NORTH || a == Side.SOUTH) && (b == Side.NORTH || b == Side.SOUTH);
        final boolean ew = (a == Side.EAST || a == Side.WEST) && (b == Side.EAST || b == Side.WEST);
        if (ns) {
            return Optional.of("NORTH_SOUTH");
        }
        if (ew) {
            return Optional.of("EAST_WEST");
        }
        final Side vertical = a == Side.NORTH || a == Side.SOUTH ? a : b;
        final Side horizontal = vertical == a ? b : a;
        return Optional.of(vertical.name() + "_" + horizontal.name());
    }
}
