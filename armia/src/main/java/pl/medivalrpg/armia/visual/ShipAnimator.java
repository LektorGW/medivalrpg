// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.ArrayList;
import java.util.List;
import static pl.medivalrpg.armia.visual.ModelAction.Kind.HOLD;
import static pl.medivalrpg.armia.visual.ModelAction.Kind.LOOP;

/**
 * Pure state machine of a ship model: waves and flags always, sails set once the hull really moves, furled after a
 * calm spell, flutter while sailing, anchor dropped when moored and raised on departure, rudder following the helm.
 * Only reads positions the fleet already computed; never moves anything.
 */
public final class ShipAnimator {
    public record Settings(int setAfterTicks, int furlAfterTicks, int anchorAfterTicks, boolean waves) {
        public static final Settings DEFAULT = new Settings(5, 100, 200, true);
    }
    /** Helm input: left, none, right, or unknown (AI autopilot: derived from the turn rate). */
    public enum Steer { LEFT, NONE, RIGHT, UNKNOWN }
    public static final double MOVING_SPEED = 0.015;   // blocks per tick
    public static final double SAILING_SPEED = 0.05;
    public static final float MAX_RUDDER = 28;          // degrees
    public static final float TURN_RATE = 2.4f;         // HelmRules degrees per tick

    private final Settings settings;
    private boolean started, sailsSet, flutter, anchorDown;
    private long lastTick = Long.MIN_VALUE, movingSince = -1, stillSince = -1;
    private double lastX, lastZ, speed;
    private float lastYaw, rudder;

    public ShipAnimator(final Settings settings) { this.settings = settings == null ? Settings.DEFAULT : settings; }

    public List<ModelAction> update(final long tick, final double x, final double z, final float yaw, final Steer steer) {
        final List<ModelAction> out = new ArrayList<>();
        if (!this.started) {
            this.started = true;
            if (this.settings.waves()) out.add(start(ModelCatalog.WAVES, LOOP));
            out.add(start(ModelCatalog.FLAGS, LOOP));
            out.add(start(ModelCatalog.SAILS_FURLED, HOLD));
            out.add(start(ModelCatalog.ANCHOR_DOWN, HOLD));
            this.anchorDown = true;
            this.stillSince = tick;
            this.remember(tick, x, z, yaw);
            return out;
        }
        final long dt = Math.max(1, tick - this.lastTick);
        if (tick <= this.lastTick) return out;
        final double moved = Math.hypot(x - this.lastX, z - this.lastZ);
        final float turn = wrap(yaw - this.lastYaw);
        this.speed = moved / dt;
        final boolean moving = this.speed > MOVING_SPEED || Math.abs(turn) / dt > 0.05f;
        if (moving) {
            this.stillSince = -1;
            if (this.movingSince < 0) this.movingSince = tick;
            if (this.anchorDown) {
                out.add(ModelAction.stop(ModelCatalog.ANCHOR_DOWN));
                out.add(ModelAction.stop(ModelCatalog.ANCHOR_DROP));
                out.add(start(ModelCatalog.ANCHOR_RAISE, HOLD));
                this.anchorDown = false;
            }
            if (!this.sailsSet && tick - this.movingSince >= this.settings.setAfterTicks()) {
                out.add(ModelAction.stop(ModelCatalog.SAILS_FURLED));
                out.add(ModelAction.stop(ModelCatalog.SAILS_FURL));
                out.add(start(ModelCatalog.SAILS_SET, HOLD));
                this.sailsSet = true;
            }
            if (this.sailsSet && !this.flutter && this.speed > SAILING_SPEED) {
                out.add(start(ModelCatalog.FLUTTER, LOOP));
                this.flutter = true;
            }
        } else {
            this.movingSince = -1;
            if (this.stillSince < 0) this.stillSince = tick;
            if (this.flutter) {
                out.add(ModelAction.stop(ModelCatalog.FLUTTER));
                this.flutter = false;
            }
            if (this.sailsSet && tick - this.stillSince >= this.settings.furlAfterTicks()) {
                out.add(ModelAction.stop(ModelCatalog.SAILS_SET));
                out.add(start(ModelCatalog.SAILS_FURL, HOLD));
                this.sailsSet = false;
            }
            if (!this.anchorDown && tick - this.stillSince >= this.settings.anchorAfterTicks()) {
                out.add(ModelAction.stop(ModelCatalog.ANCHOR_RAISE));
                out.add(start(ModelCatalog.ANCHOR_DROP, HOLD));
                this.anchorDown = true;
            }
        }
        final float target = switch (steer == null ? Steer.UNKNOWN : steer) {
            case LEFT -> -MAX_RUDDER;
            case RIGHT -> MAX_RUDDER;
            case NONE -> 0;
            case UNKNOWN -> MAX_RUDDER * Math.max(-1, Math.min(1, turn / dt / TURN_RATE));
        };
        this.rudder += (target - this.rudder) * Math.min(1, 0.3f * dt);
        if (Math.abs(this.rudder) < 0.05f) this.rudder = 0;
        this.remember(tick, x, z, yaw);
        return out;
    }

    private void remember(final long tick, final double x, final double z, final float yaw) {
        this.lastTick = tick; this.lastX = x; this.lastZ = z; this.lastYaw = yaw;
    }

    private static ModelAction start(final String animation, final ModelAction.Kind kind) {
        return ModelAction.start(animation, kind, 1, ModelAction.priority(animation));
    }

    static float wrap(final float degrees) {
        float d = degrees % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** Degrees, positive = rudder blade trailing edge to starboard (turning right). */
    public float rudder() { return this.rudder; }
    public boolean sailsSet() { return this.sailsSet; }
    public boolean flutter() { return this.flutter; }
    public boolean anchorDown() { return this.anchorDown; }
    public double speed() { return this.speed; }
}
