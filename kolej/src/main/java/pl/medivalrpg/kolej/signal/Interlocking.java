// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import pl.medivalrpg.kolej.network.Route;
import pl.medivalrpg.kolej.network.TrackNode;

/**
 * Block signalling and interlocking (pure, main thread). Guarantees that trains never collide and that single-track
 * lines cannot deadlock:
 *
 * <ul>
 *   <li><b>Sections</b>: the route between two waiting points (stations, or the route end) is one section. A train may
 *   only leave a waiting point when it can lock, atomically, every segment of the next section in its direction of
 *   travel (no train running the other way), every junction inside it (one train at a time) and a free track at the
 *   next station (station capacity). Opposite trains therefore always meet at stations.</li>
 *   <li><b>Blocks</b>: segments are cut into blocks of {@code blockLength} metres. Trains running the same way in a
 *   section follow each other block by block; a block belongs to at most one train.</li>
 *   <li><b>Movement authority</b>: {@link #update} returns how far the head of a train may advance (route position);
 *   the driver (automatic or supervised human) must be able to stop before it.</li>
 * </ul>
 *
 * Claims are recomputed from scratch on every update of a train (release all, then re-claim what it occupies, what it
 * has committed to and what it can newly acquire), so the state is always consistent with the train positions.
 */
public final class Interlocking {

    /** Why the authority ends where it does. */
    public enum Limit { CLEAR, END_OF_ROUTE, SECTION_OCCUPIED, STATION_FULL, TRAIN_AHEAD }

    public record Authority(double limit, Limit reason) {
    }

    private record Claims(Set<String> segments, Set<String> blocks, Set<String> nodes) {
        Claims() {
            this(new HashSet<>(), new HashSet<>(), new HashSet<>());
        }
    }

    private final Function<String, TrackNode> nodes;
    private final double blockLength;
    private final double stationZone;
    private final double junctionZone;

    private final Map<String, Map<String, Boolean>> segmentHolders = new HashMap<>();
    private final Map<String, String> blockOwner = new HashMap<>();
    private final Map<String, Set<String>> nodeHolders = new HashMap<>();
    private final Map<String, Claims> claims = new HashMap<>();
    private int conflicts;

    /**
     * @param nodes        node lookup
     * @param blockLength  block length (m)
     * @param stationZone  half length of a station zone (platform) around the node (m)
     * @param junctionZone half length of a junction zone (m)
     */
    public Interlocking(final Function<String, TrackNode> nodes, final double blockLength, final double stationZone, final double junctionZone) {
        this.nodes = nodes;
        this.blockLength = Math.max(10, blockLength);
        this.stationZone = stationZone;
        this.junctionZone = junctionZone;
    }

    /** Number of forced claims that overlapped another train (should stay 0; for diagnostics and tests). */
    public int conflicts() {
        return this.conflicts;
    }

    public double zone(final String nodeId) {
        final TrackNode n = this.nodes.apply(nodeId);
        return n != null && n.isWaitingPoint() ? this.stationZone : this.junctionZone;
    }

    private boolean waiting(final Route route, final int nodeIndex) {
        if (nodeIndex == route.nodes().size() - 1 || nodeIndex == 0) {
            return true;
        }
        final TrackNode n = this.nodes.apply(route.nodes().get(nodeIndex));
        return n != null && n.isWaitingPoint();
    }

    private int capacity(final String nodeId) {
        final TrackNode n = this.nodes.apply(nodeId);
        return n == null ? 1 : Math.max(1, n.isWaitingPoint() ? n.capacity() : 1);
    }

    /** Releases everything held by the train (removed, teleported, route changed...). */
    public void release(final String train) {
        final Claims c = this.claims.remove(train);
        if (c == null) {
            return;
        }
        for (final String s : c.segments()) {
            final Map<String, Boolean> h = this.segmentHolders.get(s);
            if (h != null) {
                h.remove(train);
                if (h.isEmpty()) {
                    this.segmentHolders.remove(s);
                }
            }
        }
        for (final String b : c.blocks()) {
            this.blockOwner.remove(b, train);
        }
        for (final String n : c.nodes()) {
            final Set<String> h = this.nodeHolders.get(n);
            if (h != null) {
                h.remove(train);
                if (h.isEmpty()) {
                    this.nodeHolders.remove(n);
                }
            }
        }
    }

