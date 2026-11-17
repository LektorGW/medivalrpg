// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Owner's decision 2026-11-03: natural embankments instead of stone-brick retaining walls with fences (Landsbergia,
 * Watykan: "budowle mają sobie mury"). Earth slopes of at most one block per block, a face only where the slope cannot
 * fit into the terrain the builders may touch.
 */
class NaturalEmbankmentTest {

    private static final Terraform.Settings S = new Terraform.Settings(TerrainFootprint.MARGIN, TerrainFootprint.RAMP, 3);
    private static final int FLOOR = 70;

    /** Largest height difference between neighbours over the graded columns and the untouched terrain around them. */
    private static int maxStep(final Terraform.Plan p, final IntBinaryOperator h) {
        final Map<Long, Terraform.Column> m = p.byKey();
        int max = 0;
        for (final Terraform.Column c : p.columns()) {
            if (c.wall()) {
                continue;
            }
            for (final int[] d : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                final Terraform.Column n = m.get(RoadPlanner.key(c.x() + d[0], c.z() + d[1]));
                if (n != null && n.wall()) {
                    continue;
                }
                final int nt = n != null ? n.newTop() : h.applyAsInt(c.x() + d[0], c.z() + d[1]);
                max = Math.max(max, Math.abs(nt - c.newTop()));
            }
        }
        return max;
    }

    @Test
    void anElevenByElevenStoreOnAOneToTwoHillsideGetsOnlyEarthSlopes() {
        final IntBinaryOperator hill = (x, z) -> FLOOR + Math.floorDiv(x, 2); // Landsbergia: 1 block per 2
        final Terraform.Plan p = Terraform.plan(hill, (x, z) -> true, -5, -5, 5, 5, FLOOR, S, true);
        assertEquals(0, p.wallColumns(), "skarpa mieści się w pasie " + TerrainFootprint.RAMP);
        assertTrue(maxStep(p, hill) <= 1, "spadek najwyżej 1:1, jest " + maxStep(p, hill));
        for (final Terraform.Column c : p.columns()) {
            if (c.plot()) {
                assertEquals(FLOOR, c.newTop());
            }
        }
    }

    @Test
    void protectedColumnsAreLeftAloneAndAFaceIsTheLastResort() {
        final IntBinaryOperator steep = (x, z) -> FLOOR + 3 * Math.max(0, x - 5); // 3 blocks per block next to the plot
        final Map<Long, Boolean> touched = new HashMap<>();
        final Terraform.Plan p = Terraform.plan(steep, (x, z) -> {
            final boolean ok = !(x == 9 && z == 0);
            touched.put(RoadPlanner.key(x, z), ok);
            return ok;
        }, -5, -5, 5, 5, FLOOR, S, true);
        assertTrue(p.columns().stream().noneMatch(c -> c.x() == 9 && c.z() == 0), "chroniona kolumna nietknięta");
        assertTrue(p.wallColumns() > 0, "zbyt stromo: uskok jako ostateczność");
        for (final Terraform.Column c : p.columns()) {
            if (c.wall()) {
                assertTrue(c.wallTop() - c.wallBottom() + 1 <= 3, "niski uskok");
            }
        }
    }
}
