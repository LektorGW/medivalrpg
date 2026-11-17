// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.steam;

/**
 * Pure steam locomotive physics, stepped every simulation tick.
 *
 * <ul>
 *   <li><b>Boiler</b>: with the fire lit, coal in the tender and water in the boiler, pressure rises towards
 *   {@code maxPressure} (from cold in {@code warmupSeconds}); drawing steam (power) lowers it, so sustained full power
 *   on a long grade drains pressure. Above the maximum the safety valve vents. Without fire the pressure decays.</li>
 *   <li><b>Fuel</b>: coal = idle burn + work × coalPerMj; water = work × waterPerMj (boiler), refilled by the injector
 *   from the tender. Out of coal → fire dies → pressure falls → the train stops. Firing a dry boiler damages it; at
 *   100 damage the boiler has failed (repair needed).</li>
 *   <li><b>Motion</b>: tractive force limited by pressure × throttle, power/speed and adhesion; resistance = rolling +
 *   air + gradient; brakes; a stopped train never rolls backwards (handbrake).</li>
 * </ul>
 */
public final class SteamModel {

    public static final double G = 9.81;
    /** Rolling resistance coefficient. */
    public static final double ROLLING = 0.0025;
    /** Air resistance kN per (m/s)². */
    public static final double AIR = 0.0055;
    /** Wheel-rail adhesion coefficient. */
    public static final double ADHESION = 0.25;

    private SteamModel() {
    }

    /**
     * Advances the state by {@code dt} seconds.
     *
     * @param gradient   rise per horizontal metre along the direction of travel (+ uphill)
     * @param trainTonnes total mass of the train (locomotive, tender, cars and load)
     * @return distance travelled (m)
     */
    public static double step(final LocomotiveSpec spec, final SteamState s, final double dt, final double gradient, final double trainTonnes) {
        final double mass = Math.max(1, trainTonnes);
        s.venting = false;
        s.dryFiring = false;

        // ---- fire & boiler
        if (s.failed) {
            s.fire = false;
        }
        if (s.fire && s.coal <= 0) {
            s.fire = false;
        }
        final double pFrac = spec.maxPressure() <= 0 ? 0 : Math.max(0, s.pressure / spec.maxPressure());

        // ---- tractive force
        double force = 0;
        if (s.ready(spec) && s.throttle > 0 && !s.emergency) {
            final double byPressure = spec.maxTractiveKn() * pFrac * s.throttle;
            final double byPower = spec.maxPowerKw() * pFrac * s.throttle / Math.max(s.speed, 1.0);
            final double byAdhesion = spec.adhesiveTonnes() * G * ADHESION;
            force = Math.min(Math.min(byPressure, byPower), byAdhesion);
            if (s.speed >= spec.maxSpeed()) {
                force = 0;
            }
        }

        // ---- resistance & brakes (kN; tonnes × m/s² = kN)
        final double grade = mass * G * gradient;
        final double rolling = mass * G * ROLLING + AIR * s.speed * s.speed;
        final double brakeDecel = s.emergency ? spec.emergencyBrake() : spec.serviceBrake() * clamp01(s.brake);
        final double brakeForce = brakeDecel * mass;

        double v = s.speed;
        // standing: rolling resistance acts as static friction and the brakes hold fully
        final double net = v > 0.01 ? force - grade - rolling - brakeForce
            : force - grade - rolling - (s.brake > 0 || s.emergency ? spec.serviceBrake() * mass * 2 : 0);
        if (v <= 0.01 && net <= 0) {
            v = 0; // standing: handbrake holds, nothing happens
        } else {
            v = v + net / mass * dt;
            if (v < 0) {
                v = 0;
            }
        }
        v = Math.min(v, spec.maxSpeed() * 1.05);
        final double distance = (s.speed + v) / 2 * dt;
        s.speed = v;

        // ---- energy, fuel and water
        final double workMj = Math.max(0, force * distance) / 1000.0; // kN·m = kJ
        s.lastForceKn = force;
        s.lastPowerKw = dt > 0 ? workMj * 1000 / dt : 0;
        if (s.fire) {
            final double burn = spec.idleCoalPerMinute() / 60.0 * dt + workMj * spec.coalPerMj();
            s.coal = Math.max(0, s.coal - burn);
        }
        s.boilerWater = Math.max(0, s.boilerWater - workMj * spec.waterPerMj());

        // injector: keep the boiler between 75 % and 95 %
        if (s.boilerWater < spec.boilerWater() * 0.95 && s.water > 0) {
            final double feed = Math.min(spec.injectorRate() * dt, Math.min(s.water, spec.boilerWater() - s.boilerWater));
            s.water -= feed;
            s.boilerWater += feed;
        }

        // pressure
        final double heatRate = spec.warmupSeconds() <= 0 ? spec.maxPressure() : spec.maxPressure() / spec.warmupSeconds();
        if (s.fire && s.boilerWater > 0) {
            s.pressure += heatRate * dt;
        } else {
            s.pressure -= s.pressure * spec.coolingPerSecond() * dt;
        }
        // steam draw: full power continuously uses 120 % of the heating rate
        if (spec.maxPowerKw() > 0) {
            s.pressure -= heatRate * 1.2 * (s.lastPowerKw / spec.maxPowerKw()) * dt;
        }
        if (s.pressure > spec.maxPressure()) {
            s.pressure = spec.maxPressure();
            s.venting = s.fire;
            if (s.venting) {
                s.boilerWater = Math.max(0, s.boilerWater - 0.5 * dt);
            }
        }
        s.pressure = Math.max(0, s.pressure);

        // dry firing
        if (s.fire && s.boilerWater <= 0) {
            s.dryFiring = true;
            if (spec.dryDamagePerSecond() > 0) {
                s.damage = Math.min(100, s.damage + spec.dryDamagePerSecond() * dt);
                if (s.damage >= 100) {
                    s.failed = true;
                    s.fire = false;
                }
            }
        }
        return distance;
    }

    /** Distance (m) needed to stop from {@code speed} with deceleration {@code decel}. */
    public static double brakingDistance(final double speed, final double decel) {
        return decel <= 0 ? Double.MAX_VALUE : speed * speed / (2 * decel);
    }

    /** Highest speed from which the train can still stop within {@code distance} at {@code decel}. */
    public static double maxSpeedToStop(final double distance, final double decel) {
        return distance <= 0 ? 0 : Math.sqrt(2 * decel * distance);
    }

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }
}
