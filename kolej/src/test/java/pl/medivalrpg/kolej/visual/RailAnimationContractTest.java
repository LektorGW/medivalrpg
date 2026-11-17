// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.infrastructure.SupplyKind;
import pl.medivalrpg.kolej.depot.TurntableService;
import static org.junit.jupiter.api.Assertions.*;

/** The animation names used in code exist in the packaged models, and pure helpers map events correctly. */
class RailAnimationContractTest {
    private static JsonObject model(String path) throws Exception {
        try(var in=RailAnimationContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in,path);return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static Set<String> animations(String path) throws Exception {
        Set<String> names=new HashSet<>();
        for(var a:model(path).getAsJsonArray("animations"))names.add(a.getAsJsonObject().get("name").getAsString());
        return names;
    }
    @Test void locomotiveHasEveryAnimationTheStateMachineUses() throws Exception {
        var names=animations("/models/kolej_parowoz.bbmodel");
        for(var loop:LocomotiveAnimator.Loop.values())if(loop.animation!=null)assertTrue(names.contains(loop.animation),loop.animation);
        assertTrue(names.containsAll(List.of("start","brake","whistle")));
    }
    @Test void everyVehicleRunsBothWaysAndDoorsExistWhereUsed() throws Exception {
        for(String n:List.of("parowoz","tender","pasazerski","salonowy","kryty","weglarka","platforma","cysterna","hamulcowy"))
            assertTrue(animations("/models/kolej_"+n+".bbmodel").containsAll(List.of("run","run_reverse")),n);
        assertTrue(animations("/models/"+TrainModels.FREIGHT_DOOR_MODEL+".bbmodel").contains("doors"));
        assertTrue(animations("/models/kolej_pasazerski.bbmodel").contains("doors"));
    }
    @Test void supplyFixturesAndLeverHaveTheirAnimations() throws Exception {
        for(SupplyKind kind:SupplyKind.values()) {
            var names=animations("/infrastructure-models/"+kind.model+".bbmodel");
            if(kind==SupplyKind.ZURAW)assertEquals(Set.of("service_pz","service_nz"),names);else assertEquals(Set.of("service"),names);
        }
        assertEquals(Set.of("throw_reverse","throw_normal"),animations("/infrastructure-models/"+TrainModels.LEVER+".bbmodel"));
        assertEquals(Set.of("time"),animations("/infrastructure-models/kolej_zegar_stacyjny.bbmodel"));
        // Turntable bridge follows the real angle via tracker rotation; the semaphore arm via pose modifiers.
        assertEquals(Set.of(),animations("/infrastructure-models/"+TurntableService.BRIDGE_MODEL+".bbmodel"));
        assertEquals(Set.of(),animations("/infrastructure-models/"+SemaphoreModels.MODEL+".bbmodel"));
    }
    @Test void craneSwingsTowardsTheTenderSide() {
        // Unrotated crane: local +Z = world +Z.
        assertEquals("service_pz",ServiceAnimation.name(ServiceAnimation.CRANE,0,0,4));
        assertEquals("service_nz",ServiceAnimation.name(ServiceAnimation.CRANE,0,0,-4));
        // Yaw 90: local +Z = world -X.
        assertEquals("service_pz",ServiceAnimation.name(ServiceAnimation.CRANE,1,-4,0));
        assertEquals("service_nz",ServiceAnimation.name(ServiceAnimation.CRANE,1,4,0));
        assertEquals("service",ServiceAnimation.name("kolej_pompa_reczna",2,1,1));
    }
    @Test void localAxesMatchSupplyKindRotation() {
        // SupplyKind quarter 1 maps local (x,z) to world (-z,x).
        double x=2,z=.5,wx=-z,wz=x;
        assertEquals(x,ServiceAnimation.localX(90,wx,wz),1e-9);assertEquals(z,ServiceAnimation.localZ(90,wx,wz),1e-9);
    }
    @Test void leverStandsBesideTheTrackOnTheRight() {
        var p=ServiceAnimation.lever(.5,.5,0,3.2);   // travelling +Z: right hand is -X
        assertEquals(-2.7,p[0],1e-9);assertEquals(.5,p[1],1e-9);assertEquals(90,p[2],1e-9);
        var q=ServiceAnimation.lever(0,0,90,3);       // travelling -X: right hand is -Z
        assertEquals(0,q[0],1e-9);assertEquals(-3,q[1],1e-9);
    }
    @Test void freightDoorRequestsAreStampedPerTrain() {
        AnimationRequests.clear();
        assertEquals(0,AnimationRequests.freightDoorsStamp("p1"));
        AnimationRequests.freightDoors("p1");long first=AnimationRequests.freightDoorsStamp("p1");
        assertTrue(first>0);AnimationRequests.freightDoors("p1");assertTrue(AnimationRequests.freightDoorsStamp("p1")>first);
        assertEquals("p1",AnimationRequests.trainOfCar("p1/car3"));assertNull(AnimationRequests.trainOfCar("p1/engine"));
        assertEquals("a/b",AnimationRequests.trainOfCar("a/b/car0"));
        AnimationRequests.freightDoors(null);AnimationRequests.clear();
    }
    @Test void semaphoreArmMovesSmoothlyAndEndsExactly() {
        var pose=SemaphorePose.STOP;int steps=0;
        while(!pose.settled(true)){var next=pose.toward(true,SemaphorePose.STEP);assertTrue(next.angle()>pose.angle());pose=next;steps++;assertTrue(steps<20);}
        assertEquals(8,steps);assertSame(SemaphorePose.CLEAR,pose);
        // Every intermediate pose still rotates around the real hinge (hinge point fixed).
        var mid=SemaphorePose.STOP.toward(true,SemaphorePose.STEP).toward(true,SemaphorePose.STEP);
        assertArrayEquals(new double[]{0,SemaphorePose.HINGE},mid.transform(0,SemaphorePose.HINGE),1e-6);
        // Reversal mid-way returns to STOP.
        var back=mid;while(!back.settled(false))back=back.toward(false,SemaphorePose.STEP);
        assertSame(SemaphorePose.STOP,back);
        // Modifiers act in BetterModel bone space (model X negated, verified on Paper): mirrored pose == model pose.
        for(var p:List.of(SemaphorePose.STOP,mid,SemaphorePose.CLEAR))for(double[] q:new double[][]{{34/16.0,77.5/16},{0,SemaphorePose.HINGE},{34/16.0,80/16.0}}) {
            var model=p.transform(q[0],q[1]);var bone=p.boneTransform(-q[0],q[1]);
            assertEquals(-model[0],bone[0],1e-6);assertEquals(model[1],bone[1],1e-6);
        }
        assertTrue(SemaphorePose.CLEAR.boneTransform(-34/16.0,77.5/16)[1]>SemaphorePose.HINGE+1.4,"CLEAR raises the arm (upper quadrant)");
        assertEquals(0,SemaphorePose.at(Double.NaN).angle());assertEquals((float)SemaphorePose.CLEAR_ANGLE,SemaphorePose.at(9).angle());
    }
}
