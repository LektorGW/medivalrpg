// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.ArrayList;
import java.util.List;
import static pl.medivalrpg.armia.visual.ModelAction.Kind.HOLD;
import static pl.medivalrpg.armia.visual.ModelAction.Kind.LOOP;

/**
 * Pure animation timing of one siege engine. The release (or ram impact) frame sits at the start of the shot clip,
 * so the projectile the game logic launches on the same tick leaves the sling/bucket/muzzle together with the
 * model. The reload clip is stretched to end exactly when the logic allows the next shot.
 */
public final class SiegeAnimator {
    public static final double ROLLING_SPEED = 0.02; // blocks per tick
    public static final float MIN_RELOAD_SPEED = 0.2f, MAX_RELOAD_SPEED = 4f;

    private final ModelCatalog.Engine engine;
    private long reloadAt = -1, lastTick = Long.MIN_VALUE, lastShot = Long.MIN_VALUE;
    private float reloadSpeed = 1, rollSpeed;
    private boolean rolling, shotRunning;
    private double lastX, lastZ;

    public SiegeAnimator(final ModelCatalog.Engine engine) {
        if (engine == null) throw new IllegalArgumentException("Unknown engine model");
        this.engine = engine;
    }

    /** The logic fired on this tick; {@code reloadTicks} is the time until it allows the next shot. */
    public List<ModelAction> shot(final long tick, final long reloadTicks) {
        final List<ModelAction> out = new ArrayList<>();
        out.add(ModelAction.stop(ModelCatalog.RELOAD));
        out.add(ModelAction.stop(this.engine.shotAnimation()));
        out.add(ModelAction.start(this.engine.shotAnimation(), HOLD, 1, ModelAction.priority(this.engine.shotAnimation())));
        final long shotTicks = shotTicks(this.engine);
        this.reloadSpeed = reloadSpeed(this.engine, reloadTicks);
        this.reloadAt = tick + shotTicks;
        this.lastShot = tick;
        this.shotRunning = true;
        return out;
    }

    public static long shotTicks(final ModelCatalog.Engine engine) {
        return Math.max(1, Math.round(engine.shotLength() * 20));
    }

    /** Playback speed that makes the reload clip finish when the next shot is allowed. */
    public static float reloadSpeed(final ModelCatalog.Engine engine, final long reloadTicks) {
        final long available = reloadTicks - shotTicks(engine);
        if (available <= 0) return MAX_RELOAD_SPEED;
        final float speed = (float) (engine.reloadLength() * 20 / available);
        return Math.max(MIN_RELOAD_SPEED, Math.min(MAX_RELOAD_SPEED, speed));
    }

    public List<ModelAction> update(final long tick, final double x, final double z) {
        final List<ModelAction> out = new ArrayList<>();
        if (this.lastTick == Long.MIN_VALUE) {
            this.remember(tick, x, z);
            return out;
        }
        if (tick <= this.lastTick) return out;
        if (this.reloadAt >= 0 && tick >= this.reloadAt) {
            out.add(ModelAction.stop(this.engine.shotAnimation()));
            out.add(ModelAction.start(ModelCatalog.RELOAD, HOLD, this.reloadSpeed, ModelAction.priority(ModelCatalog.RELOAD)));
            this.reloadAt = -1;
            this.shotRunning = false;
        }
        final double speed = Math.hypot(x - this.lastX, z - this.lastZ) / (tick - this.lastTick);
        this.rollSpeed = (float) (speed * 20 / (2 * Math.PI * this.engine.wheelRadius()));
        if (speed > ROLLING_SPEED && !this.rolling) {
            out.add(ModelAction.start(ModelCatalog.ROLL, LOOP, ModelAction.LIVE_SPEED, ModelAction.priority(ModelCatalog.ROLL)));
            this.rolling = true;
        } else if (speed <= ROLLING_SPEED && this.rolling) {
            out.add(ModelAction.stop(ModelCatalog.ROLL));
            this.rolling = false;
        }
        this.remember(tick, x, z);
        return out;
    }

    private void remember(final long tick, final double x, final double z) {
        this.lastTick = tick; this.lastX = x; this.lastZ = z;
    }

    /** Loops of the 1 s wheel clip per second at the current ground speed. */
    public float rollSpeed() { return this.rollSpeed; }
    public boolean rolling() { return this.rolling; }
    public boolean shotRunning() { return this.shotRunning; }
    public long reloadAt() { return this.reloadAt; }
    public long lastShot() { return this.lastShot; }
    public float pendingReloadSpeed() { return this.reloadSpeed; }
    public ModelCatalog.Engine engine() { return this.engine; }
}
