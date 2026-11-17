// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.depot;

import java.util.*;
import pl.medivalrpg.kolej.build.BlockOp;
import pl.medivalrpg.kolej.build.RailGeometry;
import pl.medivalrpg.kolej.world.PlanRotation;
import pl.medivalrpg.kolej.network.TrackCell;
import pl.medivalrpg.kolej.network.CellKind;

/** Masonry and timber carrier depot. Origin = turntable centre; y = yard surface. */
public final class DepotPlan {
    public record Bay(int index, int doorX, int doorZ, int endX, int endZ) { }
    /** Static rails only. Bay -1 = incoming track; centre-to-approach connections are dynamic turntable links. */
    public record TrackLink(int bayIndex, List<TrackCell> cells) { public TrackLink { cells=List.copyOf(cells); } }
    private DepotPlan() { }
    /** Six radial sheds face the turntable, 20 degrees apart. */
    public static List<Bay> bays() {
        List<Bay> bays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(-50 + 20 * i);
            bays.add(new Bay(i, (int) Math.round(12 * Math.sin(angle)), (int) Math.round(12 * Math.cos(angle)),
                (int) Math.round(25 * Math.sin(angle)), (int) Math.round(25 * Math.cos(angle))));
        }
        return List.copyOf(bays);
    }
    public static List<BlockOp> plan(int x, int groundY, int z) {
        List<BlockOp> out = local();
        return out.stream().map(op -> new BlockOp(x + op.x(), groundY + op.y(), z + op.z(), op.part(), op.mode(), op.data())).toList();
    }
    public static List<BlockOp> plan(int x, int groundY, int z, int quarterTurns) {
        return PlanRotation.transform(local(), x, groundY, z, quarterTurns);
    }
    /** Pure data for runtime to create its own nodes/segments; does not mutate the graph. */
    public static List<TrackLink> trackLinks(int x,int railY,int z,int quarterTurns) {
        List<TrackLink> links=new ArrayList<>();
        List<TrackCell> incoming=new ArrayList<>();
        for (int dz=-30;dz<=-TurntablePlan.PIT_RADIUS;dz++) incoming.add(cell(x,railY,z,0,dz,quarterTurns));
        links.add(new TrackLink(-1,incoming));
        for (int i=0;i<6;i++) {
            List<TrackCell> cells=new ArrayList<>();
            for (int[] p:bayPath(i)) cells.add(cell(x,railY,z,p[0],p[1],quarterTurns));
            links.add(new TrackLink(i,cells));
        }
        return List.copyOf(links);
    }
    private static TrackCell cell(int x,int y,int z,int px,int pz,int turns) {
        int[] p=PlanRotation.point(px,pz,turns); return new TrackCell(x+p[0],y,z+p[1],CellKind.GROUND);
    }
    private static List<int[]> bayPath(int index) {
        double a=Math.toRadians(-50+index*20);
        // Continue in the direction of the bridge's final rail before bending toward the gate.
        var moving=TurntablePlan.bridge(TurntablePlan.bayYaw(0,index));
        List<TurntablePlan.Cell> rails=moving.keySet().stream().filter(c->c.y()==1).toList();
        var tip=rails.getLast(); var prev=rails.get(rails.size()-2);
        int px=tip.x(),pz=tip.z();
        int nx=(int)Math.round(TurntablePlan.PIT_RADIUS*Math.sin(a)),nz=(int)Math.round(TurntablePlan.PIT_RADIUS*Math.cos(a));
        List<int[]> path=new ArrayList<>();
        if (tip.x()!=prev.x()) {
            while(px!=nx){px+=Integer.signum(nx-px);path.add(new int[]{px,pz});}
            while(pz!=nz){pz+=Integer.signum(nz-pz);path.add(new int[]{px,pz});}
        } else {
            while(pz!=nz){pz+=Integer.signum(nz-pz);path.add(new int[]{px,pz});}
            while(px!=nx){px+=Integer.signum(nx-px);path.add(new int[]{px,pz});}
        }
        for (int d=TurntablePlan.PIT_RADIUS+1;d<=25;d++) {
            nx=(int)Math.round(d*Math.sin(a)); nz=(int)Math.round(d*Math.cos(a));
            while(pz!=nz){pz+=Integer.signum(nz-pz);path.add(new int[]{px,pz});}
            while(px!=nx){px+=Integer.signum(nx-px);path.add(new int[]{px,pz});}
        }
        return path;
    }
    private static List<BlockOp> local() {
        Plan p = new Plan();
        // Footprint 77 x 61. Deliberately no deep automatic grading: survey/grade the site first.
        for (int x = -38; x <= 38; x++) for (int z = -30; z <= 30; z++) {
            for (int y = -2; y < 0; y++) p.fill(x, y, z, "dirt");
            p.put(x, 0, z, Math.floorMod(x * 31 + z, 7) == 0 ? "andesite" : "gravel");
        }
        // Turntable pit, raised stone rim, central pivot and a real straight bridge carrying rails.
        for (int x = -TurntablePlan.PIT_RADIUS; x <= TurntablePlan.PIT_RADIUS; x++) for (int z = -TurntablePlan.PIT_RADIUS; z <= TurntablePlan.PIT_RADIUS; z++) {
            double radius = Math.hypot(x, z);
            if (radius > TurntablePlan.PIT_RADIUS) continue;
            p.put(x, -2, z, "stone_bricks");
            p.put(x, -1, z, radius >= TurntablePlan.BRIDGE_RADIUS ? "stone_bricks" : "air");
            p.put(x, 0, z, radius >= TurntablePlan.BRIDGE_RADIUS ? "polished_andesite" : "air");
            p.put(x, 1, z, "air");
        }
        p.put(0, -1, 0, "chiseled_stone_bricks");
        TurntablePlan.bridge(0).forEach((cell,data) -> p.put(cell.x(),cell.y(),cell.z(),data.substring("minecraft:".length())));
        // Six fan bays: rasterised radial coordinates, arched door openings, clerestory roof.
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-50 + i * 20);
            for (int depth = 12; depth <= 26; depth++) for (int w = -2; w <= 2; w++) {
                int bx = (int) Math.round(depth * Math.sin(a) + w * Math.cos(a));
                int bz = (int) Math.round(depth * Math.cos(a) - w * Math.sin(a));
                p.put(bx, 0, bz, "stone_bricks");
                for (int y = 1; y <= 6; y++) {
                    boolean wall = Math.abs(w) == 2 || depth == 26;
                    String data = wall ? (y == 3 && depth % 4 == 0 ? "glass_pane" : "bricks") : "air";
                    if (depth == 12 && Math.abs(w) < 2 && y == 6) data = "stone_bricks";
                    p.put(bx, y, bz, data);
                }
                p.put(bx, 7 + (Math.abs(w) < 2 ? 1 : 0), bz, "dark_oak_planks");
            }
            // Track corridor from turntable edge to shed rear. Stair-step rails connect diagonals.
            List<int[]> path=bayPath(i);
            p.route(path);
            List<TurntablePlan.Cell> bridgeRails=TurntablePlan.bridge(TurntablePlan.bayYaw(0,i)).keySet().stream().filter(c->c.y()==1).toList();
            var tip=bridgeRails.getLast(); int[] first=path.getFirst(), next=path.get(1);
            p.put(first[0],1,first[1],"rail[shape="+RailGeometry.shape(tip.x(),1,tip.z(),first[0],1,first[1],next[0],1,next[1])+"]");
        }
        // Incoming service track and gate.
        for (int z = -30; z <= -TurntablePlan.PIT_RADIUS; z++) p.track(0, z);
        building(p, -34, -5, 11, 15, "workshop");
        building(p, 22, -22, 12, 10, "office");
        building(p, 22, -7, 12, 15, "quarters");
        // Coal bunkers with masonry walls, three graded heaps.
        for (int x = -26; x <= -14; x++) for (int z = -25; z <= -17; z++) {
            boolean wall = x == -26 || x == -14 || z == -25;
            for (int y = 1; y <= (wall ? 3 : 1 + Math.floorMod(x + z, 3)); y++) p.put(x, y, z, wall ? "stone_bricks" : "coal_block");
        }
        // Timber-supported water tank, masonry base, accessible spout.
        for (int x = 12; x <= 17; x++) for (int z = -15; z <= -10; z++) {
            p.put(x, 0, z, "stone_bricks");
            if ((x == 12 || x == 17) && (z == -15 || z == -10))
                for (int y = 1; y < 7; y++) p.put(x, y, z, "stripped_spruce_log[axis=y]");
            for (int y = 7; y <= 10; y++) p.put(x, y, z,
                y == 7 || x == 12 || x == 17 || z == -15 || z == -10 ? "spruce_planks" : "water[level=0]");
            p.put(x, 11, z, "dark_oak_slab[type=bottom]");
        }
        for (int x = 9; x <= 12; x++) p.put(x, 7, -12, "copper_block");
        for (int y = 4; y <= 6; y++) p.put(9, y, -12, "copper_block");
        // Yard roads and warm lamps.
        for (int x = -35; x <= 35; x++) for (int z = -29; z <= -27; z++) p.put(x, 0, z, "cobblestone");
        for (int x : new int[] {-35, -10, 10, 35}) for (int z : new int[] {-27, 12}) {
            for (int y = 1; y <= 3; y++) p.put(x, y, z, "oak_fence");
            p.put(x, 4, z, "lantern");
        }
        return p.finish();
    }
    private static void building(Plan p, int x, int z, int width, int depth, String function) {
        for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < depth; dz++) {
            p.put(x + dx, 0, z + dz, "stone_bricks");
            for (int y = 1; y <= 5; y++) {
                boolean wall = dx == 0 || dz == 0 || dx == width - 1 || dz == depth - 1;
                boolean door = dz == 0 && dx == width / 2 && y < 4;
                boolean window = wall && y == 3 && (dx + dz) % 3 == 1;
                p.put(x + dx, y, z + dz, door || !wall ? "air" : window ? "glass_pane" : y == 5 ? "spruce_planks" : "stone_bricks");
            }
            int roof = 6 + Math.min(dx, width - 1 - dx) / 2;
            p.put(x + dx, roof, z + dz, "dark_oak_planks");
        }
        p.put(x + width / 2, 4, z + 2, "lantern[hanging=true]");
        if (function.equals("workshop")) {
            p.put(x + 2, 1, z + 2, "anvil[facing=north]");
            p.put(x + 3, 1, z + 2, "crafting_table");
            for (int y = 1; y <= 10; y++) p.put(x + width - 2, y, z + depth - 2, "bricks");
        } else if (function.equals("office")) {
            p.put(x + 2, 1, z + 3, "oak_slab[type=top]");
            p.put(x + 3, 1, z + 3, "oak_slab[type=top]");
            p.put(x + 2, 1, z + depth - 2, "bookshelf");
        } else {
            for (int dz = 3; dz < depth - 2; dz += 3) {
                p.put(x + 2, 1, z + dz, "red_bed[facing=south,part=foot]");
                p.put(x + 2, 1, z + dz + 1, "red_bed[facing=south,part=head]");
            }
        }
    }
    /** Last write wins, one operation per coordinate, stable foundation-first ordering. */
    private static final class Plan {
        private record Key(int x, int y, int z) { }
        private final Map<Key, BlockOp> blocks = new LinkedHashMap<>();
        void put(int x, int y, int z, String data) { blocks.put(new Key(x,y,z), BlockOp.raw(x,y,z,"minecraft:" + data, data.equals("air") ? BlockOp.Mode.CLEAR : BlockOp.Mode.PLACE)); }
        void fill(int x, int y, int z, String data) { blocks.put(new Key(x,y,z), BlockOp.raw(x,y,z,"minecraft:" + data, BlockOp.Mode.FILL)); }
        void track(int x, int z) {
            put(x, 0, z, "stone_bricks"); put(x, 1, z, "rail[shape=north_south]");
            for (int y = 2; y <= 4; y++) put(x, y, z, "air");
        }
        void route(List<int[]> path) {
            for (int i = 0; i < path.size(); i++) {
                int[] a = path.get(Math.max(0, i - 1)), c = path.get(i), b = path.get(Math.min(path.size() - 1, i + 1));
                track(c[0], c[1]);
                put(c[0], 1, c[1], "rail[shape=" + RailGeometry.shape(a[0], 1, a[1], c[0], 1, c[1], b[0], 1, b[1]) + "]");
            }
        }
        List<BlockOp> finish() { return blocks.values().stream().sorted(Comparator.comparingInt(BlockOp::y)).toList(); }
    }
}
