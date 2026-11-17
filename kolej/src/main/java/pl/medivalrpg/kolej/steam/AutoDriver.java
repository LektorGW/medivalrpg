// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.steam;

/**
 * Automatic driving (NPC driver or timetable mode) and the safety supervision of human drivers. Every tick it sets
 * throttle and brake so the train runs at {@code targetSpeed} and always stays below the braking curve towards the
 * next stop point ({@code distanceToStop}: end of the movement authority or the platform stop).
 */
public final class AutoDriver {

    /** Margin before the stop point (m). */
    public static final double MARGIN = 2.0;

    private AutoDriver() {
    }

    /** Allowed speed now: the lower of the target and the braking curve to the stop point. */
    public static double allowedSpeed(final double targetSpeed, final double distanceToStop, final double decel) {
        return Math.min(targetSpeed, SteamModel.maxSpeedToStop(distanceToStop - MARGIN, decel * 0.85));
    }

    /** Automatic control: sets throttle and brake of {@code s}. */
    public static void drive(final LocomotiveSpec spec, final SteamState s, final double targetSpeed, final double distanceToStop) {
        final double allowed = allowedSpeed(targetSpeed, distanceToStop, spec.serviceBrake());
        final double v = s.speed;
        if (distanceToStop <= MARGIN + 0.3) {
            s.throttle = 0;
            s.brake = 1;
            return;
        }
        if (v > allowed + 0.3) {
            s.throttle = 0;
            s.brake = Math.min(1, s.brake + 0.1 + (v - allowed) * 0.3);
        } else if (v < allowed - 0.6) {
            s.brake = 0;
            s.throttle = Math.min(1, s.throttle + 0.05);
        } else {
            s.brake = Math.max(0, s.brake - 0.1);
            s.throttle = Math.max(0, Math.min(1, s.throttle - 0.02));
        }
    }

    /** Also supervises manual regulators; an explicit shunting restriction cannot be overridden. */
    public static boolean enforceSpeed(SteamState state,double allowed) {
        if(!Double.isFinite(allowed) || allowed<0)allowed=0;
        if(state.speed>allowed+.2){state.throttle=0;state.brake=1;return true;}
        return false;
    }

    /**
     * Supervision of a human driver (like a train-stop system): returns true when the train would overrun the stop
     * point at its current speed, in which case the brakes are forced on and the throttle closed.
     */
    public static boolean supervise(final LocomotiveSpec spec, final SteamState s, final double distanceToStop) {
        final double limit = SteamModel.maxSpeedToStop(distanceToStop - MARGIN * 0.5, spec.serviceBrake());
        if (s.speed > limit + 0.2 || distanceToStop <= MARGIN * 0.5) {
            s.throttle = 0;
            s.brake = 1;
            if (s.speed > SteamModel.maxSpeedToStop(distanceToStop, spec.serviceBrake()) + 0.5) {
                s.emergency = true;
            }
            return true;
        }
        if (s.emergency && s.speed < 0.05) {
            s.emergency = false;
        }
        return false;
    }
}
