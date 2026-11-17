// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Connection states of blocks that depend on their neighbours, following the vanilla rules (pure, unit tested):
 * panes and iron bars, fences, fence gates ({@code in_wall}), walls ({@code low}/{@code none}, {@code up}) and stair
 * {@code shape}. Blocks are placed without physics, so the builder recomputes these from the real neighbours after a
 * building (and its scaffolding) is finished; templates get them from the grid at load time.
 */
public final class Connections {

    /** Neighbour lookup by direction ({@code north/east/south/west/up/down}); null = air/unknown. */
    public interface Neighbours extends Function<String, BlockStates.Parsed> {
    }

    private Connections() {
    }

    /** Heuristic sturdiness used for templates and tests: full cubes are sturdy on every face. */
    public static boolean fullBlockSturdy(final String dir, final BlockStates.Parsed n) {
        return n != null && BlockKinds.isFullBlock(n);
    }

    public static boolean isStairs(final BlockStates.Parsed p) {
        return p != null && p.path().endsWith("_stairs");
    }

    /** True when the block's state depends on its neighbours. */
    public static boolean dependsOnNeighbours(final BlockStates.Parsed p) {
        return BlockKinds.isConnectable(p) || BlockKinds.isFenceGate(p) || isStairs(p);
    }

    private static String axisOf(final String dir) {
        return dir.equals("north") || dir.equals("south") ? "z" : "x";
    }

    /** A fence gate connects towards {@code dir} when it is turned across that direction. */
    static boolean gateConnects(final BlockStates.Parsed gate, final String dir) {
        final String f = gate.property("facing");
        return f != null && !axisOf(f).equals(axisOf(dir));
    }

    /** Whether {@code self} attaches towards neighbour {@code n} lying in {@code dir}. */
    public static boolean connects(final BlockStates.Parsed self, final String dir, final BlockStates.Parsed n,
                                   final BiPredicate<String, BlockStates.Parsed> sturdy) {
        if (n == null || BlockStates.isAir(n.id())) {
            return false;
        }
        final boolean solid = sturdy.test(dir, n);
        if (BlockKinds.isPaneLike(self)) {
            return BlockKinds.isPaneLike(n) || BlockKinds.isWall(n) || solid;
        }
        if (BlockKinds.isFence(self)) {
            if (BlockKinds.isFenceGate(n)) {
                return gateConnects(n, dir);
            }
            if (BlockKinds.isFence(n)) {
                return self.path().startsWith("nether_brick") == n.path().startsWith("nether_brick");
            }
            return solid;
        }
        if (BlockKinds.isWall(self)) {
            if (BlockKinds.isFenceGate(n)) {
                return gateConnects(n, dir);
            }
            return BlockKinds.isWall(n) || BlockKinds.isPaneLike(n) || solid;
        }
        return false;
    }

