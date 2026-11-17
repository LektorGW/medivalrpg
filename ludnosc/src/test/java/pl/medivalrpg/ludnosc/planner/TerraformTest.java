// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class TerraformTest {

    private static final Terraform.Settings S = new Terraform.Settings(2, 6, 3);
    // footprint 11 x 9 around (0, 0), floor at 64
    private static final int MIN_X = -5;
    private static final int MAX_X = 5;
    private static final int MIN_Z = -4;
    private static final int MAX_Z = 4;
    private static final int FLOOR = 64;

    private static Terraform.Plan plan(final IntBinaryOperator h) {
        return Terraform.plan(h, MIN_X, MIN_Z, MAX_X, MAX_Z, FLOOR, S);
    }

    /** Neighbouring non-wall columns of the plan differ by at most 1 inside the modified area. */
    private static int maxInnerStep(final Terraform.Plan p) {
        final Map<Long, Terraform.Column> m = p.byKey();
        int max = 0;
        for (final Terraform.Column c : p.columns()) {
            if (c.wall()) {
                continue;
            }
            for (final int[] d : new int[][] {{1, 0}, {0, 1}}) {
                final Terraform.Column n = m.get(RoadPlanner.key(c.x() + d[0], c.z() + d[1]));
                if (n != null && !n.wall()) {
                    max = Math.max(max, Math.abs(n.newTop() - c.newTop()));
                }
            }
        }
        return max;
    }

    private static void assertPlotLevel(final Terraform.Plan p) {
        for (final Terraform.Column c : p.columns()) {
            if (c.plot()) {
                assertEquals(FLOOR, c.newTop(), "plot levelled at " + c.x() + "," + c.z());
            }
        }
    }

    @Test
    void flatGroundNeedsNothing() {
        final Terraform.Plan p = plan((x, z) -> FLOOR);
        assertPlotLevel(p);
        assertEquals(0, p.wallColumns());
        assertEquals(0, p.maxStep());
        assertTrue(p.columns().stream().filter(c -> !c.plot()).noneMatch(Terraform.Column::changed));
    }

    @Test
    void gentleSlopeGetsRampsWithoutWalls() {
        // slope of 1 block per 2 blocks along x
        final Terraform.Plan p = plan((x, z) -> FLOOR + Math.floorDiv(x, 2));
        assertPlotLevel(p);
        assertEquals(0, p.wallColumns(), "no retaining wall on a gentle slope");
        assertTrue(maxInnerStep(p) <= 1, "slopes ≤ 45°");
        assertTrue(p.maxStep() <= 2, "no earth wall > 2: " + p.maxStep());
    }

    @Test
    void steepSlopeStaysWalkable() {
        // 1:1 slope along z (45°) rising 8 blocks above the floor
        final Terraform.Plan p = plan((x, z) -> FLOOR + Math.max(-4, Math.min(8, z - 4)));
        assertPlotLevel(p);
        assertTrue(maxInnerStep(p) <= 1);
        assertTrue(p.maxStep() <= 2, "max step " + p.maxStep());
    }

    @Test
    void valleyIsFilledWithSlopes() {
        // plot over a valley: terrain 6 blocks lower in the middle, rising to both sides
        final Terraform.Plan p = plan((x, z) -> FLOOR - Math.max(0, 6 - Math.abs(x)));
        assertPlotLevel(p);
        assertTrue(maxInnerStep(p) <= 1);
        assertTrue(p.maxStep() <= 2, "max step " + p.maxStep());
        assertTrue(p.columns().stream().anyMatch(c -> c.plot() && c.oldTop() < c.newTop()), "filled");
    }

    @Test
    void cliffGetsARetainingWallOnlyWhereNeeded() {
        // plateau ends 3 blocks south of the plot, 9 blocks lower
        final IntBinaryOperator cliff = (x, z) -> z > MAX_Z + 4 ? FLOOR - 9 : FLOOR;
        final Terraform.Plan p = plan(cliff);
        assertPlotLevel(p);
        assertTrue(p.wallColumns() > 0, "a retaining wall is needed");
        for (final Terraform.Column c : p.columns()) {
            if (c.wall()) {
                assertTrue(c.z() > MAX_Z, "wall only on the cliff side: " + c);
                assertTrue(c.wallTop() - c.wallBottom() + 1 <= 3, "wall face ≤ 3");
            }
        }
        assertTrue(maxInnerStep(p) <= 1);
        assertTrue(p.maxStep() <= 2, "no earth wall > 2: " + p.maxStep());
    }

    @Test
    void hillsideIsCutWithSlopesOrWall() {
        // hill rising 9 blocks right north of the plot
        final Terraform.Plan p = plan((x, z) -> z < MIN_Z - 2 ? FLOOR + 9 : FLOOR);
        assertPlotLevel(p);
        assertTrue(maxInnerStep(p) <= 1);
        assertTrue(p.maxStep() <= 2, "max step " + p.maxStep());
        for (final Terraform.Column c : p.columns()) {
            if (c.wall()) {
                assertTrue(c.z() <= MIN_Z, "uphill wall on the hill side (or its corners): " + c);
                assertTrue(c.wallTop() - c.wallBottom() + 1 <= 3);
            }
        }
    }

    @Test
    void unknownTerrainIsTreatedAsFloor() {
        final Terraform.Plan p = plan((x, z) -> TerrainView.UNKNOWN);
        assertEquals(0, p.maxStep());
        assertEquals(0, p.wallColumns());
    }
}
