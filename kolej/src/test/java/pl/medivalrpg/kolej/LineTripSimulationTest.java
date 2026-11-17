// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.Car;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.steam.*;

/**
 * Timetable evidence for the launch plan: the same SteamModel, AutoDriver and RouteSpeed as the runtime, on the real
 * approved graph (baza_krolewska ↔ hub-j01). Gives the one-way running time and the coal/water used per trip.
 */
class LineTripSimulationTest {
    record Trip(double seconds,double coalKg,double waterL,double lengthM) {}

    static Trip simulate(TrackGraph graph,List<String> stops,List<Car> cars) {
        Route r=graph.routeThrough(stops,TrackSegment::isBuilt,true).orElseThrow();
        var spec=LocomotiveSpec.defaults();var s=SteamState.cold(spec);
        double mass=70+cars.stream().mapToDouble(c->c.tonnes).sum(),length=15+cars.size()*8;
        s.fire=true;s.pressure=spec.maxPressure(); // warmed up before the timetable minute (runtime lights the fire 4 min ahead)
        double coal0=s.coal,water0=s.water+s.boilerWater,distance=0,time=0;
        double decel=spec.serviceBrake();
        while(time<4*3600) {
            double gradient=r.path().gradientAt(distance,2);
            double d=Math.max(.05,spec.serviceBrake()+Math.min(0,gradient)*SteamModel.G);
            double remaining=Math.max(0,r.length()-distance);
            double speed=RouteSpeed.allowed(graph,r,distance,length,Math.min(12,spec.maxSpeed()),d);
            AutoDriver.drive(spec,s,speed,remaining);AutoDriver.enforceSpeed(s,speed);
            double moved=SteamModel.step(spec,s,.1,gradient,mass);
            distance=Math.min(r.length(),distance+moved);time+=.1;
            if(r.length()-distance<=2.35 && s.speed<.2)break;
            assertFalse(s.failed,"boiler failure");
        }
        assertTrue(r.length()-distance<=2.35,"train must reach the platform");
        return new Trip(time,coal0-s.coal,water0-(s.water+s.boilerWater),r.length());
    }

    @Test void realLineTimetableAndFuel() throws Exception {
        Path plan=Path.of("../deploy-rail-north-20261029/commission/commission-graph-plan.json");
        Assumptions.assumeTrue(Files.exists(plan),"approved plan not available");
        var p=new Gson().fromJson(Files.readString(plan),CommissionGraph.Plan.class);
        var graph=CommissionGraph.prepare(new TrackGraph(),p,new HashSet<>(p.sourceJobs()));
        graph.segments().forEach(s->s.status(SegmentStatus.BUILT));
        for(var option:List.of(List.of(Car.PASAZERSKI),List.of(Car.PASAZERSKI,Car.SALONOWY),List.of(Car.PASAZERSKI,Car.PASAZERSKI),List.of(Car.PASAZERSKI,Car.PASAZERSKI,Car.SALONOWY))){
            var d=simulate(graph,List.of("baza_krolewska","hub-j01"),option);var u=simulate(graph,List.of("hub-j01","baza_krolewska"),option);
            System.out.println(String.format(Locale.ROOT,"OPTION %s down %.1f min %.0f kg %.0f l | up %.1f min %.0f kg %.0f l",option,d.seconds()/60,d.coalKg(),d.waterL(),u.seconds()/60,u.coalKg(),u.waterL()));
        }
        var cars=List.of(Car.PASAZERSKI); // the launch plan's consist
        var down=simulate(graph,List.of("baza_krolewska","hub-j01"),cars);
        var up=simulate(graph,List.of("hub-j01","baza_krolewska"),cars);
        String report=String.format(Locale.ROOT,"{\"lengthM\":%.0f,\"downMin\":%.2f,\"upMin\":%.2f,\"downCoalKg\":%.1f,\"upCoalKg\":%.1f,\"downWaterL\":%.0f,\"upWaterL\":%.0f}",
            down.lengthM(),down.seconds()/60,up.seconds()/60,down.coalKg(),up.coalKg(),down.waterL(),up.waterL());
        Files.createDirectories(Path.of("build"));Files.writeString(Path.of("build/line-trip-simulation.json"),report);
        System.out.println("LINE-TRIP "+report);
        // The tender is topped up at both termini (zapasy.do-pelna-na-koncowkach); the uphill trip to the base must
        // still leave >= 20 % of a full tender (500 kg / 4000 l; the boiler's own water is an extra reserve).
        assertTrue(down.coalKg()<400 && up.coalKg()<400,report);
        assertTrue(down.waterL()<3200 && up.waterL()<3200,report);
        // The configured shuttle: departures every 60 min, return 30 min after departure.
        assertTrue(down.seconds()/60+2<=30,"return slot must follow the arrival: "+report);
        assertTrue(30+up.seconds()/60+4<=60,"next departure must follow the return trip: "+report);
    }
}
