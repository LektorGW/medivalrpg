// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.steam;

/**
 * Technical data of a steam locomotive with its tender (config {@code lokomotywa.*}). SI-ish units: tonnes, kN, kW,
 * m/s (1 block = 1 m), kilograms of coal, litres of water, bar.
 *
 * @param massTonnes        locomotive + empty tender
 * @param adhesiveTonnes    mass on the driving wheels (limits tractive force by adhesion)
 * @param maxPressure       working boiler pressure (safety valve opens above)
 * @param warmupSeconds     time from cold to working pressure with the fire lit and no steam drawn
 * @param maxTractiveKn     starting tractive effort at full pressure and full throttle
 * @param maxPowerKw        cylinder power at full pressure and full throttle
 * @param maxSpeed          design speed (m/s)
 * @param boilerWater       boiler water capacity (L)
 * @param coalCapacity      tender coal capacity (kg)
 * @param waterCapacity     tender water capacity (L)
 * @param coalPerMj         coal burnt per MJ of work at the wheels (kg)
 * @param waterPerMj        water evaporated per MJ of work (L)
 * @param idleCoalPerMinute coal burnt per minute just to keep the fire (kg)
 * @param injectorRate      boiler feed from the tender (L/s)
 * @param dryDamagePerSecond boiler damage per second when firing a dry boiler (0..100 scale; 0 = disabled)
 * @param coolingPerSecond  fraction of pressure lost per second without fire
 * @param serviceBrake      service brake deceleration (m/s²) at full brake
 * @param emergencyBrake    emergency brake deceleration (m/s²)
 */
public record LocomotiveSpec(
    double massTonnes,
    double adhesiveTonnes,
    double maxPressure,
    double warmupSeconds,
    double maxTractiveKn,
    double maxPowerKw,
    double maxSpeed,
    double boilerWater,
    double coalCapacity,
    double waterCapacity,
    double coalPerMj,
    double waterPerMj,
    double idleCoalPerMinute,
    double injectorRate,
    double dryDamagePerSecond,
    double coolingPerSecond,
    double serviceBrake,
    double emergencyBrake
) {

    public static LocomotiveSpec defaults() {
        return new LocomotiveSpec(70, 36, 12, 150, 95, 650, 16, 3000, 500, 4000,
            3.0, 22, 1.2, 12, 4, 0.004, 0.7, 1.4);
    }

    /** Pressure (bar) needed before the locomotive may start moving. */
    public double minWorkingPressure() {
        return this.maxPressure * 0.35;
    }
}
