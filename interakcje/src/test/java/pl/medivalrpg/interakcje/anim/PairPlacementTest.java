// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairPlacementTest {
    private static final double EPS = 1e-6;

    @Test
    void yawFollowsBukkitConvention() {
        assertEquals(0f, PairPlacement.yaw(0, 1), 1e-4);      // south
        assertEquals(90f, PairPlacement.yaw(-1, 0), 1e-4);    // west
        assertEquals(180f, Math.abs(PairPlacement.yaw(0, -1)), 1e-4); // north
        assertEquals(-90f, PairPlacement.yaw(1, 0), 1e-4);    // east
        double[] d = PairPlacement.direction(90f);
        assertEquals(-1, d[0], EPS);
        assertEquals(0, d[1], EPS);
        assertEquals(-170f, PairPlacement.normalizeYaw(190f), 1e-4);
        assertEquals(170f, PairPlacement.normalizeYaw(-190f), 1e-4);
    }

    @Test
    void faceToFaceKeepsTheMidpointAndTheDistance() {
        PairPlacement.Result r = PairPlacement.face(10, 5, 12, 6, 0.6, 0f);
        PairPlacement.Spot s = r.sender();
        PairPlacement.Spot t = r.target();
        assertEquals(11.0, (s.x() + t.x()) / 2, EPS);
        assertEquals(5.5, (s.z() + t.z()) / 2, EPS);
        assertEquals(0.6, Math.hypot(s.x() - t.x(), s.z() - t.z()), EPS);
        // both look at each other
        double[] ds = PairPlacement.direction(s.yaw());
        double[] dt = PairPlacement.direction(t.yaw());
        double ux = (t.x() - s.x()) / 0.6;
        double uz = (t.z() - s.z()) / 0.6;
        assertEquals(1.0, ds[0] * ux + ds[1] * uz, 1e-5);
        assertEquals(-1.0, dt[0] * ux + dt[1] * uz, 1e-5);
        assertEquals(180f, Math.abs(PairPlacement.normalizeYaw(s.yaw() - t.yaw())), 1e-3);
        // each moves (3 - 0.6) / 2 along the line when they start 3 apart
        PairPlacement.Result far = PairPlacement.face(0, 0, 3, 0, 0.6, 0f);
        assertEquals(1.2, far.maxShift(0, 0, 3, 0), EPS);
        assertEquals(1.2, far.sender().x(), EPS);
        assertEquals(1.8, far.target().x(), EPS);
        assertEquals(-90f, far.sender().yaw(), 1e-4); // looks east (+X) at the target
        assertEquals(90f, far.target().yaw(), 1e-4);
    }

    @Test
    void faceToFaceOnTheSameSpotUsesTheSendersLook() {
        PairPlacement.Result r = PairPlacement.face(0, 0, 0, 0, 0.6, 0f); // sender looks south
        assertEquals(-0.3, r.sender().z(), EPS);
        assertEquals(0.3, r.target().z(), EPS);
        assertEquals(0f, r.sender().yaw(), 1e-4);
    }

    @Test
    void sideBySideLooksTheSameWayWithTheTargetOnTheRight() {
        // target east of the sender, both look north (yaw 180): north-facing -> right hand is east
        PairPlacement.Result r = PairPlacement.side(0, 0, 2, 0, 0.8, 180f, 175f, true);
        assertTrue(r.targetOnRight());
        assertEquals(r.sender().yaw(), r.target().yaw(), 1e-4);
        assertEquals(180f, Math.abs(r.sender().yaw()), 1e-3);
        assertEquals(0.8, Math.hypot(r.sender().x() - r.target().x(), r.sender().z() - r.target().z()), EPS);
        assertEquals(1.0, (r.sender().x() + r.target().x()) / 2, EPS);
        // the target really is on the sender's right: right of facing f is (-f.z, f.x)
        double[] f = PairPlacement.direction(r.sender().yaw());
        double rx = -f[1];
        double rz = f[0];
        double dx = r.target().x() - r.sender().x();
        double dz = r.target().z() - r.sender().z();
        assertEquals(0.8, dx * rx + dz * rz, 1e-5);
    }

    @Test
    void sideBySideFlipsWhenTheyLookTheOtherWay() {
        // same positions, both look south: the target (east) is now on the sender's left
        PairPlacement.Result r = PairPlacement.side(0, 0, 2, 0, 0.8, 0f, 5f, true);
        assertFalse(r.targetOnRight());
        assertEquals(0f, r.sender().yaw(), 1e-3);
        // without mirrored clips the facing is forced so the target stays on the right
        PairPlacement.Result forced = PairPlacement.side(0, 0, 2, 0, 0.8, 0f, 5f, false);
        assertTrue(forced.targetOnRight());
        assertEquals(180f, Math.abs(forced.sender().yaw()), 1e-3);
    }

    @Test
    void sideBySideOnTheSameSpotPutsTheTargetOnTheRight() {
        PairPlacement.Result r = PairPlacement.side(5, 5, 5, 5, 0.8, 0f, 0f, true); // look south, right = west
        assertTrue(r.targetOnRight());
        assertTrue(r.target().x() < r.sender().x());
        assertEquals(0.8, r.sender().x() - r.target().x(), EPS);
    }
}