    /**
     * Re-claims for a train and computes its movement authority.
     *
     * @param route     the train's route
     * @param head      route position of the front of the train
     * @param tail      route position of the rear ({@code head - train length})
     * @param lookahead how far ahead of the head the train wants authority (braking distance + margin)
     */
    public Authority update(final String train, final Route route, final double head, final double tail, final double lookahead) {
        this.release(train);
        final Claims c = new Claims();
        this.claims.put(train, c);
        final int nodeCount = route.nodes().size();
        if (route.legs().isEmpty()) {
            this.forceNode(train, c, route.firstNode());
            return new Authority(head, Limit.END_OF_ROUTE);
        }

        // ---- A: what the train physically occupies
        for (int i = 0; i < nodeCount; i++) {
            final double at = route.nodeOffset(i);
            final double z = this.zone(route.nodes().get(i));
            if (at - z <= head && at + z >= tail) {
                this.forceNode(train, c, route.nodes().get(i));
            }
        }
        for (int legIndex = 0; legIndex < route.legs().size(); legIndex++) {
            final Route.Leg leg = route.legs().get(legIndex);
            // Standing at the origin is not entry into the single-track section. A reservation by
            // an opposing train must not be force-overwritten merely because head == leg.start().
            final double entry = leg.start() + this.zone(route.nodes().get(legIndex));
            final double exit = leg.end() - this.zone(route.nodes().get(legIndex + 1));
            if (head > entry && tail < exit) {
                this.forceSegment(train, c, leg);
                this.forceBlocks(train, c, leg, Math.max(tail, leg.start()), Math.min(head, leg.end()));
            }
        }

        // ---- B: committed section (head already left the zone of the waiting point behind it)
        int sectionStart = 0; // node index of the waiting point at/behind the head
        for (int i = 0; i < nodeCount; i++) {
            if (route.nodeOffset(i) <= head + 1e-9 && this.waiting(route, i)) {
                sectionStart = i;
            }
        }
        double authority;
        int nextWaiting = this.nextWaiting(route, sectionStart);
        final boolean inZone = head <= route.nodeOffset(sectionStart) + this.zone(route.nodes().get(sectionStart));
        if (!inZone || sectionStart == nodeCount - 1) {
            if (sectionStart < nodeCount - 1) {
                this.claimSection(train, c, route, sectionStart, nextWaiting, true);
                authority = this.stopLimit(route, nextWaiting);
            } else {
                authority = route.length();
            }
        } else {
            authority = this.stopLimit(route, sectionStart);
            nextWaiting = sectionStart;
        }

        // ---- C: extension into following sections while the lookahead wants more
        Limit reason = Limit.CLEAR;
        int from = nextWaiting;
        while (head + lookahead > authority && from < nodeCount - 1) {
            final int to = this.nextWaiting(route, from);
            if (!this.claimSection(train, c, route, from, to, false)) {
                reason = this.sectionBlockedReason(train, route, from, to);
                break;
            }
            authority = this.stopLimit(route, to);
            from = to;
        }
        if (from >= nodeCount - 1 && reason == Limit.CLEAR) {
            authority = route.length();
            reason = Limit.END_OF_ROUTE;
        }

        // ---- D: blocks ahead of the head (trains running the same way)
        final double want = Math.min(authority, head + lookahead);
        for (final Route.Leg leg : route.legs()) {
            if (leg.end() < head || leg.start() > want) {
                continue;
            }
            final double blockLimit = this.claimBlocksAhead(train, c, leg, Math.max(head, leg.start()), want);
            if (blockLimit < authority) {
                authority = blockLimit;
                reason = Limit.TRAIN_AHEAD;
                break;
            }
        }
        return new Authority(Math.max(authority, head), reason);
    }

    /** Route position up to which the head may go when the train has to stop at waiting point {@code i}. */
    private double stopLimit(final Route route, final int i) {
        if (i >= route.nodes().size() - 1) {
            return route.length();
        }
        return route.nodeOffset(i) + this.zone(route.nodes().get(i));
    }

    private int nextWaiting(final Route route, final int from) {
        for (int i = from + 1; i < route.nodes().size(); i++) {
            if (this.waiting(route, i)) {
                return i;
            }
        }
        return route.nodes().size() - 1;
    }

    private Limit sectionBlockedReason(final String train, final Route route, final int from, final int to) {
        final String end = route.nodes().get(to);
        if (to < route.nodes().size() - 1 || this.nodes.apply(end) != null) {
            final Set<String> h = this.nodeHolders.getOrDefault(end, Set.of());
            final long others = h.stream().filter(t -> !t.equals(train)).count();
            if (others >= this.capacity(end)) {
                return Limit.STATION_FULL;
            }
        }
        return Limit.SECTION_OCCUPIED;
    }

