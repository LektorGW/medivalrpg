// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MineLightingTest {
    @Test void firstHeadAndEverySeventhStepGetLighting() {
        assertTrue(MineLighting.due(0,1,7,false));
        for(int n=1;n<40;n++) assertEquals((n+1)%7==0,MineLighting.due(n,n+1,7,false));
    }
    @Test void existingTorchMakesRestartReplayIdempotent() {
        assertFalse(MineLighting.due(0,1,7,true)); assertFalse(MineLighting.due(6,7,7,true));
        assertFalse(MineLighting.due(7,7,7,false)); assertFalse(MineLighting.due(7,6,7,false));
    }
    @Test void wallTorchFacesAwayFromItsSupportingWall() {
        assertEquals("minecraft:wall_torch[facing=south]",MineLighting.wallState("north"));
        assertEquals("minecraft:wall_torch[facing=west]",MineLighting.wallState("east"));
        assertEquals("minecraft:wall_torch[facing=north]",MineLighting.wallState("south"));
        assertEquals("minecraft:wall_torch[facing=east]",MineLighting.wallState("west"));
    }
    @Test void containersOreWaterAndExistingStructuresCannotBecomeTorchTargets() {
        assertTrue(MineLighting.canPlace(true,true,false,false));
        assertFalse(MineLighting.canPlace(false,true,false,false));
        assertFalse(MineLighting.canPlace(true,false,false,false));
        assertFalse(MineLighting.canPlace(true,true,true,false));
        assertFalse(MineLighting.canPlace(true,true,false,true));
    }
}
