// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.steam;

/** Mutable state of a locomotive's boiler, tender and motion. Persisted with the train. */
public final class SteamState {

    public boolean fire;
    /** Boiler pressure (bar). */
    public double pressure;
    /** Water in the boiler (L). */
    public double boilerWater;
    /** Coal in the tender (kg). */
    public double coal;
    /** Water in the tender (L). */
    public double water;
    /** Boiler damage 0..100; 100 = failed. */
    public double damage;
    public boolean failed;
    /** Speed (m/s, never negative: trains always move "forward" along their route). */
    public double speed;
    /** Throttle 0..1. */
    public double throttle;
    /** Brake 0..1. */
    public double brake;
    public boolean emergency;

    // ---- last step (read-only outputs, for displays and effects) ----
    public transient double lastForceKn;
    public transient double lastPowerKw;
    public transient boolean venting;
    public transient boolean dryFiring;

    public static SteamState cold(final LocomotiveSpec spec) {
        final SteamState s = new SteamState();
        s.boilerWater = spec.boilerWater() * 0.8;
        s.coal = spec.coalCapacity();
        s.water = spec.waterCapacity();
        return s;
    }

    public boolean ready(final LocomotiveSpec spec) {
        return !this.failed && this.pressure >= spec.minWorkingPressure();
    }

    public double coalFraction(final LocomotiveSpec spec) {
        return spec.coalCapacity() <= 0 ? 0 : this.coal / spec.coalCapacity();
    }

    public double waterFraction(final LocomotiveSpec spec) {
        return spec.waterCapacity() <= 0 ? 0 : this.water / spec.waterCapacity();
    }
}