    /** Claims legs {@code from..to-1}, the junctions in between and a track at node {@code to}. */
    private boolean claimSection(final String train, final Claims c, final Route route, final int from, final int to, final boolean force) {
        if (!force) {
            for (int i = from; i < to; i++) {
                final Route.Leg leg = route.legs().get(i);
                for (final Map.Entry<String, Boolean> e : this.segmentHolders.getOrDefault(leg.segmentId(), Map.of()).entrySet()) {
                    if (!e.getKey().equals(train) && e.getValue() != leg.forward()) {
                        return false;
                    }
                }
            }
            for (int i = from + 1; i < to; i++) {
                final String n = route.nodes().get(i);
                for (final String h : this.nodeHolders.getOrDefault(n, Set.of())) {
                    if (!h.equals(train)) {
                        return false;
                    }
                }
            }
            final String end = route.nodes().get(to);
            final Set<String> h = this.nodeHolders.getOrDefault(end, Set.of());
            final long others = h.stream().filter(t -> !t.equals(train)).count();
            if (!h.contains(train) && others >= this.capacity(end)) {
                return false;
            }
        }
        for (int i = from; i < to; i++) {
            this.forceSegment(train, c, route.legs().get(i));
        }
        for (int i = from + 1; i <= to; i++) {
            this.forceNode(train, c, route.nodes().get(i));
        }
        return true;
    }

    private void forceSegment(final String train, final Claims c, final Route.Leg leg) {
        final Map<String, Boolean> h = this.segmentHolders.computeIfAbsent(leg.segmentId(), k -> new HashMap<>());
        for (final Map.Entry<String, Boolean> e : h.entrySet()) {
            if (!e.getKey().equals(train) && e.getValue() != leg.forward()) {
                this.conflicts++;
            }
        }
        h.put(train, leg.forward());
        c.segments().add(leg.segmentId());
    }

    private void forceNode(final String train, final Claims c, final String node) {
        this.nodeHolders.computeIfAbsent(node, k -> new LinkedHashSet<>()).add(train);
        c.nodes().add(node);
    }

    private String blockId(final String segment, final int k) {
        return segment + "#" + k;
    }

    private void forceBlocks(final String train, final Claims c, final Route.Leg leg, final double from, final double to) {
        final double a = leg.toSegment(from);
        final double b = leg.toSegment(to);
        final int k0 = (int) Math.floor(Math.min(a, b) / this.blockLength);
        final int k1 = (int) Math.floor(Math.max(a, b) / this.blockLength);
        for (int k = k0; k <= k1; k++) {
            final String id = this.blockId(leg.segmentId(), k);
            final String owner = this.blockOwner.get(id);
            if (owner != null && !owner.equals(train)) {
                this.conflicts++;
                continue;
            }
            this.blockOwner.put(id, train);
            c.blocks().add(id);
        }
    }

    /**
     * Claims blocks of {@code leg} from route position {@code from} to {@code to} in travel order. Returns the route
     * position where another train's block starts (minus a small gap), or {@code Double.MAX_VALUE} when all were free.
     */
    private double claimBlocksAhead(final String train, final Claims c, final Route.Leg leg, final double from, final double to) {
        final double segLen = leg.length();
        final int kFrom = (int) Math.floor(leg.toSegment(from) / this.blockLength - (leg.forward() ? 0 : 1e-9));
        final int kTo = (int) Math.floor(Math.max(0, leg.toSegment(Math.min(to, leg.end()))) / this.blockLength);
        final List<Integer> order = new ArrayList<>();
        if (leg.forward()) {
            for (int k = kFrom; k <= kTo; k++) {
                order.add(k);
            }
        } else {
            for (int k = kFrom; k >= kTo; k--) {
                order.add(k);
            }
        }
        for (final int k : order) {
            final String id = this.blockId(leg.segmentId(), k);
            final String owner = this.blockOwner.get(id);
            if (owner != null && !owner.equals(train)) {
                final double blockStartSeg = leg.forward() ? k * this.blockLength : Math.min(segLen, (k + 1) * this.blockLength);
                final double routePos = leg.forward() ? leg.start() + blockStartSeg : leg.start() + (segLen - blockStartSeg);
                return routePos - 1.0;
            }
            this.blockOwner.put(id, train);
            c.blocks().add(id);
        }
        return Double.MAX_VALUE;
    }

    // ------------------------------------------------------------------ read

    /** Trains holding a node (station tracks / junction). */
    public Set<String> holders(final String node) {
        return Collections.unmodifiableSet(this.nodeHolders.getOrDefault(node, Set.of()));
    }

    /** Owner of block {@code k} of a segment, or null. */
    public String blockOwner(final String segment, final int k) {
        return this.blockOwner.get(this.blockId(segment, k));
    }

    /** Trains holding a segment with their direction (true = forward). */
    public Map<String, Boolean> segmentHolders(final String segment) {
        return Collections.unmodifiableMap(this.segmentHolders.getOrDefault(segment, Map.of()));
    }

    public double blockLength() {
        return this.blockLength;
    }
}
