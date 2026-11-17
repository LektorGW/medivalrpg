// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FireSafetyTest {
    @Test void onlySleepingCitizensInImmediateDangerAreWoken() {
        assertTrue(FireSafety.wakeSleeper(true,64,false));
        assertFalse(FireSafety.wakeSleeper(true,65,false));
        assertTrue(FireSafety.wakeSleeper(true,10000,true));
        assertFalse(FireSafety.wakeSleeper(false,1,true));
    }
    @Test void aBucketCannotExtinguishThroughWallsAcrossBordersOrAtLongRange() {
        assertTrue(FireSafety.mayExtinguish(16,true,true,true));
        assertFalse(FireSafety.mayExtinguish(17,true,true,true));
        assertFalse(FireSafety.mayExtinguish(9,false,true,true));
        assertFalse(FireSafety.mayExtinguish(9,true,false,true));
        assertFalse(FireSafety.mayExtinguish(9,true,true,false));
    }
    @Test void evacuationNeverChoosesBurningForeignUnloadedOrUnwalkableGround() {
        assertTrue(FireSafety.shelter(256,true,true,true,false,16));
        assertFalse(FireSafety.shelter(255,true,true,true,false,16));
        assertFalse(FireSafety.shelter(256,false,true,true,false,16));
        assertFalse(FireSafety.shelter(256,true,false,true,false,16));
        assertFalse(FireSafety.shelter(256,true,true,false,false,16));
        assertFalse(FireSafety.shelter(256,true,true,true,true,16));
    }
    @Test void childrenAndBusyOrEquippedCitizensCannotBecomeFirefighters() {
        assertTrue(FireSafety.mayFight(true,false,true,2,3));
        assertFalse(FireSafety.mayFight(false,false,true,0,3));
        assertFalse(FireSafety.mayFight(true,true,true,0,3));
        assertFalse(FireSafety.mayFight(true,false,false,0,3));
        assertFalse(FireSafety.mayFight(true,false,true,3,3));
        assertFalse(FireSafety.mayFight(true,false,true,0,0));
    }

    @Test void fireplacesOnEternalBasesAreNotTownFires() {
        assertTrue(FireSafety.eternalBase("NETHERRACK"));
        assertTrue(FireSafety.eternalBase("SOUL_SAND"));
        assertTrue(FireSafety.eternalBase("SOUL_SOIL"));
        assertTrue(FireSafety.eternalBase("MAGMA_BLOCK"));
        assertFalse(FireSafety.eternalBase("OAK_PLANKS"));
        assertFalse(FireSafety.eternalBase("HAY_BLOCK"));
        assertFalse(FireSafety.eternalBase("GRASS_BLOCK"));
    }
}
