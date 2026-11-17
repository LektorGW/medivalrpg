// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldAndPoseRulesTest {

    @Test
    void holdEndsOnTimeoutDistanceAndQuit() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        HoldSession s = new HoldSession(a, b, "hold", 0, 30_000, 4.0);
        assertNull(s.check(10_000, true, 2.0));
        assertNull(s.check(10_000, true, 4.0), "exactly at the limit is still fine");
        assertEquals(HoldSession.EndReason.DISTANCE, s.check(10_000, true, 4.01));
        assertEquals(HoldSession.EndReason.DISTANCE, s.check(10_000, true, Double.NaN), "different worlds");
        assertEquals(HoldSession.EndReason.TIMEOUT, s.check(30_000, true, 1.0));
        assertEquals(HoldSession.EndReason.QUIT, s.check(1000, false, 1.0));
        assertEquals(b, s.other(a));
        assertEquals(a, s.other(b));
        assertTrue(s.involves(a));
        assertFalse(s.involves(UUID.randomUUID()));
    }

    private static PoseRules.State ok() {
        return new PoseRules.State(false, true, false, false, true, true, true);
    }

    @Test
    void poseAllowedOnSolidGround() {
        assertNull(PoseRules.check(ok()));
    }

    @Test
    void poseRefusedInAirWaterVehicleAndLowCeiling() {
        assertEquals("pose-air", PoseRules.check(new PoseRules.State(false, false, false, false, true, true, true)));
        assertEquals("pose-air", PoseRules.check(new PoseRules.State(false, true, false, false, true, false, true)));
        assertEquals("pose-air", PoseRules.check(new PoseRules.State(false, true, false, true, true, true, true)));
        assertEquals("pose-water", PoseRules.check(new PoseRules.State(false, true, true, false, true, true, true)));
        assertEquals("pose-riding", PoseRules.check(new PoseRules.State(true, true, false, false, true, true, true)));
        assertEquals("pose-busy", PoseRules.check(new PoseRules.State(false, true, false, false, false, true, true)));
        assertEquals("pose-headroom", PoseRules.check(new PoseRules.State(false, true, false, false, true, true, false)));
    }

    @Test
    void lyingStandsUpOnRealMovementOnly() {
        assertFalse(PoseRules.movedTooFar(0.1, 0.0, 0.1));
        assertFalse(PoseRules.movedTooFar(0, 0.5, 0));
        assertTrue(PoseRules.movedTooFar(0.3, 0, 0));
        assertTrue(PoseRules.movedTooFar(0, 0.7, 0));
        assertTrue(PoseRules.movedTooFar(0.2, 0, 0.2));
    }

    @Test
    void stairsSitYawFacesAwayFromTheStep() {
        assertEquals(0f, PoseRules.stairsSitYaw(BlockFace.NORTH)); // rises north -> look south
        assertEquals(180f, PoseRules.stairsSitYaw(BlockFace.SOUTH));
        assertEquals(-90f, PoseRules.stairsSitYaw(BlockFace.WEST)); // look east
        assertEquals(90f, PoseRules.stairsSitYaw(BlockFace.EAST)); // look west
    }

    @Test
    void menuAutoSlotsAreCentred() {
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16), InteractionMenu.autoSlots(7).subList(0, 7));
        assertEquals(List.of(12, 13, 14), InteractionMenu.autoSlots(3).subList(0, 3));
        assertEquals(27, InteractionMenu.autoSlots(9).size());
        assertEquals(1, InteractionMenu.autoSlots(9).getFirst()); // more than a row: the inner columns
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 10), InteractionMenu.autoSlots(14).subList(0, 8));
        assertEquals(27, InteractionMenu.autoSlots(14).size());
    }
}
