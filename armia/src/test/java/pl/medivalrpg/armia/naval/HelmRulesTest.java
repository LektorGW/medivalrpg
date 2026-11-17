// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HelmRulesTest {
    @Test void forwardRespectsWorldHeading(){
        var south=HelmRules.step(0,true,true,false,false,false,0,0);
        var west=HelmRules.step(90,true,true,false,false,false,0,0);
        assertEquals(0,south.x(),1e-12);assertEquals(.24,south.z(),1e-12);
        assertEquals(-.24,west.x(),1e-12);assertEquals(0,west.z(),1e-12);
    }
    @Test void oppositeKeysCancelAndReverseIsSlower(){
        var stop=HelmRules.step(73,true,true,true,true,true,.2,.1);
        assertEquals(73,stop.yaw());assertEquals(0,stop.x(),1e-12);assertEquals(0,stop.z(),1e-12);
        var reverse=HelmRules.step(0,true,false,true,false,false,0,0);assertEquals(-.108,reverse.z(),1e-12);
    }
    @Test void steeringWrapsWithoutFullCircleJumps(){
        var turn=HelmRules.step(179,true,true,false,false,true,0,0);
        assertEquals(-178.6,turn.yaw(),1e-4);assertEquals(2.4,DeckRules.yawDelta(179,turn.yaw()),1e-4);
        assertEquals(.24,Math.hypot(turn.x(),turn.z()),1e-12);
    }
    @Test void abandonedHelmBrakesAndCannotPreserveAnExplosiveImpulse(){
        var coast=HelmRules.step(0,false,true,false,false,false,.1,.1);
        assertEquals(.075,coast.x(),1e-12);assertEquals(.075,coast.z(),1e-12);
        var blast=HelmRules.step(0,false,false,false,false,false,20,20);
        assertEquals(.24,Math.hypot(blast.x(),blast.z()),1e-12);
        var settled=HelmRules.step(0,false,false,false,false,false,1e-7,0);assertEquals(0,settled.x());
    }
    @Test void rejectsInvalidMotion(){assertThrows(IllegalArgumentException.class,()->HelmRules.step(0,true,true,false,false,false,Double.NaN,0));}
}
