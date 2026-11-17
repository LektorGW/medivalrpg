// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

/** Pure placement, visibility and collider rules of the model layer. */
public final class VisualRules {
    /** Shown models stay until {@code hide}; hidden ones appear inside {@code show} (hysteresis, no flicker). */
    public static boolean visible(final boolean shown, final double distanceSq, final double show, final double hide) {
        if (!Double.isFinite(distanceSq) || distanceSq < 0) return false;
        final double limit = shown ? Math.max(show, hide) : show;
        return distanceSq < limit * limit;
    }

    public static boolean admit(final int live, final int limit) { return limit > 0 && live < limit; }

    /** Minecraft yaw → horizontal forward unit vector (yaw 0 faces +Z). */
    public static double[] forward(final float yaw) {
        final double r = Math.toRadians(yaw);
        return new double[] {-Math.sin(r), Math.cos(r)};
    }

    /** Axis-aligned collider box of a deployed engine: {minX,minY,minZ,maxX,maxY,maxZ}. */
    public static double[] colliderBox(final double x, final double y, final double z, final float yaw,
                                       final ModelCatalog.Engine engine) {
        final double[] f = forward(yaw);
        final double cx = x + f[0] * engine.colliderForward(), cz = z + f[1] * engine.colliderForward();
        final double h = engine.colliderSize() / 2;
        return new double[] {cx - h, y, cz - h, cx + h, y + engine.colliderSize(), cz + h};
    }

    public static boolean overlaps(final double[] a, final double[] b, final double margin) {
        return a[0] - margin < b[3] && a[3] + margin > b[0] && a[1] - margin < b[4] && a[4] + margin > b[1]
            && a[2] - margin < b[5] && a[5] + margin > b[2];
    }

    /** The collider exists only for a deployed (still) engine whose box holds nobody: never a trap. */
    public static boolean colliderWanted(final boolean enabled, final boolean shown, final long stillTicks,
                                         final int requiredTicks, final boolean occupied) {
        return enabled && shown && stillTicks >= requiredTicks && !occupied;
    }

    public static boolean still(final double moved, final float yawDelta) {
        return moved < 0.01 && Math.abs(ShipAnimator.wrap(yawDelta)) < 0.5f;
    }

    private VisualRules() { }
}
