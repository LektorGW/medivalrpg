// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.geom;

/** Immutable 3D point/vector in block units (1 block = 1 m). */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(final Vec3 o) {
        return new Vec3(this.x + o.x, this.y + o.y, this.z + o.z);
    }

    public Vec3 sub(final Vec3 o) {
        return new Vec3(this.x - o.x, this.y - o.y, this.z - o.z);
    }

    public Vec3 scale(final double f) {
        return new Vec3(this.x * f, this.y * f, this.z * f);
    }

    public double length() {
        return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
    }

    public double horizontalLength() {
        return Math.hypot(this.x, this.z);
    }

    public double distance(final Vec3 o) {
        return this.sub(o).length();
    }

    public Vec3 normalized() {
        final double l = this.length();
        return l < 1e-9 ? ZERO : this.scale(1.0 / l);
    }

    public Vec3 lerp(final Vec3 o, final double t) {
        return new Vec3(this.x + (o.x - this.x) * t, this.y + (o.y - this.y) * t, this.z + (o.z - this.z) * t);
    }

    /** Minecraft yaw (degrees) of a horizontal direction: yaw 0 faces +Z, 90 faces -X. */
    public double yawDegrees() {
        return Math.toDegrees(Math.atan2(-this.x, this.z));
    }

    /** Minecraft pitch (degrees, positive = down) of a direction. */
    public double pitchDegrees() {
        return -Math.toDegrees(Math.atan2(this.y, this.horizontalLength()));
    }
}
