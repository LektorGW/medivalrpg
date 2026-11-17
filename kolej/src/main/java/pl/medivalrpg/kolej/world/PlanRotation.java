// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.world;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.structure.StructureRotation;
import pl.medivalrpg.kolej.build.BlockOp;

/** Quarter turns clockwise around a local origin, followed by translation. Bukkit data rotation requires main thread. */
public final class PlanRotation {
    private PlanRotation() { }
    public static int[] point(int x, int z, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new int[] {-z, x}; case 2 -> new int[] {-x, -z};
            case 3 -> new int[] {z, -x}; default -> new int[] {x, z};
        };
    }
    public static List<BlockOp> transform(List<BlockOp> ops, int x, int y, int z, int turns) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        int q = Math.floorMod(turns, 4);
        return ops.stream().map(op -> {
            int[] p = point(op.x(), op.z(), q);
            String data = op.data();
            if (data != null && q != 0) {
                var block = Bukkit.createBlockData(data);
                block.rotate(switch (q) {
                    case 1 -> StructureRotation.CLOCKWISE_90;
                    case 2 -> StructureRotation.CLOCKWISE_180;
                    default -> StructureRotation.COUNTERCLOCKWISE_90;
                });
                data = block.getAsString();
            }
            return new BlockOp(Math.addExact(x, p[0]), Math.addExact(y, op.y()), Math.addExact(z, p[1]), op.part(), op.mode(), data);
        }).toList();
    }
}
