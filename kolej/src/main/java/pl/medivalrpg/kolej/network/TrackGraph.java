// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import pl.medivalrpg.kolej.geom.Vec3;

/**
 * The railway network: nodes (stations, junctions) and segments. Routing honours junction geometry: a train passing
 * a node may only continue on a segment whose leaving direction turns by at most {@link #maxTurnDegrees()} from the
 * arriving direction (no reversing on a junction, no impossible sharp switches). Not thread-safe; main thread only.
 */
public final class TrackGraph {

    private final Map<String, TrackNode> nodes = new LinkedHashMap<>();
    private final Map<String, TrackSegment> segments = new LinkedHashMap<>();
    private double maxTurnDegrees = 75;

    public double maxTurnDegrees() {
        return this.maxTurnDegrees;
    }

    public void maxTurnDegrees(final double deg) {
        this.maxTurnDegrees = deg;
    }

    // ------------------------------------------------------------------ nodes & segments

    public void putNode(final TrackNode node) {
        this.nodes.put(node.id(), node);
    }

    public TrackNode node(final String id) {
        return this.nodes.get(id);
    }

    public Collection<TrackNode> nodes() {
        return this.nodes.values();
    }

    public Optional<TrackNode> nodeByName(final String name) {
        for (final TrackNode n : this.nodes.values()) {
            if (n.name().equalsIgnoreCase(name) || n.id().equalsIgnoreCase(name)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    public void removeNode(final String id) {
        this.nodes.remove(id);
        this.segments.values().removeIf(s -> s.touches(id));
    }

    public void putSegment(final TrackSegment segment) {
        if (!this.nodes.containsKey(segment.from()) || !this.nodes.containsKey(segment.to())) {
            throw new IllegalArgumentException("segment endpoints must exist");
        }
        this.segments.put(segment.id(), segment);
    }

    public TrackSegment segment(final String id) {
        return this.segments.get(id);
    }

    public Collection<TrackSegment> segments() {
        return this.segments.values();
    }

    public void removeSegment(final String id) {
        this.segments.remove(id);
    }

    /** Segments touching {@code node}. */
    public List<TrackSegment> segmentsAt(final String node) {
        final List<TrackSegment> out = new ArrayList<>();
        for (final TrackSegment s : this.segments.values()) {
            if (s.touches(node)) {
                out.add(s);
            }
        }
        return out;
    }

    /** First free id with the given prefix ({@code s1, s2...}). */
    public String nextId(final String prefix) {
        int i = 1;
        while (this.nodes.containsKey(prefix + i) || this.segments.containsKey(prefix + i)) {
            i++;
        }
        return prefix + i;
    }

    // ------------------------------------------------------------------ routing

    /** True when a train arriving at {@code node} on {@code in} may leave on {@code out}. */
    public boolean canPass(final String node, final TrackSegment in, final TrackSegment out) {
        if (in == null) {
            return true;
        }
        if (in.id().equals(out.id())) {
            return false;
        }
        final Vec3 arriving = in.directionFrom(node).scale(-1);
        final Vec3 leaving = out.directionFrom(node);
        final double dot = arriving.x() * leaving.x() + arriving.z() * leaving.z();
        final double la = Math.hypot(arriving.x(), arriving.z());
        final double lb = Math.hypot(leaving.x(), leaving.z());
        if (la < 1e-9 || lb < 1e-9) {
            return true;
        }
        final double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / (la * lb)))));
        return angle <= this.maxTurnDegrees + 1e-6;
    }

    private record State(String node, String via) {
    }

    private record Entry(State state, double cost) {
    }

    /**
     * Shortest route from {@code start} to {@code goal} over segments accepted by {@code usable}
     * (e.g. {@link TrackSegment#isBuilt()}), obeying junction geometry. A route may leave the start node in either
     * direction (the train reverses at stations). Empty when unreachable or {@code start == goal}.
     */
    public Optional<Route> route(final String start, final String goal, final Predicate<TrackSegment> usable) {
        if (start.equals(goal) || !this.nodes.containsKey(start) || !this.nodes.containsKey(goal)) {
            return Optional.empty();
        }
        final Map<State, Double> dist = new HashMap<>();
        final Map<State, State> prev = new HashMap<>();
        final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        final State origin = new State(start, null);
        dist.put(origin, 0.0);
        queue.add(new Entry(origin, 0));
        State reached = null;
        while (!queue.isEmpty()) {
            final Entry e = queue.poll();
            if (e.cost() > dist.getOrDefault(e.state(), Double.MAX_VALUE)) {
                continue;
            }
            if (e.state().node().equals(goal)) {
                reached = e.state();
                break;
            }
            final TrackSegment in = e.state().via() == null ? null : this.segments.get(e.state().via());
            for (final TrackSegment out : this.segmentsAt(e.state().node())) {
                if (!usable.test(out) || out.cells().size() < 2 || !this.canPass(e.state().node(), in, out)) {
                    continue;
                }
                final State next = new State(out.other(e.state().node()), out.id());
                final double cost = e.cost() + out.length();
                if (cost < dist.getOrDefault(next, Double.MAX_VALUE)) {
                    dist.put(next, cost);
                    prev.put(next, e.state());
                    queue.add(new Entry(next, cost));
                }
            }
        }
        if (reached == null) {
            return Optional.empty();
        }
        final List<String> nodeList = new ArrayList<>();
        final List<String> segList = new ArrayList<>();
        for (State s = reached; s != null; s = prev.get(s)) {
            nodeList.addFirst(s.node());
            if (s.via() != null) {
                segList.addFirst(s.via());
            }
        }
        return Optional.of(Route.of(this, nodeList, segList));
    }

    /**
     * Route visiting {@code stops} in order (each hop is a shortest legal route; consecutive hops must also be legal at
     * the shared stop unless {@code reverseAtStops}). Empty if any hop is unreachable.
     */
    public Optional<Route> routeThrough(final List<String> stops, final Predicate<TrackSegment> usable, final boolean reverseAtStops) {
        if (stops.size() < 2) {
            return Optional.empty();
        }
        final List<String> nodeList = new ArrayList<>();
        final List<String> segList = new ArrayList<>();
        nodeList.add(stops.getFirst());
        for (int i = 0; i + 1 < stops.size(); i++) {
            final Optional<Route> hop = this.route(stops.get(i), stops.get(i + 1), usable);
            if (hop.isEmpty()) {
                return Optional.empty();
            }
            final List<String> hopSegs = hop.get().segmentIds();
            if (!reverseAtStops && !segList.isEmpty()) {
                final TrackSegment in = this.segments.get(segList.getLast());
                final TrackSegment out = this.segments.get(hopSegs.getFirst());
                if (!this.canPass(stops.get(i), in, out)) {
                    return Optional.empty();
                }
            }
            segList.addAll(hopSegs);
            nodeList.addAll(hop.get().nodes().subList(1, hop.get().nodes().size()));
        }
        return Optional.of(Route.of(this, nodeList, segList));
    }

    /** Nodes reachable from {@code start} over usable segments (ignoring junction geometry). */
    public java.util.Set<String> connected(final String start, final Predicate<TrackSegment> usable) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        todo.add(start);
        seen.add(start);
        while (!todo.isEmpty()) {
            final String n = todo.poll();
            for (final TrackSegment s : this.segmentsAt(n)) {
                if (usable.test(s) && seen.add(s.other(n))) {
                    todo.add(s.other(n));
                }
            }
        }
        return seen;
    }
}
