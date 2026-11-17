// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.steam;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteamModelTest {
    private final LocomotiveSpec spec=LocomotiveSpec.defaults();
    @Test void cannotPullBeforeMinimumWorkingPressure(){SteamState s=SteamState.cold(spec);s.fire=true;s.throttle=1;s.pressure=spec.minWorkingPressure()-.1;SteamModel.step(spec,s,.1,0,70);assertEquals(0,s.speed);assertEquals(0,s.lastForceKn);}
    @Test void coldBoilerWarmsAndConsumesFuel() {
        SteamState s=SteamState.cold(spec);s.fire=true;double coal=s.coal;
        for(int i=0;i<1600;i++)SteamModel.step(spec,s,.1,0,150);
        assertTrue(s.ready(spec));assertTrue(s.coal<coal);assertEquals(0,s.speed);assertTrue(s.pressure<=spec.maxPressure());
    }
    @Test void trainUsesWaterAndPullsLessOnSteepGrade() {
        SteamState flat=SteamState.cold(spec);flat.fire=true;flat.pressure=12;flat.throttle=1;
        SteamState grade=SteamState.cold(spec);grade.fire=true;grade.pressure=12;grade.throttle=1;
        double water=flat.water+flat.boilerWater;
        for(int i=0;i<500;i++){SteamModel.step(spec,flat,.1,0,200);SteamModel.step(spec,grade,.1,.04,200);}
        assertTrue(flat.speed>grade.speed);assertTrue(flat.water+flat.boilerWater<water);
        assertTrue(flat.speed<=spec.maxSpeed()*1.05);
    }
    @Test void dryFiringEventuallyFailsAndNoFuelExtinguishes() {
        SteamState s=SteamState.cold(spec);s.boilerWater=0;s.water=0;s.fire=true;
        for(int i=0;i<300;i++)SteamModel.step(spec,s,.1,0,70);
        assertTrue(s.failed);assertFalse(s.fire);assertEquals(100,s.damage);
        s=SteamState.cold(spec);s.coal=0;s.fire=true;SteamModel.step(spec,s,.1,0,70);assertFalse(s.fire);
    }
    @Test void brakingCurveStopsBeforeSignal() {
        SteamState s=SteamState.cold(spec);s.pressure=12;s.speed=12;s.fire=true;
        double x=0;
        for(int i=0;i<1000;i++){AutoDriver.drive(spec,s,12,150-x);x+=SteamModel.step(spec,s,.1,0,150);}
        assertTrue(x<=150,"signal overrun: "+x);assertTrue(s.speed<.01);
    }
    @Test void manualDriverCannotRunRedSignal() {
        SteamState s=SteamState.cold(spec);s.speed=15;s.throttle=1;
        assertTrue(AutoDriver.supervise(spec,s,5));assertEquals(0,s.throttle);assertTrue(s.emergency);
    }
}
