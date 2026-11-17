// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

/**
 * Pure per-locomotive state machine: driving state → BetterModel animations and effect events.
 * No Bukkit/BetterModel types, so it is unit-tested offline. Times are game ticks.
 */
public final class LocomotiveAnimator {
    /** Mutually exclusive looping animation. NONE = cold engine standing still. */
    public enum Loop {
        NONE(null), IDLE("idle"), RUN("run"), RUN_REVERSE("run_reverse");
        public final String animation;
        Loop(String animation) { this.animation = animation; }
    }
    /** start/whistle are one-shot events for this tick; braking is a held state. */
    public record Frame(Loop loop, boolean braking, boolean start, boolean whistle, float wheelSpeed, double speed) {
        public boolean moving() { return loop == Loop.RUN || loop == Loop.RUN_REVERSE; }
    }
    public static final double WHEEL_RADIUS = .5625, START_MOVING = .05, STOP_MOVING = .03;
    public static final long START_COOLDOWN = 100, WHISTLE_COOLDOWN = 600;
    private boolean moving, braking;
    private double lastAbs;
    private long lastStart = Long.MIN_VALUE / 2, lastWhistle = Long.MIN_VALUE / 2;

    /**
     * @param speed signed m/s; negative = reversing (yard manoeuvres)
     * @param brake train brake 0..1
     * @param fire firebox lit
     * @param mainline true on a timetable/route run (whistle before departure); false in the depot yard
     */
    public Frame update(long tick, double speed, double brake, boolean fire, boolean mainline) {
        double abs = Double.isFinite(speed) ? Math.abs(speed) : 0;
        if (!Double.isFinite(speed)) speed = 0;
        boolean wasMoving = moving;
        moving = moving ? abs > STOP_MOVING : abs > START_MOVING;
        boolean start = false, whistle = false;
        if (moving && !wasMoving) {
            if (tick - lastStart >= START_COOLDOWN) { start = true; lastStart = tick; }
            if (mainline && speed > 0 && tick - lastWhistle >= WHISTLE_COOLDOWN) { whistle = true; lastWhistle = tick; }
        }
        boolean decelerating = abs < lastAbs - 1e-4;
        if (!moving || abs < .2 || brake < .1) braking = false;
        else if (!braking && brake >= .2 && abs > .4 && decelerating) braking = true;
        lastAbs = abs;
        Loop loop = moving ? (speed < 0 ? Loop.RUN_REVERSE : Loop.RUN) : fire ? Loop.IDLE : Loop.NONE;
        return new Frame(loop, braking, start, whistle, wheelSpeed(abs, WHEEL_RADIUS), speed);
    }
    /** BetterModel speed multiplier for a 1 s loop = one wheel revolution. */
    public static float wheelSpeed(double speed, double radius) {
        if (!Double.isFinite(speed) || radius <= 0) return 0;
        return (float) (Math.abs(speed) / (Math.PI * 2 * radius));
    }
    /** Fallback frame without history (BetterModel absent): smoke/sound only. */
    public static Frame stateless(double speed, boolean fire) {
        double abs = Double.isFinite(speed) ? Math.abs(speed) : 0;
        Loop loop = abs > START_MOVING ? (speed < 0 ? Loop.RUN_REVERSE : Loop.RUN) : fire ? Loop.IDLE : Loop.NONE;
        return new Frame(loop, false, false, false, wheelSpeed(abs, WHEEL_RADIUS), Double.isFinite(speed) ? speed : 0);
    }
    /**
     * Direction of travel from two successive poses: +1 forwards, -1 reversing, 0 unknown/standing.
     * Yaw uses the Minecraft convention: forward = (-sin yaw, cos yaw).
     */
    public static int direction(double fromX, double fromZ, double toX, double toZ, double yaw) {
        double dx = toX - fromX, dz = toZ - fromZ;
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || !Double.isFinite(yaw) || Math.hypot(dx, dz) < 1e-4) return 0;
        double a = Math.toRadians(yaw), along = -Math.sin(a) * dx + Math.cos(a) * dz;
        return along > 1e-5 ? 1 : along < -1e-5 ? -1 : 0;
    }
}
