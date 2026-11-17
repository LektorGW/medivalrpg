// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.kolej.freight.JourneyProof.*;

class JourneyProofTest {
    Position p(double x){return new Position("world",x,0,0);}
    @Test void increasedCounterWithoutActualMovementIsNotTravel(){
        var proof=new JourneyProof("r",0,p(0));assertFalse(proof.observe(true,"r",100,p(0),p(0),p(10)));
    }
    @Test void teleportWithoutDistanceAndCrossWorldJumpAreNotTravel(){
        var proof=new JourneyProof("r",0,p(0));assertFalse(proof.observe(true,"r",0,p(10),p(0),p(10)));
        proof=new JourneyProof("r",0,p(0));assertFalse(proof.observe(true,"r",10,new Position("other",10,0,0),p(0),p(10)));
    }
    @Test void routeChangeCannotReuseOldCounterJump(){
        var proof=new JourneyProof("old",0,p(0));assertFalse(proof.observe(true,"new",100,p(10),p(0),p(10)));
    }
    @Test void ordinaryProgressOnSameRouteProvesJourney(){
        var proof=new JourneyProof("r",0,p(0));assertFalse(proof.observe(true,"r",5,p(5),p(0),p(10)));
        assertTrue(proof.observe(true,"r",10,p(10),p(0),p(10)));
    }
    @Test void runtimeArrivalTickCanFinishLast235MetresAfterObservedMovement(){
        var proof=new JourneyProof("r",0,p(0));
        assertFalse(proof.observe(true,"r",7.65,p(7.65),p(0),p(10)));
        assertTrue(proof.observe(false,"r",10,p(10),p(0),p(10)));
    }
    @Test void stoppedJumpWithoutPreviouslyObservedMovementDoesNotProveArrival(){
        var proof=new JourneyProof("r",0,p(0));
        assertFalse(proof.observe(true,"r",0,p(0),p(0),p(2)));
        assertFalse(proof.observe(false,"r",2,p(2),p(0),p(2)));
    }
}
