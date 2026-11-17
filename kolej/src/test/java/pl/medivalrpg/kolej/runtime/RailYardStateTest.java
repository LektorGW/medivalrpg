// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.depot.TurntablePlan;
import pl.medivalrpg.kolej.network.TrackSegment;
import static org.junit.jupiter.api.Assertions.*;

class RailYardStateTest {
    private static Train train(Depot d,String phase,int bay,double along,double yaw){
        Train t=new Train();t.yardBase="base";t.yardPhase=phase;t.yardBay=bay;double a=Math.toRadians(yaw);
        t.yardPose=new double[]{d.x()+.5-Math.sin(a)*along,d.groundY()+1+TrackSegment.RAIL_TOP,d.z()+.5+Math.cos(a)*along,yaw};return t;
    }
    @Test void restoreAcceptsAllValidPhasesAndRotations(){
        for(int q=0;q<4;q++){
            Depot d=new Depot(5070,66,970,q);double in=q*90;
            for(String phase:new String[]{"ALIGN_IN","IN","TO_GATE"})assertTrue(RailYard.consistentPose(train(d,phase,0,-18,in),d));
            for(String phase:new String[]{"ROTATE_IN","ROTATE_OUT"})assertTrue(RailYard.consistentPose(train(d,phase,0,4,in+15),d));
            double bay=TurntablePlan.bayYaw(in,2);
            for(String phase:new String[]{"TO_BAY","PARKED","ALIGN_EXIT","FROM_BAY"})assertTrue(RailYard.consistentPose(train(d,phase,2,RailYard.PARK,bay),d));
        }
    }
    @Test void corruptOrCrossPhasePoseMustNotMoveAfterRestart(){
        Depot d=new Depot(0,66,0,0);Train t=train(d,"TO_BAY",0,-18,0);assertFalse(RailYard.consistentPose(t,d));
        t=train(d,"ROTATE_IN",0,10,0);assertFalse(RailYard.consistentPose(t,d));
        t=train(d,"IN",0,0,0);t.yardPose[1]=80;assertFalse(RailYard.consistentPose(t,d));
        t.yardPose=null;assertFalse(RailYard.validState(t));
        t.yardPose=new double[]{0,67,0,Double.NaN};assertFalse(RailYard.validState(t));
        t=train(d,"IN",0,0,0);t.yardPhase="BROKEN";assertFalse(RailYard.validState(t));
        t.yardPhase="IN";t.yardBay=6;assertFalse(RailYard.validState(t));
        t.yardBay=0;t.cars.add(Car.KRYTY);assertFalse(RailYard.validState(t));
    }
    @Test void shuntStepsAreBoundedAndBackingKeepsEngineFacing(){
        double[] start={.5,67,.5,0},goal={.5,67,-17.5,0};
        double[] step=RailYard.stepPose(start,goal,.12);
        assertEquals(-.12,step[2]-start[2],1e-8);assertEquals(0,step[3]);assertEquals(.5,start[2]);
        assertArrayEquals(goal,RailYard.stepPose(goal,goal,.12));
        assertThrows(IllegalArgumentException.class,()->RailYard.stepPose(start,goal,Double.NaN));
    }
    @Test void gateFitsWholeConsistAndParkKeepsNoseBeforeRearWall(){
        assertTrue(RailYard.GATE-11>=-30);assertTrue(RailYard.PARK+4<26);
        assertEquals(4,RailYard.HEAD_ON_TABLE);assertEquals(0,TurntablePlan.bayYaw(0,6));
    }
    @Test void duplicateRestoredBayBlocksBothTrains(){
        Depot d=new Depot(0,66,0,0);Train a=train(d,"PARKED",2,RailYard.PARK,-10),b=train(d,"PARKED",2,RailYard.PARK,-10);
        assertTrue(RailYard.duplicateBay(a,java.util.List.of(a,b)));assertTrue(RailYard.duplicateBay(b,java.util.List.of(a,b)));
        b.yardBay=3;assertFalse(RailYard.duplicateBay(a,java.util.List.of(a,b)));
    }
}
