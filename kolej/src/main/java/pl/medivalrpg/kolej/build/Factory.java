// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.function.ToIntBiFunction;
import pl.medivalrpg.kolej.network.TrackCell;

/** Pure plans in world coordinates; surface level is groundY, rails are one block above. */
public final class Factory {
    private Factory() { }
    /**
     * Pure bounded grading from a supplied survey (highest occupied block, including vegetation/water).
     * x/z are minimum corner; y is final surface. No trees are whitelisted by this generator.
     * Quote BEFORE submitting: execution policy still rejects logs, leaves, containers and built materials.
     */
    public static List<BlockOp> grade(int x,int groundY,int z,int width,int depth,int maxCut,int maxFill,
            ToIntBiFunction<Integer,Integer> surfaceTop) {
        if (width<1 || width>128 || depth<1 || depth>128 || maxCut<0 || maxCut>32 || maxFill<0 || maxFill>32)
            throw new IllegalArgumentException("Grading dimensions/bounds");
        Objects.requireNonNull(surfaceTop);
        List<BlockOp> ops=new ArrayList<>();
        for(int dx=0;dx<width;dx++) for(int dz=0;dz<depth;dz++) {
            int bx=Math.addExact(x,dx),bz=Math.addExact(z,dz),top=surfaceTop.applyAsInt(bx,bz);
            long delta=(long)top-groundY;
            if(delta>maxCut || -delta>maxFill) throw new IllegalArgumentException("Niwelacja przekracza limit przy "+bx+","+bz+": "+delta);
            for(int y=top;y>groundY;y--) ops.add(BlockOp.clear(bx,y,bz));
            // Water is soft: explicit PLACE also seals submerged pockets within the bounded foundation.
            // Existing soil is never excavated below the final surface.
            for(int y=groundY-maxFill;y<groundY;y++) ops.add(BlockOp.fill(bx,y,bz,BlockOp.Part.FILL));
            ops.add(BlockOp.place(bx,groundY,bz,BlockOp.Part.FILL));
        }
        return List.copyOf(ops);
    }
    public static List<BlockOp> segment(List<TrackCell> cells, ToIntBiFunction<Integer, Integer> groundTop,
            ToIntBiFunction<Integer, Integer> seabed, ToIntBiFunction<Integer, Integer> water, CellWorks.Settings settings) {
        if (cells.size() > 10000) throw new IllegalArgumentException("Segment too long");
        Set<Long> columns = new HashSet<>(); cells.forEach(c -> columns.add(c.columnKey()));
        List<BlockOp> ops = new ArrayList<>();
        Set<Long> curveColumns = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            TrackCell c = cells.get(i);
            if (c.kind() == pl.medivalrpg.kolej.network.CellKind.TUNNEL && CellWorks.nearSharpCurve(cells, i)) {
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) curveColumns.add(((long)(c.x()+dx)<<32)|((c.z()+dz)&0xffffffffL));
            }
            if (i > 0) {
                TrackCell prev = cells.get(i - 1);
                if (Math.abs(prev.x() - c.x()) + Math.abs(prev.z() - c.z()) != 1 || Math.abs(prev.y() - c.y()) > 1)
                    throw new IllegalArgumentException("Track must be four-neighbour, grade <= 1");
            }
            TrackCell a = cells.get(Math.max(0, i - 1)), b = cells.get(Math.min(cells.size() - 1, i + 1));
            if ((a.y() != c.y() || b.y() != c.y()) && a.x() != b.x() && a.z() != b.z())
                throw new IllegalArgumentException("A slope cannot also be a curve");
            String shape = RailGeometry.shape(a.x(), a.y(), a.z(), c.x(), c.y(), c.z(), b.x(), b.y(), b.z());
            for (BlockOp op : CellWorks.plan(cells, i, groundTop.applyAsInt(c.x(), c.z()), seabed.applyAsInt(c.x(), c.z()),
                water.applyAsInt(c.x(), c.z()), (x,z) -> columns.contains(((long) x << 32) | (z & 0xffffffffL)), groundTop, settings)) {
                ops.add(op.part() == BlockOp.Part.RAIL ? BlockOp.raw(op.x(), op.y(), op.z(), "minecraft:rail[shape=" + shape + "]", op.mode()) : op);
            }
        }
        if (!curveColumns.isEmpty()) {
            // A neighbouring cell's lining must not seal a curved chamber again.
            // Collect only local room voxels: straight segments pay no sweep/memory cost.
            Set<ClearVoxel> air = new HashSet<>();
            for (BlockOp op : ops) if (op.mode() == BlockOp.Mode.CLEAR && curveColumns.contains(((long)op.x()<<32)|(op.z()&0xffffffffL))) air.add(new ClearVoxel(op.x(),op.y(),op.z()));
            ops.removeIf(op -> switch (op.part()) {
                case TUNNEL_WALL, TUNNEL_CEILING, TUNNEL_LIGHT, BRIDGE_RAILING, LAMP, LAMP_POST, SIGNAL_POST, SIGNAL_HEAD -> air.contains(new ClearVoxel(op.x(),op.y(),op.z()));
                default -> false;
            });
        }
        return List.copyOf(ops);
    }
    private record ClearVoxel(int x,int y,int z) {}
    /** Straight road along +Z, with cleared headroom and shallow foundations. */
    public static List<BlockOp> road(int x, int groundY, int z, int length, int width) {
        if (length < 1 || length > 1024 || width < 1 || width > 9) throw new IllegalArgumentException("Road dimensions");
        List<BlockOp> ops = new ArrayList<>();
        for (int dz = 0; dz < length; dz++) for (int dx = 0; dx < width; dx++) {
            for (int dy = 3; dy >= 1; dy--) ops.add(BlockOp.clear(x + dx, groundY + dy, z + dz));
            for (int dy = -3; dy < 0; dy++) ops.add(BlockOp.fill(x + dx, groundY + dy, z + dz, BlockOp.Part.FILL));
            ops.add(BlockOp.place(x + dx, groundY, z + dz, BlockOp.Part.ROAD));
        }
        return List.copyOf(ops);
    }
    /** Track at x; passenger platform x+2..x+6, timber canopy and stone edging. */
    public static List<BlockOp> station(int x, int groundY, int z, int length) {
        if (length < 8 || length > 128) throw new IllegalArgumentException("Station length 8..128");
        List<BlockOp> ops = new ArrayList<>();
        for (int dz = 0; dz < length; dz++) {
            for (int dx = -1; dx <= 6; dx++) {
                for (int dy = 1; dy <= 5; dy++) ops.add(BlockOp.clear(x + dx, groundY + dy, z + dz));
                for (int dy = -2; dy < 0; dy++) ops.add(BlockOp.fill(x + dx, groundY + dy, z + dz, BlockOp.Part.FILL));
                ops.add(BlockOp.place(x + dx, groundY, z + dz, dx < 2 ? BlockOp.Part.BED : BlockOp.Part.PLATFORM));
            }
            ops.add(BlockOp.raw(x, groundY + 1, z + dz, "minecraft:rail[shape=north_south]", BlockOp.Mode.PLACE));
            for (int dx = 2; dx <= 6; dx++) ops.add(BlockOp.raw(x + dx, groundY + 5, z + dz, "minecraft:spruce_slab[type=bottom]", BlockOp.Mode.PLACE));
            if (dz % 6 == 0) {
                for (int dy = 1; dy <= 4; dy++) ops.add(BlockOp.raw(x + 6, groundY + dy, z + dz, "minecraft:stripped_spruce_log[axis=y]", BlockOp.Mode.PLACE));
                ops.add(BlockOp.raw(x + 4, groundY + 4, z + dz, "minecraft:lantern[hanging=true]", BlockOp.Mode.PLACE));
            }
        }
        return List.copyOf(ops);
    }
}
