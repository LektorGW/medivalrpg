// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanRotationTest {
    @Test void clockwiseAndNegativeTurns() {
        assertArrayEquals(new int[] {0,1}, PlanRotation.point(1,0,1));
        assertArrayEquals(new int[] {0,-1}, PlanRotation.point(1,0,-1));
    }
    @Test void rotationIsBijectiveAndInvertibleAcrossDepotFootprint() {
        for (int x = -38; x <= 38; x++) for (int z = -30; z <= 30; z++) for (int q = -4; q <= 4; q++) {
            int[] p = PlanRotation.point(x,z,q), original = PlanRotation.point(p[0],p[1],-q);
            assertArrayEquals(new int[] {x,z}, original);
            assertEquals(x*x + z*z, p[0]*p[0] + p[1]*p[1]);
        }
    }
}
