// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.kolej.visual.LocomotiveAnimator.Loop.*;

class LocomotiveAnimatorTest {
    @Test void coldStandingIdleWithFireAndRunWhenMoving() {
        var a=new LocomotiveAnimator();
        assertEquals(NONE,a.update(0,0,1,false,true).loop());
        assertEquals(IDLE,a.update(2,0,1,true,true).loop());
        var f=a.update(4,1.5,0,true,true);
        assertEquals(RUN,f.loop());assertTrue(f.moving());
        assertEquals(1.5/(2*Math.PI*.5625),f.wheelSpeed(),1e-6);
    }
    @Test void departureStartsOnceAndWhistlesOnlyOnMainline() {
        var a=new LocomotiveAnimator();a.update(0,0,1,true,true);
        var f=a.update(2,.2,0,true,true);assertTrue(f.start());assertTrue(f.whistle());
        assertFalse(a.update(4,.4,0,true,true).start());
        var yard=new LocomotiveAnimator();yard.update(0,0,1,true,false);
        var y=yard.update(2,.2,0,true,false);assertTrue(y.start());assertFalse(y.whistle());
    }
    @Test void hysteresisAndCooldownsAvoidFlickerAndWhistleSpam() {
        var a=new LocomotiveAnimator();a.update(0,0,1,true,true);
        assertTrue(a.update(2,.2,0,true,true).start());
        // Creeping between thresholds keeps running; no new start.
        assertEquals(RUN,a.update(4,.04,0,true,true).loop());
        assertEquals(IDLE,a.update(6,.01,1,true,true).loop());
        // Re-start within 5 s: no lurch, no whistle; after 30 s both again.
        var again=a.update(20,.3,0,true,true);assertFalse(again.start());assertFalse(again.whistle());
        a.update(22,0,1,true,true);
        var later=a.update(700,.3,0,true,true);assertTrue(later.start());assertTrue(later.whistle());
    }
    @Test void brakingOnlyWhileDeceleratingUnderBrake() {
        var a=new LocomotiveAnimator();a.update(0,0,1,true,true);a.update(2,8,0,true,true);
        assertFalse(a.update(4,8,.5,true,true).braking(),"constant speed is not braking");
        assertTrue(a.update(6,7.8,.5,true,true).braking());
        assertTrue(a.update(8,7.8,.5,true,true).braking(),"held while the brake stays applied");
        assertFalse(a.update(10,7.7,0,true,true).braking(),"released brake");
        a.update(12,7,.8,true,true);assertTrue(a.update(14,6.5,.8,true,true).braking());
        assertFalse(a.update(16,.1,.8,true,true).braking(),"too slow for sparks");
    }
    @Test void negativeSpeedReversesWheels() {
        var a=new LocomotiveAnimator();a.update(0,0,1,true,false);
        var f=a.update(2,-1.2,0,true,false);
        assertEquals(RUN_REVERSE,f.loop());assertEquals("run_reverse",f.loop().animation);assertTrue(f.wheelSpeed()>0);
        assertEquals(RUN_REVERSE,LocomotiveAnimator.stateless(-1,true).loop());
        assertEquals(IDLE,LocomotiveAnimator.stateless(Double.NaN,true).loop());
    }
    @Test void directionFromDisplacementAlongYaw() {
        // yaw 0 = forward +Z
        assertEquals(1,LocomotiveAnimator.direction(0,0,0,.12,0));
        assertEquals(-1,LocomotiveAnimator.direction(0,0,0,-.12,0));
        // yaw 90 = forward -X
        assertEquals(1,LocomotiveAnimator.direction(0,0,-.12,0,90));
        assertEquals(-1,LocomotiveAnimator.direction(0,0,.12,0,90));
        assertEquals(0,LocomotiveAnimator.direction(0,0,0,0,0));
        assertEquals(0,LocomotiveAnimator.direction(0,0,Double.NaN,0,0));
    }
    @Test void invalidInputIsSafe() {
        var a=new LocomotiveAnimator();
        var f=a.update(0,Double.NaN,Double.NaN,true,true);
        assertEquals(IDLE,f.loop());assertEquals(0,f.wheelSpeed());assertFalse(f.braking());
    }
}
