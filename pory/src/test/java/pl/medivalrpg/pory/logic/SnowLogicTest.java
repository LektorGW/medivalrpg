// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SnowLogicTest {

    private static SnowCoverage coverage() {
        final Map<VisualSeason, List<Double>> m = new EnumMap<>(VisualSeason.class);
        m.put(VisualSeason.ZIMA, List.of(0.45, 0.75, 1.0, 1.0, 1.0, 0.9, 0.7));
        m.put(VisualSeason.WIOSNA, List.of(0.3, 0.12, 0.0));
        m.put(VisualSeason.JESIEN, List.of(2.0, -1.0));
        return new SnowCoverage(m);
    }

    @Test
    void coverageByDay() {
        final SnowCoverage c = coverage();
        assertEquals(0.45, c.coverage(VisualSeason.ZIMA, 1));
        assertEquals(1.0, c.coverage(VisualSeason.ZIMA, 3));
        assertEquals(0.7, c.coverage(VisualSeason.ZIMA, 7));
        assertEquals(0.7, c.coverage(VisualSeason.ZIMA, 30), "last value repeats");
        assertEquals(0.45, c.coverage(VisualSeason.ZIMA, 0), "clamped");
        assertEquals(0.3, c.coverage(VisualSeason.WIOSNA, 1));
        assertEquals(0.0, c.coverage(VisualSeason.WIOSNA, 5));
        assertEquals(0.0, c.coverage(VisualSeason.LATO, 2));
        assertEquals(1.0, c.coverage(VisualSeason.JESIEN, 1), "clamped to 1");
        assertEquals(0.0, c.coverage(VisualSeason.JESIEN, 2), "clamped to 0");
        assertEquals(1.0, c.peak(VisualSeason.ZIMA));
        assertEquals(3, c.peakDay(VisualSeason.ZIMA));
        assertEquals(1, c.peakDay(VisualSeason.LATO));
        assertEquals(0.0, c.coverage(null, 1));
    }

    @Test
    void coveredIsMonotonicAndStable() {
        int low = 0;
        int high = 0;
        for (int x = -200; x < 200; x++) {
            for (int z = -200; z < 200; z++) {
                final boolean a = SnowCoverage.covered(x, z, 0.3);
                final boolean b = SnowCoverage.covered(x, z, 0.7);
                assertTrue(!a || b, "more coverage never removes a column");
                assertEquals(a, SnowCoverage.covered(x, z, 0.3), "stable");
                assertTrue(SnowCoverage.covered(x, z, 1.0));
                assertFalse(SnowCoverage.covered(x, z, 0.0));
                low += a ? 1 : 0;
                high += b ? 1 : 0;
            }
        }
        final double total = 400.0 * 400.0;
        assertTrue(low / total > 0.1 && low / total < 0.4, "about 30%: " + low / total);
        assertTrue(high / total > 0.6 && high / total < 0.95, "about 70%: " + high / total);
    }

    @Test
    void coverIsPatchyNotSpeckled() {
        // neighbouring columns agree far more often than random columns would
        int same = 0;
        int n = 0;
        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                same += SnowCoverage.covered(x, z, 0.5) == SnowCoverage.covered(x + 1, z, 0.5) ? 1 : 0;
                n++;
            }
        }
        assertTrue(same / (double) n > 0.7, "neighbour agreement " + same / (double) n);
    }

    @Test
    void columnValueInRange() {
        for (int i = -1000; i < 1000; i += 7) {
            final double v = SnowCoverage.columnValue(i, i * 31);
            assertTrue(v >= 0 && v < 1);
        }
    }

    @Test
    void trackerAddRemoveAndCounts() {
        final SnowTracker t = new SnowTracker();
        t.add(5, 70, -3);
        t.add(5, 71, -3); // same column, new height
        t.add(-17, 64, 33);
        assertEquals(2, t.columns());
        assertEquals(2, t.chunks());
        assertEquals(71, t.snowAt(5, -3));
        assertFalse(t.remove(5, 70, -3), "wrong height");
        assertTrue(t.remove(5, 71, -3));
        assertEquals(SnowTracker.NONE, t.snowAt(5, -3));
        assertEquals(1, t.columns());
        t.clearChunk(-17 >> 4, 33 >> 4);
        assertEquals(0, t.columns());
        t.add(1, 1, 1);
        t.clearAll();
        assertEquals(0, t.columns());
        assertEquals(0, t.chunks());
    }

    @Test
    void trackerBlockUpdates() {
        final SnowTracker t = new SnowTracker();
        t.add(10, 65, 10);
        assertFalse(t.onBlockUpdate(10, 64, 10, true), "support replaced by another support: snow stays");
        assertEquals(65, t.snowAt(10, 10));
        assertTrue(t.onBlockUpdate(10, 64, 10, false), "support broken: remove snow");
        assertEquals(SnowTracker.NONE, t.snowAt(10, 10));
        assertFalse(t.onBlockUpdate(10, 64, 10, false), "nothing left");
        t.add(10, 65, 10);
        assertFalse(t.onBlockUpdate(10, 65, 10, false), "real block at snow position");
        assertEquals(SnowTracker.NONE, t.snowAt(10, 10));
        t.add(10, 65, 10);
        assertFalse(t.onBlockUpdate(10, 80, 10, false), "unrelated height");
        assertFalse(t.onBlockUpdate(11, 64, 10, false), "other column");
        assertEquals(65, t.snowAt(10, 10));
        assertEquals(1, t.columns());
    }

    @Test
    void snapshotsAreCopies() {
        final SnowTracker t = new SnowTracker();
        t.add(0, 70, 0);
        final short[] s = t.snapshot(0, 0);
        s[0] = 5;
        assertEquals(70, t.snowAt(0, 0));
        assertNull(t.snapshot(1, 1));
    }

    @Test
    void diffAndOrdering() {
        final short[] cur = new short[256];
        final short[] want = new short[256];
        Arrays.fill(cur, SnowTracker.NONE);
        Arrays.fill(want, SnowTracker.NONE);
        cur[0] = 70;
        cur[1] = 71;
        want[1] = 71;
        want[2] = 72;
        final List<ChunkPlans.ColumnChange> d = ChunkPlans.diff(cur, want);
        assertEquals(2, d.size());
        assertEquals(new ChunkPlans.ColumnChange(0, 70, SnowTracker.NONE), d.get(0));
        assertEquals(new ChunkPlans.ColumnChange(2, SnowTracker.NONE, 72), d.get(1));
        assertEquals(1, ChunkPlans.diff(null, want).size() - 1);
        assertTrue(ChunkPlans.diff(null, null).isEmpty());

        final long a = SnowTracker.chunkKey(0, 0);
        final long b = SnowTracker.chunkKey(5, -5);
        final long c = SnowTracker.chunkKey(-1, 1);
        assertArrayEquals(new long[] {a, c, b}, ChunkPlans.nearestFirst(Set.of(a, b, c), 0, 0));
        assertEquals(-1, ChunkPlans.chunkX(c));
        assertEquals(1, ChunkPlans.chunkZ(c));
        assertEquals(5, ChunkPlans.chunkX(b));
        assertEquals(-5, ChunkPlans.chunkZ(b));
    }

    @Test
    void seasonParsing() {
        assertEquals(VisualSeason.JESIEN, VisualSeason.parse("Jesień"));
        assertEquals(VisualSeason.ZIMA, VisualSeason.parse("winter"));
        assertNull(VisualSeason.parse("monsun"));
        assertEquals(VisualSeason.WIOSNA, VisualSeason.byIndex(0));
        assertEquals(VisualSeason.ZIMA, VisualSeason.byIndex(3));
        assertNull(VisualSeason.byIndex(4));
        assertEquals(VisualSeason.WIOSNA, VisualSeason.ZIMA.next());
    }
}
