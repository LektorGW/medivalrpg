// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Merges a set of chunk squares into polygons (outer rings plus holes).
 *
 * <p>Algorithm: every chunk contributes the edges of its square that are not shared with a claimed
 * neighbour (shared edges cancel out). Edges are directed counter-clockwise around each chunk
 * (the x/z plane treated as x/y), so the claimed area is always on the left of an edge. Edges are
 * then chained into closed loops; at "pinch" vertices (two chunks touching only diagonally) the
 * left-most turn is taken, which keeps diagonal neighbours as separate rings and gives a
 * deterministic result without self-crossing rings. Loops with positive signed area are outer
 * rings, loops with negative area are holes; each hole is assigned to the smallest outer ring that
 * contains it (this also handles islands inside holes).</p>
 *
 * <p>All coordinates are in chunk-corner units: chunk (x, z) spans [x, x+1] x [z, z+1].
 * Multiply by 16 to get block coordinates.</p>
 */
public final class ChunkPolygons {

    /** Direction vectors: 0 = +x, 1 = +z, 2 = -x, 3 = -z (counter-clockwise order). */
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    private ChunkPolygons() {
    }

    /** A polygon corner in chunk-corner coordinates. */
    public record Vertex(int x, int z) {
    }

    /** One 4-connected piece of territory: an outer ring and zero or more holes. */
    public record Part(List<Vertex> outer, List<List<Vertex>> holes) {

        /** Area in chunks (outer area minus hole areas). */
        public long area() {
            long area2 = Math.abs(signedArea2(this.outer));
            for (final List<Vertex> hole : this.holes) {
                area2 -= Math.abs(signedArea2(hole));
            }
            return area2 / 2;
        }
    }

    private record Edge(int x, int z, int dir) {
        int endX() {
            return this.x + DX[this.dir];
        }

        int endZ() {
            return this.z + DZ[this.dir];
        }
    }

    /**
     * Builds polygons from packed chunk keys (see {@link ChunkKeys}).
     */
    public static List<Part> fromKeys(final Collection<Long> chunkKeys) {
        final Set<Long> set = chunkKeys instanceof Set<Long> s ? s : new HashSet<>(chunkKeys);
        if (set.isEmpty()) {
            return List.of();
        }

        // 1. Collect boundary edges (edges shared with a claimed neighbour are skipped).
        final List<Edge> edges = new ArrayList<>();
        for (final long key : set) {
            final int x = ChunkKeys.x(key);
            final int z = ChunkKeys.z(key);
            if (!set.contains(ChunkKeys.key(x, z - 1))) {
                edges.add(new Edge(x, z, 0));             // (x,z) -> (x+1,z)
            }
            if (!set.contains(ChunkKeys.key(x + 1, z))) {
                edges.add(new Edge(x + 1, z, 1));         // (x+1,z) -> (x+1,z+1)
            }
            if (!set.contains(ChunkKeys.key(x, z + 1))) {
                edges.add(new Edge(x + 1, z + 1, 2));     // (x+1,z+1) -> (x,z+1)
            }
            if (!set.contains(ChunkKeys.key(x - 1, z))) {
                edges.add(new Edge(x, z + 1, 3));         // (x,z+1) -> (x,z)
            }
        }

        // 2. Index outgoing edges by their start vertex.
        final Map<Long, List<Integer>> outgoing = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            final Edge e = edges.get(i);
            outgoing.computeIfAbsent(ChunkKeys.key(e.x(), e.z()), k -> new ArrayList<>(2)).add(i);
        }

