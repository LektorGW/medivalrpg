// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.data.RailData.Car;
import pl.medivalrpg.kolej.runtime.RailRuntime;

class LaunchPlanTest {
    static YamlConfiguration shipped() {
        try (var in = LaunchPlanTest.class.getResourceAsStream("/uruchomienie.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {throw new IllegalStateException(e);}
    }

    @Test void shippedPlanIsValidAndPricedFromConfig() {
        var plans = LaunchPlan.parseAll(shipped(), 6);
        var p = plans.get("glowna");
        assertNotNull(p);
        assertEquals(List.of("baza_krolewska", "hub-j01"), p.line().stops());
        assertEquals(20, p.line().fare());
        assertEquals(30, p.line().returnAfterMinutes());
        assertEquals(17, p.line().timetable().minutesOfDay().size(), "06:00..22:00 every 60 min");
        var t = p.trains().getFirst();
        assertEquals(List.of(Car.PASAZERSKI), t.cars());
        assertNotNull(t.stoker());
        assertEquals(4, p.fixtures().size());
        assertEquals(2, p.boards().size());
        var costs = p.costs(new LaunchPlan.Prices(20000, 2000, 500, 100, 60));
        assertEquals(20000, costs.locomotives());
        assertEquals(2000, costs.cars());
        assertEquals(1000, costs.hires());
        assertEquals(160, costs.firstWages());
        assertEquals(23160, costs.total());
    }

    @Test void refusesUnsafeOrIncompletePlans() {
        var yaml = shipped();
        yaml.set("plany.glowna.linia.bilet", 20000);
        assertThrows(IllegalArgumentException.class, () -> LaunchPlan.parseAll(yaml, 6));
        var noCoal = shipped();
        var fixtures = new ArrayList<>(noCoal.getMapList("plany.glowna.infrastruktura"));
        fixtures.removeIf(m -> "spawn-wegiel".equals(m.get("id")));
        noCoal.set("plany.glowna.infrastruktura", fixtures);
        assertThrows(IllegalArgumentException.class, () -> LaunchPlan.parseAll(noCoal, 6), "every terminus needs real water and coal");
        assertThrows(IllegalArgumentException.class, () -> LaunchPlan.parseAll(shipped(), 0), "car limit");
        var sameStops = shipped();
        sameStops.set("plany.glowna.linia.przystanki", List.of("hub-j01", "hub-j01"));
        assertThrows(IllegalArgumentException.class, () -> LaunchPlan.parseAll(sameStops, 6));
        assertThrows(IllegalArgumentException.class, () -> LaunchPlan.timetable("5", "06:00", "22:00"));
        assertEquals(List.of(420, 570), LaunchPlan.timetable("07:00,09:30", "", "").minutesOfDay());
        assertEquals("L-krolewski-1-m", LaunchPlan.crewId("krolewski-1", false));
        assertEquals("L-krolewski-1-p", LaunchPlan.crewId("krolewski-1", true));
    }

    @Test void crewRolesWagesAndOldFiles() {
        var d = new RailData.Crew(); d.train = "t"; d.paid = true;
        assertTrue(d.driver(), "older files without role are engine drivers");
        var f = new RailData.Crew(); f.train = "t"; f.paid = true; f.role = RailData.Crew.STOKER;
        assertTrue(RailRuntime.crewReady(List.of(d), "t", false));
        assertFalse(RailRuntime.crewReady(List.of(d), "t", true), "fireman required by config");
        assertTrue(RailRuntime.crewReady(List.of(d, f), "t", true));
        assertFalse(RailRuntime.crewReady(List.of(f), "t", false), "a fireman cannot drive");
        d.paid = false;
        assertFalse(RailRuntime.crewReady(List.of(d, f), "t", false), "unpaid driver stops work");
        var config = new YamlConfiguration();
        assertEquals(100, RailRuntime.wage(config, d));
        assertEquals(60, RailRuntime.wage(config, f));
        config.set("firma.dzienny-zold-palacza", 75);
        assertEquals(75, RailRuntime.wage(config, f));
    }

    @Test void timedReturnFollowsTheOriginDeparture() {
        var l = new RailData.Line();
        assertEquals(0, RailRuntime.plannedReturnMillis(l), "legacy lines return immediately");
        l.returnAfterMinutes = 30; assertEquals(0, RailRuntime.plannedReturnMillis(l), "no departure yet");
        l.lastDepartureMinute = 1000;
        assertEquals((1000 + 30) * 60_000L, RailRuntime.plannedReturnMillis(l));
    }
}
