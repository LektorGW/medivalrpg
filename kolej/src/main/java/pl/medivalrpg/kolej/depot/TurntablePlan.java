// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.depot;

import java.util.*;
import pl.medivalrpg.kolej.build.BlockOp;
import pl.medivalrpg.kolej.build.RailGeometry;
import pl.medivalrpg.kolej.world.PlanRotation;

/** Pure physical bridge geometry. Y=0 is deck, Y=1 is rail. Bukkit yaw 0 points south. */
public final class TurntablePlan {
    public static final int BRIDGE_RADIUS = 9;
    public static final int PIT_RADIUS = 10;
    private TurntablePlan() { }
    public record Cell(int x, int y, int z) { }
    public static Map<Cell, String> bridge(double yaw) {
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Invalid yaw");
        // Rasterise in one canonical quadrant, then rotate integer coordinates. This avoids
        // sign-dependent half rounding and x/z stepping changing a bay's attachment after a 90° rotation.
        double normalized = ((yaw % 360) + 360) % 360;
        int turns = (int)Math.floor((normalized + 45) / 90);
        double angle = Math.toRadians(-(normalized - turns*90)), sx = Math.sin(angle), sz = Math.cos(angle);
        List<int[]> path = new ArrayList<>();
        int px = (int) Math.round(-BRIDGE_RADIUS * sx), pz = (int) Math.round(-BRIDGE_RADIUS * sz);
        path.add(new int[] {px,pz});
        for (int d = -BRIDGE_RADIUS+1; d <= BRIDGE_RADIUS; d++) {
            int nx = (int) Math.round(d * sx), nz = (int) Math.round(d * sz);
            while (pz != nz) { pz += Integer.signum(nz-pz); path.add(new int[] {px,pz}); }
            while (px != nx) { px += Integer.signum(nx-px); path.add(new int[] {px,pz}); }
        }
        Map<Cell, String> out = new LinkedHashMap<>();
        for (int x = -BRIDGE_RADIUS; x <= BRIDGE_RADIUS; x++) for (int z = -BRIDGE_RADIUS; z <= BRIDGE_RADIUS; z++)
            if (Math.hypot(x,z) <= BRIDGE_RADIUS-.5 && Math.abs(x * sz - z * sx) <= 1.15) {
                int[] p=PlanRotation.point(x,z,turns);
                out.put(new Cell(p[0],0,p[1]), "minecraft:dark_oak_planks");
            }
        List<int[]> rotated = new ArrayList<>();
        for (int[] p:path) rotated.add(PlanRotation.point(p[0],p[1],turns));
        path=rotated;
        for (int i = 0; i < path.size(); i++) {
            int[] a = path.get(Math.max(0,i-1)), c = path.get(i), b = path.get(Math.min(path.size()-1,i+1));
            out.put(new Cell(c[0],0,c[1]), "minecraft:dark_oak_planks");
            out.put(new Cell(c[0],1,c[1]), "minecraft:rail[shape=" + RailGeometry.shape(a[0],1,a[1],c[0],1,c[1],b[0],1,b[1]) + "]");
        }
        return Collections.unmodifiableMap(out);
    }
    public static double bayYaw(double baseYaw, int index) {
        if (index == 6) return baseYaw;
        if (index < 0 || index > 6) throw new IllegalArgumentException("Bay index 0..5 or 6=incoming");
        return baseYaw + 50 - 20 * index;
    }
    /** Static pit/rim at deck and rail heights, excluding the moving bridge and approach rails. */
    public static String pit(Cell cell) {
        if (cell.y == 1) return "minecraft:air";
        return Math.hypot(cell.x,cell.z) >= BRIDGE_RADIUS ? "minecraft:polished_andesite" : "minecraft:air";
    }
    public static List<BlockOp> operations(double yaw) {
        return bridge(yaw).entrySet().stream().map(e -> BlockOp.raw(e.getKey().x,e.getKey().y,e.getKey().z,e.getValue(),BlockOp.Mode.PLACE)).toList();
    }
}
