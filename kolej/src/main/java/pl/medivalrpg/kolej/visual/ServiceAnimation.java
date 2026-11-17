// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

/** Pure mapping of a completed tender service to the fixture animation and switch-lever geometry. */
public final class ServiceAnimation {
    public static final String CRANE = "kolej_zuraw_wodny";
    private ServiceAnimation() { }
    /**
     * Water crane swings towards the served tender: local +Z → service_pz, otherwise service_nz.
     * Other fixtures have a single "service" animation. Fixture yaw = quarter*90 (Minecraft yaw).
     */
    public static String name(String model, int quarter, double dx, double dz) {
        if (!CRANE.equals(model)) return "service";
        return localZ(quarter * 90.0, dx, dz) >= 0 ? "service_pz" : "service_nz";
    }
    /** World offset → model-local Z for a model rotated by Minecraft yaw (local +Z = (-sin, cos)). */
    public static double localZ(double yaw, double dx, double dz) {
        double a = Math.toRadians(yaw);
        return -Math.sin(a) * dx + Math.cos(a) * dz;
    }
    /** Model-local X: (cos, sin) in world, consistent with SupplyKind bounds rotation. */
    public static double localX(double yaw, double dx, double dz) {
        double a = Math.toRadians(yaw);
        return Math.cos(a) * dx + Math.sin(a) * dz;
    }
    /** Lever stand beside a junction: offset to the right of travel, lever swinging along the track. Returns {x,z,yaw}. */
    public static double[] lever(double x, double z, double travelYaw, double lateral) {
        double a = Math.toRadians(travelYaw);
        return new double[]{x - Math.cos(a) * lateral, z - Math.sin(a) * lateral, travelYaw + 90};
    }
}
