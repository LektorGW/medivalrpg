// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.ceremony;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Model space of the clips (faces -Z, right = +X, px) -> Minecraft world (yaw 0 = south = +Z). */
class CeremonyMathTest {
    private static final double E = 1e-9;

    @Test
    void forwardOfTheModelIsTheFacingDirection() {
        Location a = new Location(null, 10, 64, 20);
        Location f = CeremonyService.toWorld(a, 0f, 0, 0, -16); // 16 px forward, facing south
        assertEquals(10.0, f.getX(), E);
        assertEquals(20 + 16 * CeremonyService.PX, f.getZ(), E);
        Location r = CeremonyService.toWorld(a, 0f, 16, 0, 0); // the right hand side facing south is west
        assertEquals(10 - 16 * CeremonyService.PX, r.getX(), E);
        Location up = CeremonyService.toWorld(a, 90f, 0, 32, 0);
        assertEquals(64 + 32 * CeremonyService.PX, up.getY(), E);
        Location west = CeremonyService.toWorld(a, 90f, 0, 0, -16); // yaw 90 faces west
        assertEquals(10 - 16 * CeremonyService.PX, west.getX(), E);
        assertEquals(20.0, west.getZ(), 1e-6);
    }

    @Test
    void theModelIsAsTallAsAPlayer() {
        // head top of the rig at ~30.6 px must land near the vanilla player height (1.8 blocks)
        assertEquals(1.8, 30.6 * CeremonyService.PX, 0.05);
    }
}