        // 3. Chain edges into loops. Successor rule: left turn, then straight, then right turn.
        final boolean[] used = new boolean[edges.size()];
        final List<List<Vertex>> outers = new ArrayList<>();
        final List<List<Vertex>> holes = new ArrayList<>();
        for (int start = 0; start < edges.size(); start++) {
            if (used[start]) {
                continue;
            }
            final List<Edge> loop = new ArrayList<>();
            int current = start;
            while (!used[current]) {
                used[current] = true;
                final Edge e = edges.get(current);
                loop.add(e);
                final int next = successor(e, edges, outgoing);
                if (next < 0) {
                    throw new IllegalStateException("Open boundary at " + e.endX() + "," + e.endZ());
                }
                current = next;
            }
            if (current != start) {
                throw new IllegalStateException("Boundary loop did not close");
            }
            final List<Vertex> ring = simplify(loop);
            if (signedArea2(ring) > 0) {
                outers.add(ring);
            } else {
                holes.add(ring);
            }
        }

        // 4. Assign each hole to the smallest outer ring containing it.
        final List<List<List<Vertex>>> holesPerOuter = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            holesPerOuter.add(new ArrayList<>());
        }
        for (final List<Vertex> hole : holes) {
            // Test point: just inside the claimed area next to the hole's first edge (left side of the edge).
            final Vertex a = hole.get(0);
            final Vertex b = hole.get(1);
            final int dirX = Integer.signum(b.x() - a.x());
            final int dirZ = Integer.signum(b.z() - a.z());
            final double px = a.x() + dirX * 0.5 - dirZ * 0.25;
            final double pz = a.z() + dirZ * 0.5 + dirX * 0.25;
            int best = -1;
            long bestArea = Long.MAX_VALUE;
            for (int i = 0; i < outers.size(); i++) {
                final List<Vertex> outer = outers.get(i);
                if (contains(outer, px, pz)) {
                    final long area = Math.abs(signedArea2(outer));
                    if (area < bestArea) {
                        bestArea = area;
                        best = i;
                    }
                }
            }
            if (best < 0) {
                throw new IllegalStateException("Hole without outer ring");
            }
            holesPerOuter.get(best).add(hole);
        }

        final List<Part> parts = new ArrayList<>(outers.size());
        for (int i = 0; i < outers.size(); i++) {
            parts.add(new Part(List.copyOf(outers.get(i)), List.copyOf(holesPerOuter.get(i))));
        }
        return parts;
    }

    private static int successor(final Edge e, final List<Edge> edges, final Map<Long, List<Integer>> outgoing) {
        final List<Integer> candidates = outgoing.get(ChunkKeys.key(e.endX(), e.endZ()));
        if (candidates == null) {
            return -1;
        }
        final int[] preference = {(e.dir() + 1) & 3, e.dir(), (e.dir() + 3) & 3};
        for (final int wanted : preference) {
            for (final int idx : candidates) {
                if (edges.get(idx).dir() == wanted) {
                    return idx;
                }
            }
        }
        return -1;
    }

    /** Converts a loop of unit edges into a ring of corner vertices (collinear points removed). */
    private static List<Vertex> simplify(final List<Edge> loop) {
        final List<Vertex> ring = new ArrayList<>();
        final int n = loop.size();
        for (int i = 0; i < n; i++) {
            final Edge e = loop.get(i);
            final Edge prev = loop.get((i - 1 + n) % n);
            if (prev.dir() != e.dir()) {
                ring.add(new Vertex(e.x(), e.z()));
            }
        }
        return ring;
    }

    /** Twice the signed area (shoelace formula); positive for counter-clockwise rings. */
    public static long signedArea2(final List<Vertex> ring) {
        long sum = 0;
        final int n = ring.size();
        for (int i = 0; i < n; i++) {
            final Vertex a = ring.get(i);
            final Vertex b = ring.get((i + 1) % n);
            sum += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return sum;
    }

    /** Ray casting point-in-polygon. Test points never lie on grid lines, so there are no degenerate cases. */
    public static boolean contains(final List<Vertex> ring, final double px, final double pz) {
        boolean inside = false;
        final int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final Vertex a = ring.get(i);
            final Vertex b = ring.get(j);
            if ((a.z() > pz) != (b.z() > pz)) {
                final double xCross = (double) (b.x() - a.x()) * (pz - a.z()) / (double) (b.z() - a.z()) + a.x();
                if (px < xCross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }
}
