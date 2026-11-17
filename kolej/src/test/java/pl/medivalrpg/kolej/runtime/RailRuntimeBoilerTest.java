// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.schedule.Timetable;
import pl.medivalrpg.kolej.steam.*;
import static org.junit.jupiter.api.Assertions.*;

class RailRuntimeBoilerTest {
    private final LocomotiveSpec spec=LocomotiveSpec.defaults();
    @Test void movingOrStoppedMidlineCannotReceiveFuelFromStaleStationNode(){
        var g=RailRuntimeSafetyTest.graph(false);var r=RailRuntimeSafetyTest.route(g,false);Train t=new Train();t.node="a";t.running=true;t.distance=50;t.steam.coal=0;
        assertFalse(RailRuntime.atServicePoint(t,r,g));
        assertEquals(0,t.steam.coal);
        t.distance=0;t.steam.speed=2;assertFalse(RailRuntime.atServicePoint(t,r,g));
        t.steam.speed=0;assertTrue(RailRuntime.atServicePoint(t,r,g));
        t.yardBase="base";assertFalse(RailRuntime.atServicePoint(t,r,g));
        t.yardBase=null;t.node="b";t.distance=r.length();assertTrue(RailRuntime.atServicePoint(t,r,g));
    }
    @Test void idleFireDiesAfterThreeMinutesAndCannotConsumeWholeTender(){
        var care=new RailRuntime.BoilerCare();var s=SteamState.cold(spec);s.fire=true;
        for(long now=0;now<3_600_000;now+=100){
            if(care.extinguish(now,false))s.fire=false;
            SteamModel.step(spec,s,.1,0,70);
            if(now<180_000)assertTrue(s.fire);else assertFalse(s.fire);
        }
        assertTrue(s.coal>496);assertEquals(0,s.speed);
    }
    @Test void scheduledWarmupOrPresentDriverResetsIdleTimeout(){
        var care=new RailRuntime.BoilerCare();assertFalse(care.extinguish(0,false));
        assertFalse(care.extinguish(179_000,true));assertFalse(care.extinguish(300_000,true));
        assertFalse(care.extinguish(400_000,false));assertFalse(care.extinguish(579_999,false));assertTrue(care.extinguish(580_000,false));
    }
    @Test void warmupOnlyWithinFourMinutesOfRealUnconsumedDeparture(){
        Train t=new Train();t.node="a";t.line="line";Line l=new Line();l.id="line";l.active=true;l.stops=List.of("a","b");l.timetable=Timetable.at(List.of(9*60));
        LocalDateTime now=LocalDateTime.of(2026,10,28,8,55);long wall=now.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertFalse(RailRuntime.warmupDue(t,l,now,wall));assertTrue(RailRuntime.warmupDue(t,l,now.plusMinutes(1),wall+60_000));
        assertFalse(RailRuntime.warmupDue(t,l,now.plusMinutes(10),wall+600_000));
        assertTrue(RailRuntime.warmupDue(t,l,now.plusMinutes(5),wall+300_000));l.lastDepartureMinute=(wall+300_000)/60_000;
        assertFalse(RailRuntime.warmupDue(t,l,now.plusMinutes(5),wall+300_000));
        l.active=false;assertFalse(RailRuntime.warmupDue(t,l,now.plusMinutes(1),wall+60_000));
        l.active=true;l.timetable=new Timetable();t.node="b";assertFalse(RailRuntime.warmupDue(t,l,now,wall));
    }
}
