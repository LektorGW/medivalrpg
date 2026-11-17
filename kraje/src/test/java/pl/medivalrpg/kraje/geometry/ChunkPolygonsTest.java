// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.geometry.ChunkPolygons.Part;
import pl.medivalrpg.kraje.geometry.ChunkPolygons.Vertex;
import pl.medivalrpg.kraje.util.ChunkKeys;

class ChunkPolygonsTest {

    private static Set<Long> chunks(final int[]... coords) {
        final Set<Long> set = new HashSet<>();
        for (final int[] c : coords) {
            set.add(ChunkKeys.key(c[0], c[1]));
        }
        return set;
    }

    private static Set<Long> rect(final int x0, final int z0, final int x1, final int z1) {
        final Set<Long> set = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set.add(ChunkKeys.key(x, z));
            }
        }
        return set;
    }

    private static long totalArea(final List<Part> parts) {
        return parts.stream().mapToLong(Part::area).sum();
    }

    @Test
    void emptySetGivesNoPolygons() {
        assertTrue(ChunkPolygons.fromKeys(Set.of()).isEmpty());
    }

    @Test
    void singleChunkIsSquare() {
        final List<Part> parts = ChunkPolygons.fromKeys(chunks(new int[] {0, 0}));
        assertEquals(1, parts.size());
        final Part part = parts.get(0);
        assertEquals(Set.of(new Vertex(0, 0), new Vertex(1, 0), new Vertex(1, 1), new Vertex(0, 1)), new HashSet<>(part.outer()));
        assertTrue(part.holes().isEmpty());
        assertEquals(1, part.area());
    }

    @Test
    void squareOfChunksMergesIntoOneRectangle() {
        final List<Part> parts = ChunkPolygons.fromKeys(rect(-2, -2, 1, 1)); // 4x4
        assertEquals(1, parts.size());
        final Part part = parts.get(0);
        assertEquals(4, part.outer().size(), "collinear vertices must be removed");
        assertEquals(Set.of(new Vertex(-2, -2), new Vertex(2, -2), new Vertex(2, 2), new Vertex(-2, 2)), new HashSet<>(part.outer()));
        assertTrue(part.holes().isEmpty());
        assertEquals(16, part.area());
        assertTrue(ChunkPolygons.signedArea2(part.outer()) > 0, "outer ring is counter-clockwise");
    }

    @Test
    void lShape() {
        // X .
        // X X
        final List<Part> parts = ChunkPolygons.fromKeys(chunks(new int[] {0, 0}, new int[] {1, 0}, new int[] {0, 1}));
        assertEquals(1, parts.size());
        final Part part = parts.get(0);
        assertEquals(6, part.outer().size());
        assertEquals(Set.of(
            new Vertex(0, 0), new Vertex(2, 0), new Vertex(2, 1),
            new Vertex(1, 1), new Vertex(1, 2), new Vertex(0, 2)), new HashSet<>(part.outer()));
        assertTrue(part.holes().isEmpty());
        assertEquals(3, part.area());
    }

    @Test
    void ringWithHole() {
        final Set<Long> ring = rect(0, 0, 2, 2);
        ring.remove(ChunkKeys.key(1, 1));
        final List<Part> parts = ChunkPolygons.fromKeys(ring);
        assertEquals(1, parts.size());
        final Part part = parts.get(0);
        assertEquals(4, part.outer().size());
        assertEquals(1, part.holes().size());
        final List<Vertex> hole = part.holes().get(0);
        assertEquals(Set.of(new Vertex(1, 1), new Vertex(2, 1), new Vertex(2, 2), new Vertex(1, 2)), new HashSet<>(hole));
        assertTrue(ChunkPolygons.signedArea2(hole) < 0, "hole ring is clockwise");
        assertEquals(8, part.area());
    }

    @Test
    void twoDisjointPieces() {
        final Set<Long> set = rect(0, 0, 1, 1);
        set.addAll(rect(10, 10, 12, 10));
        final List<Part> parts = ChunkPolygons.fromKeys(set);
        assertEquals(2, parts.size());
        assertTrue(parts.stream().allMatch(p -> p.holes().isEmpty()));
        assertEquals(Set.of(4L, 3L), Set.of(parts.get(0).area(), parts.get(1).area()));
    }

    @Test
    void diagonalNeighboursStaySeparate() {
        final List<Part> parts = ChunkPolygons.fromKeys(chunks(new int[] {0, 0}, new int[] {1, 1}));
        assertEquals(2, parts.size());
        assertTrue(parts.stream().allMatch(p -> p.outer().size() == 4 && p.area() == 1));
    }

    @Test
    void islandInsideHoleIsSeparatePart() {
        final Set<Long> set = rect(0, 0, 6, 6);
        set.removeAll(rect(1, 1, 5, 5)); // ring of width 1, hole 5x5
        set.add(ChunkKeys.key(3, 3));    // island in the middle of the hole
        final List<Part> parts = ChunkPolygons.fromKeys(set);
        assertEquals(2, parts.size());
        final Part big = parts.stream().filter(p -> p.area() > 1).findFirst().orElseThrow();
        final Part island = parts.stream().filter(p -> p.area() == 1).findFirst().orElseThrow();
        assertEquals(1, big.holes().size());
        assertTrue(island.holes().isEmpty());
        assertEquals(49 - 25, big.area());
    }

    @Test
    void negativeCoordinatesWork() {
        final List<Part> parts = ChunkPolygons.fromKeys(rect(-100, -50, -98, -49));
        assertEquals(1, parts.size());
        assertEquals(6, parts.get(0).area());
        assertEquals(Set.of(new Vertex(-100, -50), new Vertex(-97, -50), new Vertex(-97, -48), new Vertex(-100, -48)),
            new HashSet<>(parts.get(0).outer()));
    }

    @Test
    void randomShapesPreserveArea() {
        final Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            final Set<Long> set = new HashSet<>();
            for (int x = 0; x < 12; x++) {
                for (int z = 0; z < 12; z++) {
                    if (random.nextInt(100) < 55) {
                        set.add(ChunkKeys.key(x - 6, z - 6));
                    }
                }
            }
            final List<Part> parts = ChunkPolygons.fromKeys(set);
            assertEquals(set.size(), totalArea(parts), "area must equal chunk count (round " + round + ")");
            for (final Part part : parts) {
                assertTrue(ChunkPolygons.signedArea2(part.outer()) > 0);
                part.holes().forEach(h -> assertTrue(ChunkPolygons.signedArea2(h) < 0));
            }
        }
    }

    @Test
    void connectivityChecks() {
        final Set<Long> line = rect(0, 0, 4, 0);
        final long capital = ChunkKeys.key(0, 0);
        assertTrue(ChunkGraph.isConnected(line));
        assertFalse(ChunkGraph.staysConnectedWithout(line, ChunkKeys.key(2, 0), capital), "middle split");
        assertTrue(ChunkGraph.staysConnectedWithout(line, ChunkKeys.key(4, 0), capital), "end removal");
        assertFalse(ChunkGraph.staysConnectedWithout(line, capital, capital), "capital removal");
        final Set<Long> ring = rect(0, 0, 2, 2);
        ring.remove(ChunkKeys.key(1, 1));
        assertTrue(ChunkGraph.staysConnectedWithout(ring, ChunkKeys.key(2, 2), capital), "ring stays connected");
        final Set<Long> split = rect(0, 0, 2, 0);
        split.addAll(rect(10, 0, 12, 0));
        assertEquals(2, ChunkGraph.componentCount(split));
        assertTrue(ChunkGraph.removalKeepsConnectivity(split, ChunkKeys.key(12, 0)), "trim detached piece");
        assertFalse(ChunkGraph.removalKeepsConnectivity(split, ChunkKeys.key(11, 0)), "no new split");
        assertTrue(ChunkGraph.isAdjacent(line, ChunkKeys.key(5, 0)));
        assertFalse(ChunkGraph.isAdjacent(line, ChunkKeys.key(5, 1)), "diagonal is not adjacent");
    }

    @Test
    void chunkKeysRoundTrip() {
        for (final int[] c : new int[][] {{0, 0}, {-1, -1}, {123456, -654321}, {Integer.MIN_VALUE, Integer.MAX_VALUE}}) {
            final long key = ChunkKeys.key(c[0], c[1]);
            assertEquals(c[0], ChunkKeys.x(key));
            assertEquals(c[1], ChunkKeys.z(key));
        }
    }
}
