// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ring buffers, minute aggregation, downsampling and persistence of the health history. */
class HealthHistoryTest {

    static final long T0 = 1_761_500_000_000L - Math.floorMod(1_761_500_000_000L, 60_000L); // minute start

    static HealthPoint p(final long t, final double tps, final double mspt, final int npc) {
        return HealthPoint.probe(t, tps, mspt, 3, 400, 1200, Map.of("ludnosc", npc, "armia", 1), 5);
    }

    @Test
    void probesAreAggregatedPerMinute() {
        final HealthHistory h = new HealthHistory(3_600_000L, 24 * 3_600_000L);
        for (int i = 0; i < 12; i++) { // 2 minutes at 10 s
            h.add(p(T0 + i * 10_000L, i < 6 ? 20.0 : 14.0, i < 6 ? 10.0 : 70.0, 100 + i));
        }
        assertEquals(12, h.fineSize());
        final List<HealthPoint> c = h.coarse(0);
        assertEquals(2, c.size(), "closed minute + open minute");
        assertEquals(T0, c.get(0).t());
        assertEquals(20.0, c.get(0).tps(), 1e-9);
        assertEquals(14.0, c.get(1).tpsMin(), 1e-9);
        assertEquals(70.0, c.get(1).msptMax(), 1e-9);
        assertEquals(111 + 1, c.get(1).npcTotal(), "max of counts");
        assertEquals(111, c.get(1).npc().get("ludnosc"));
    }

    @Test
    void retentionDropsOldProbesAndMinutes() {
        final HealthHistory h = new HealthHistory(60_000L * 10, 3_600_000L);
        for (int i = 0; i <= 120; i++) { // 2 h at 1 probe / min
            h.add(p(T0 + i * 60_000L, 20, 5, 1));
        }
        final long now = T0 + 120 * 60_000L;
        assertTrue(h.fine(0).get(0).t() >= now - 600_000L, "fine keeps 10 min");
        assertTrue(h.coarse(0).get(0).t() >= now - 3_600_000L, "coarse keeps 1 h");
        assertEquals(61, h.coarseSize());
    }

    @Test
    void probesGoingBackInTimeAreIgnored() {
        final HealthHistory h = new HealthHistory(3_600_000L, 3_600_000L);
        h.add(p(T0 + 20_000, 20, 5, 1));
        h.add(p(T0 + 10_000, 20, 5, 1));
        assertEquals(1, h.fineSize());
    }

    @Test
    void downsampleMergesConsecutiveRuns() {
        final List<HealthPoint> in = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            in.add(p(T0 + i * 60_000L, 20 - i, 5 + i, i));
        }
        final List<HealthPoint> out = HealthHistory.downsample(in, 4);
        assertEquals(4, out.size(), "ceil(10/4)=3 per group → 4 groups");
        assertEquals(T0, out.get(0).t());
        assertEquals(19.0, out.get(0).tps(), 1e-9, "average of 20,19,18");
        assertEquals(18.0, out.get(0).tpsMin(), 1e-9);
        assertEquals(7.0, out.get(0).msptMax(), 1e-9);
        assertEquals(9, out.get(3).npc().get("ludnosc"));
        assertEquals(in, HealthHistory.downsample(in, 100), "small input unchanged");
    }

    @Test
    void mergeKeepsUnionOfWarningsAndWeightsAverages() {
        final HealthPoint a = p(T0, 20, 10, 1).withWarn(List.of("TPS"));
        final HealthPoint b = p(T0, 10, 50, 1).withWarn(List.of("MSPT", "TPS"));
        final HealthPoint m = HealthPoint.merge(List.of(a, b), new int[] {3, 1}, T0);
        assertEquals(List.of("TPS", "MSPT"), m.warn());
        assertEquals(17.5, m.tps(), 1e-9);
        assertEquals(20.0, m.mspt(), 1e-9);
    }

    @Test
    void saveAndRestoreThroughJsonMergesTheOpenMinuteAfterRestart() {
        final HealthHistory h = new HealthHistory(3_600_000L, 24 * 3_600_000L);
        for (int i = 0; i < 9; i++) {
            h.add(p(T0 + i * 10_000L, 20, 8, 7));
        }
        final Gson gson = new Gson();
        final String json = gson.toJson(h.save());
        final HealthHistory.Saved back = gson.fromJson(json, HealthHistory.Saved.class);
        final HealthHistory r = new HealthHistory(3_600_000L, 24 * 3_600_000L);
        r.restore(back, T0 + 100_000L);
        assertEquals(9, r.fineSize());
        assertEquals(2, r.coarseSize(), "minute 0 and the open minute 1 saved as aggregates");
        r.add(p(T0 + 110_000L, 10, 80, 7)); // same minute as the saved open bucket
        final List<HealthPoint> c = r.coarse(0);
        assertEquals(2, c.size(), "no duplicate minute");
        assertEquals(10.0, c.get(1).tpsMin(), 1e-9);
        assertEquals((20.0 * 3 + 10.0) / 4, c.get(1).tps(), 1e-9, "weighted by probe count");
        assertEquals(7, r.last().npc().get("ludnosc"));
    }

    @Test
    void restoreDropsExpiredPoints() {
        final HealthHistory h = new HealthHistory(3_600_000L, 3_600_000L);
        h.add(p(T0, 20, 5, 1));
        final HealthHistory r = new HealthHistory(3_600_000L, 3_600_000L);
        r.restore(h.save(), T0 + 2 * 3_600_000L);
        assertEquals(0, r.fineSize());
        assertEquals(0, r.coarseSize());
    }
}
