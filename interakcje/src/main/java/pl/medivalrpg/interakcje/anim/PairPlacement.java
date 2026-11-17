// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

/**
 * Where two players must stand (and which way they must look) for a two-person animation.
 * Pure math in the horizontal plane, Bukkit yaw convention (yaw 0 = +Z / south, 90 = -X / west).
 * The midpoint between the players is kept, so each of them moves as little as possible.
 */
public final class PairPlacement {

    private PairPlacement() {
    }

    /** A standing spot: position and yaw. */
    public record Spot(double x, double z, float yaw) {
        public double distanceTo(double ox, double oz) {
            return Math.hypot(x - ox, z - oz);
        }
    }

    /**
     * @param sender         the sender's spot
     * @param target         the target's spot
     * @param targetOnRight  for side by side: {@code true} = the target stands on the sender's right
     *                       (the sender plays the "partner on my right" clip)
     */
    public record Result(Spot sender, Spot target, boolean targetOnRight) {
        /** The largest distance one of the players is moved. */
        public double maxShift(double senderX, double senderZ, double targetX, double targetZ) {
            return Math.max(sender.distanceTo(senderX, senderZ), target.distanceTo(targetX, targetZ));
        }
    }

    /** Bukkit yaw looking along (dx, dz). */
    public static float yaw(double dx, double dz) {
        return normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Unit direction (dx, dz) of a Bukkit yaw. */
    public static double[] direction(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    public static float normalizeYaw(float yaw) {
        float y = yaw % 360f;
        if (y >= 180f) {
            y -= 360f;
        } else if (y < -180f) {
            y += 360f;
        }
        return y;
    }

    /**
     * Face to face {@code distance} apart, looking at each other.
     *
     * @param senderYaw used only when both stand on the same spot
     */
    public static Result face(double sx, double sz, double tx, double tz, double distance, float senderYaw) {
        double[] u = unit(tx - sx, tz - sz, senderYaw);
        double mx = (sx + tx) / 2.0;
        double mz = (sz + tz) / 2.0;
        double h = distance / 2.0;
        Spot s = new Spot(mx - u[0] * h, mz - u[1] * h, yaw(u[0], u[1]));
        Spot t = new Spot(mx + u[0] * h, mz + u[1] * h, yaw(-u[0], -u[1]));
        return new Result(s, t, true);
    }

    /**
     * Side by side {@code distance} apart, both looking the same way. Of the two possible facings
     * (perpendicular to the line between them) the one closer to where they look now is chosen; that
     * decides whether the target ends up on the sender's right or left.
     *
     * @param allowMirror {@code false}: always put the target on the sender's right (no mirrored clips)
     */
    public static Result side(double sx, double sz, double tx, double tz, double distance,
                              float senderYaw, float targetYaw, boolean allowMirror) {
        double[] r = unit(tx - sx, tz - sz, senderYaw + 90f); // fallback: the sender's right
        // facing f with "right" = r: right of f is (-f.z, f.x) -> f = (r.z, -r.x)
        double fx = r[1];
        double fz = -r[0];
        double[] a = direction(senderYaw);
        double[] b = direction(targetYaw);
        double avgX = a[0] + b[0];
        double avgZ = a[1] + b[1];
        boolean targetOnRight = !allowMirror || fx * avgX + fz * avgZ >= 0;
        if (!targetOnRight) {
            fx = -fx;
            fz = -fz;
        }
        float yaw = yaw(fx, fz);
        double mx = (sx + tx) / 2.0;
        double mz = (sz + tz) / 2.0;
        double h = distance / 2.0;
        Spot s = new Spot(mx - r[0] * h, mz - r[1] * h, yaw);
        Spot t = new Spot(mx + r[0] * h, mz + r[1] * h, yaw);
        return new Result(s, t, targetOnRight);
    }

    /** Normalized (dx, dz); when the players stand on one spot, the direction of {@code fallbackYaw}. */
    private static double[] unit(double dx, double dz, float fallbackYaw) {
        double len = Math.hypot(dx, dz);
        if (len < 1.0e-4) {
            return direction(fallbackYaw);
        }
        return new double[]{dx / len, dz / len};
    }
}
