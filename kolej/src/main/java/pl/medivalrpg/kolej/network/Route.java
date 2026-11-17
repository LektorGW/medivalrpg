// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.kolej.geom.Polyline;

/**
 * A path through the graph: {@code nodes.get(i)} → leg {@code i} → {@code nodes.get(i + 1)}. Route positions are arc
 * lengths along the concatenated leg paths, starting at 0 at the first node.
 */
public final class Route {

    /** One traversed segment; {@code forward} = walked from its {@code from} node to its {@code to} node. */
    public record Leg(String segmentId, boolean forward, double start, double length) {
        public double end() {
            return this.start + this.length;
        }

        /** Segment-local arc length (from the segment's {@code from} node) of route position {@code r}. */
        public double toSegment(final double r) {
            final double local = r - this.start;
            return this.forward ? local : this.length - local;
        }
    }

    private final List<String> nodes;
    private final List<Leg> legs;
    private final Polyline path;

    private Route(final List<String> nodes, final List<Leg> legs, final Polyline path) {
        this.nodes = List.copyOf(nodes);
        this.legs = List.copyOf(legs);
        this.path = path;
    }

    /** Builds a route from node ids and the traversed segments (looked up in {@code graph}). */
    public static Route of(final TrackGraph graph, final List<String> nodes, final List<String> segmentIds) {
        if (nodes.size() != segmentIds.size() + 1) {
            throw new IllegalArgumentException("nodes = segments + 1");
        }
        final List<Leg> legs = new ArrayList<>();
        final List<Polyline> parts = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < segmentIds.size(); i++) {
            final TrackSegment s = graph.segment(segmentIds.get(i));
            if (s == null) {
                throw new IllegalArgumentException("unknown segment " + segmentIds.get(i));
            }
            final boolean forward = s.from().equals(nodes.get(i));
            if (!forward && !s.to().equals(nodes.get(i))) {
                throw new IllegalArgumentException("segment " + s.id() + " does not touch " + nodes.get(i));
            }
            final Polyline p = forward ? s.path() : s.path().reversed();
            legs.add(new Leg(s.id(), forward, at, p.length()));
            parts.add(p);
            at += p.length();
        }
        final Polyline path = parts.isEmpty() ? new Polyline(List.of(graph.node(nodes.getFirst()).point()))
            : Polyline.concat(parts);
        return new Route(nodes, legs, path);
    }

    /** Route that only consists of standing at one node. */
    public static Route stay(final TrackGraph graph, final String node) {
        return of(graph, List.of(node), List.of());
    }

    public List<String> nodes() {
        return this.nodes;
    }

    public List<Leg> legs() {
        return this.legs;
    }

    public Polyline path() {
        return this.path;
    }

    public double length() {
        return this.legs.isEmpty() ? 0 : this.legs.getLast().end();
    }

    public String firstNode() {
        return this.nodes.getFirst();
    }

    public String lastNode() {
        return this.nodes.getLast();
    }

    /** Route position of node index {@code i}. */
    public double nodeOffset(final int i) {
        if (i <= 0) {
            return 0;
        }
        return this.legs.get(i - 1).end();
    }

    /** Index of the leg containing route position {@code r} (clamped). */
    public int legAt(final double r) {
        for (int i = 0; i < this.legs.size(); i++) {
            if (r < this.legs.get(i).end()) {
                return i;
            }
        }
        return this.legs.size() - 1;
    }

    /** Index of {@code node} in this route after (or at) node index {@code fromIndex}; -1 when absent. */
    public int indexOf(final String node, final int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < this.nodes.size(); i++) {
            if (this.nodes.get(i).equals(node)) {
                return i;
            }
        }
        return -1;
    }

    /** Segment ids in order. */
    public List<String> segmentIds() {
        return this.legs.stream().map(Leg::segmentId).toList();
    }
}