    /** Legacy helper (templates): connection with the full-block heuristic, direction-agnostic gates. */
    static boolean connects(final BlockStates.Parsed self, final BlockStates.Parsed n) {
        for (final String dir : BlockStates.HORIZONTAL) {
            if (connects(self, dir, n, Connections::fullBlockSturdy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * New state of {@code self} given its neighbours; unchanged when the block does not depend on neighbours.
     *
     * @param sturdy (direction from self, neighbour) → the neighbour's face towards self is sturdy
     */
    public static BlockStates.Parsed resolve(final BlockStates.Parsed self, final Neighbours neighbours,
                                             final BiPredicate<String, BlockStates.Parsed> sturdy) {
        if (BlockKinds.isConnectable(self)) {
            final Map<String, String> props = new LinkedHashMap<>(self.properties());
            final Map<String, Boolean> con = new HashMap<>();
            for (final String dir : BlockStates.HORIZONTAL) {
                final boolean c = connects(self, dir, neighbours.apply(dir), sturdy);
                con.put(dir, c);
                if (BlockKinds.isWall(self)) {
                    props.put(dir, c ? "low" : "none");
                } else {
                    props.put(dir, Boolean.toString(c));
                }
            }
            if (BlockKinds.isWall(self)) {
                final boolean ns = con.get("north") && con.get("south") && !con.get("east") && !con.get("west");
                final boolean ew = con.get("east") && con.get("west") && !con.get("north") && !con.get("south");
                final BlockStates.Parsed above = neighbours.apply("up");
                final boolean covered = above != null && !BlockStates.isAir(above.id())
                    && (BlockKinds.isWall(above) || sturdy.test("up", above));
                props.put("up", Boolean.toString(!(ns || ew) || covered));
            }
            return new BlockStates.Parsed(self.id(), props);
        }
        if (BlockKinds.isFenceGate(self)) {
            final String f = self.property("facing");
            if (f == null) {
                return self;
            }
            final String left = BlockStates.rotateDirection(f, -1);
            final String right = BlockStates.rotateDirection(f, 1);
            final BlockStates.Parsed l = neighbours.apply(left);
            final BlockStates.Parsed r = neighbours.apply(right);
            final boolean inWall = l != null && BlockKinds.isWall(l) || r != null && BlockKinds.isWall(r);
            final Map<String, String> props = new LinkedHashMap<>(self.properties());
            props.put("in_wall", Boolean.toString(inWall));
            return new BlockStates.Parsed(self.id(), props);
        }
        if (isStairs(self)) {
            final Map<String, String> props = new LinkedHashMap<>(self.properties());
            props.put("shape", stairShape(self, neighbours));
            return new BlockStates.Parsed(self.id(), props);
        }
        return self;
    }

    /** Vanilla {@code StairBlock#getStairsShape}. */
    static String stairShape(final BlockStates.Parsed self, final Neighbours neighbours) {
        final String dir = self.property("facing");
        final String half = self.property("half");
        if (dir == null) {
            return "straight";
        }
        final BlockStates.Parsed front = neighbours.apply(dir);
        if (isStairs(front) && Objects.equals(half, front.property("half"))) {
            final String fd = front.property("facing");
            if (fd != null && !axisOf(fd).equals(axisOf(dir))
                && canTakeShape(self, neighbours, BlockStates.rotateDirection(fd, 2))) {
                return fd.equals(BlockStates.rotateDirection(dir, -1)) ? "outer_left" : "outer_right";
            }
        }
        final BlockStates.Parsed back = neighbours.apply(BlockStates.rotateDirection(dir, 2));
        if (isStairs(back) && Objects.equals(half, back.property("half"))) {
            final String bd = back.property("facing");
            if (bd != null && !axisOf(bd).equals(axisOf(dir)) && canTakeShape(self, neighbours, bd)) {
                return bd.equals(BlockStates.rotateDirection(dir, -1)) ? "inner_left" : "inner_right";
            }
        }
        return "straight";
    }

    private static boolean canTakeShape(final BlockStates.Parsed self, final Neighbours neighbours, final String side) {
        final BlockStates.Parsed n = neighbours.apply(side);
        return !isStairs(n) || !Objects.equals(n.property("facing"), self.property("facing"))
            || !Objects.equals(n.property("half"), self.property("half"));
    }

    /**
     * Returns a copy of {@code cells} ({@code [(y * depth + z) * width + x]}) with connections filled in from the grid:
     * panes/fences/walls that do not declare their sides, gates and stairs that do not declare in_wall/shape.
     */
    public static String[] apply(final String[] cells, final int width, final int height, final int depth) {
        final String[] out = cells.clone();
        final Map<String, BlockStates.Parsed> cache = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    final int i = (y * depth + z) * width + x;
                    final String s = cells[i];
                    if (s == null) {
                        continue;
                    }
                    final BlockStates.Parsed p = cache.computeIfAbsent(s, BlockStates::parse);
                    if (!dependsOnNeighbours(p) || declares(p)) {
                        continue;
                    }
                    final int fx = x;
                    final int fy = y;
                    final int fz = z;
                    final Neighbours n = dir -> {
                        final int[] v = BlockStates.vector(dir);
                        final int nx = fx + v[0];
                        final int nz = fz + v[1];
                        final int ny = fy + (dir.equals("up") ? 1 : dir.equals("down") ? -1 : 0);
                        if (nx < 0 || nz < 0 || ny < 0 || nx >= width || nz >= depth || ny >= height) {
                            return null;
                        }
                        final String ns = cells[(ny * depth + nz) * width + nx];
                        return ns == null ? null : cache.computeIfAbsent(ns, BlockStates::parse);
                    };
                    out[i] = resolve(p, n, Connections::fullBlockSturdy).format();
                }
            }
        }
        return out;
    }

    private static boolean declares(final BlockStates.Parsed p) {
        if (isStairs(p)) {
            return p.properties().containsKey("shape");
        }
        if (BlockKinds.isFenceGate(p)) {
            return p.properties().containsKey("in_wall");
        }
        for (final String dir : BlockStates.HORIZONTAL) {
            if (p.properties().containsKey(dir)) {
                return true;
            }
        }
        return false;
    }
}
