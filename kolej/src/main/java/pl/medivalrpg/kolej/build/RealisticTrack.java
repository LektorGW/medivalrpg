// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.function.ToIntBiFunction;
import pl.medivalrpg.kolej.network.CellKind;
import pl.medivalrpg.kolej.network.TrackCell;

/**
 * Realistic 19th-century steam railway cross-section built from vanilla blocks, as a deterministic layer on top of the
 * unchanged {@link Factory#segment} plan (earthworks, bed, rail, tunnels and bridge decks stay exactly where the
 * classic generator puts them).
 *
 * <p>Cross-section of open line (w = lateral offset, + is the right-hand side of {@link CellWorks#perpendicular}):
 * <pre>
 *   w=0      sleeper (stripped spruce log across the track) on even cells, gravel ballast on odd cells, rail above
 *   w=±1     sleeper end / gravel ballast shoulder at bed level, supported by fill
 *   w=±2     andesite slab ballast toe; structure gauge cleared from rail level up to rail+3
 *   w=-3     drainage ditch (coarse dirt one below the bed) in cuttings and on level ground; embankment slope otherwise
 *   w=-4     cutting step (one block above formation) or second embankment slope level
 *   w=+3,+4  the road (trakt) when enabled and the terrain allows it, otherwise the same ditch/step/slope as the left side
 *   w=-3     hectometre posts every 100 cells, two-high kilometre posts every 1000 cells
 * </pre>
 * Bridges become masonry viaducts: five-wide piers, stone-brick arch springers and a continuous stone-brick parapet.
 *
 * <p>Invariants (tested): the rail cell and its bed are never replaced; nothing non-passable is placed at or above rail
 * level inside the runtime 3x3 envelope or the {@link RailReuse#expected} completion clearance of any cell; one
 * operation per coordinate, so the price ({@code ops.size()}) equals the number of planned voxels.
 */
public final class RealisticTrack {
    public static final String BALLAST = "minecraft:gravel";
    public static final String BRIDGE_BALLAST = "minecraft:andesite";
    public static final String TOE = "minecraft:andesite_slab[type=bottom,waterlogged=false]";
    public static final String TOE_FULL = "minecraft:andesite";
    public static final String DITCH = "minecraft:coarse_dirt";
    public static final String SLOPE = "minecraft:grass_block[snowy=false]";
    public static final String PLINTH = "minecraft:polished_andesite";
    public static final String POST = "minecraft:diorite_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]";
    public static final int HECTOMETRE = 100;
    public static final int KILOMETRE = 1000;

    private RealisticTrack() {
    }

    public static String sleeper(final int[] side) {
        return "minecraft:stripped_spruce_log[axis=" + (side[0] != 0 ? "x" : "z") + "]";
    }

    static String parapet(final boolean alongX) {
        return alongX
            ? "minecraft:stone_brick_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]"
            : "minecraft:stone_brick_wall[east=none,north=low,south=low,up=false,waterlogged=false,west=none]";
    }

    static String springer(final String facing) {
        return "minecraft:stone_brick_stairs[facing=" + facing + ",half=top,shape=straight,waterlogged=false]";
    }

    record Voxel(int x, int y, int z) {
        static Voxel of(final BlockOp op) {
            return new Voxel(op.x(), op.y(), op.z());
        }
    }

    /** Every voxel that must stay passable for the runtime probe and for VERIFYING completion checks. */
    static Set<Voxel> protectedVoxels(final List<TrackCell> cells) {
        final Set<Voxel> result = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            final TrackCell c = cells.get(i);
            for (final RailReuse.Voxel v : RailReuse.expected(cells, i).clearance()) {
                result.add(new Voxel(v.x(), v.y(), v.z()));
            }
            final TrackCell a = cells.get(Math.max(0, i - 1));
            final TrackCell b = cells.get(Math.min(cells.size() - 1, i + 1));
            final int height = a.y() != c.y() || b.y() != c.y() ? 5 : 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= height; dy++) {
                result.add(new Voxel(c.x() + dx, c.y() + dy, c.z() + dz));
            }
        }
        return result;
    }

    public static List<BlockOp> segment(final List<TrackCell> cells, final ToIntBiFunction<Integer, Integer> groundTop,
                                        final ToIntBiFunction<Integer, Integer> seabed, final ToIntBiFunction<Integer, Integer> water,
                                        final CellWorks.Settings settings) {
        // Classic structural plan without line lamps and without the classic road: both are re-planned below.
        final CellWorks.Settings core = new CellWorks.Settings(settings.clearance(), settings.pillarEvery(), settings.lampEvery(),
            false, settings.roadMaxFill(), settings.signalInset());
        final List<BlockOp> base = Factory.segment(cells, groundTop, seabed, water, core);
        final Set<Voxel> guard = protectedVoxels(cells);
        final Set<Long> track = new HashSet<>();
        cells.forEach(c -> track.add(c.columnKey()));

        // Signals are dropped as a whole column when any of their voxels would obstruct a clearance envelope.
        final Set<Long> blockedSignals = new HashSet<>();
        for (final BlockOp op : base) {
            if ((op.part() == BlockOp.Part.SIGNAL_POST || op.part() == BlockOp.Part.SIGNAL_HEAD) && guard.contains(Voxel.of(op))) {
                blockedSignals.add(column(op.x(), op.z()));
            }
        }
        final Map<Voxel, BlockOp> merged = new LinkedHashMap<>();
        for (final BlockOp op : base) {
            switch (op.part()) {
                case LAMP, LAMP_POST, BRIDGE_RAILING -> {
                    continue;
                }
                case SIGNAL_POST, SIGNAL_HEAD -> {
                    if (blockedSignals.contains(column(op.x(), op.z()))) continue;
                }
                default -> { }
            }
            merged.put(Voxel.of(op), op);
        }
        final Plan plan = new Plan(merged, guard);
        final int n = cells.size();
        final int pillarEvery = Math.max(1, settings.pillarEvery());
        for (int i = 0; i < n; i++) {
            final TrackCell c = cells.get(i);
            final TrackCell a = cells.get(Math.max(0, i - 1));
            final TrackCell b = cells.get(Math.min(n - 1, i + 1));
            final int[] side = CellWorks.perpendicular(cells, i);
            final boolean straight = a.x() == b.x() || a.z() == b.z();
            final boolean even = (i & 1) == 0;
            final int rail = c.y();
            final int bed = rail - 1;
            final String tie = sleeper(side);
            switch (c.kind()) {
                case TUNNEL, PLATFORM -> {
                    // Classic lined bore and platform track are already the realistic choice.
                }
                case BRIDGE -> {
                    plan.place(c.x(), bed, c.z(), even ? tie : BRIDGE_BALLAST);
                    for (int w = -2; w <= 2; w++) {
                        if (w == 0) continue;
                        final int cx = c.x() + side[0] * w;
                        final int cz = c.z() + side[1] * w;
                        if (track.contains(column(cx, cz))) continue;
                        if (Math.abs(w) == 1) plan.place(cx, bed, cz, even ? tie : BRIDGE_BALLAST);
                        else plan.placeOrClear(cx, rail, cz, straight ? parapet(side[0] == 0) : POST);
                    }
                    if (i % pillarEvery == 0) {
                        for (int w = -2; w <= 2; w++) {
                            if (w == 0) continue; // the classic centre pillar is already planned
                            final int cx = c.x() + side[0] * w;
                            final int cz = c.z() + side[1] * w;
                            if (track.contains(column(cx, cz))) continue;
                            final int bottom = Math.min(seabed.applyAsInt(cx, cz), groundTop.applyAsInt(cx, cz)) + 1;
                            for (int y = bottom; y < bed; y++) plan.part(cx, y, cz, BlockOp.Part.BRIDGE_PILLAR);
                        }
                    } else if (pillarEvery >= 3 && straight && a != c && b != c) {
                        final int phase = i % pillarEvery;
                        final TrackCell towards = phase == 1 ? a : phase == pillarEvery - 1 ? b : null;
                        if (towards != null) {
                            final String facing = facing(towards.x() - c.x(), towards.z() - c.z());
                            for (int w = -2; w <= 2; w++) {
                                final int cx = c.x() + side[0] * w;
                                final int cz = c.z() + side[1] * w;
                                if (w != 0 && track.contains(column(cx, cz))) continue;
                                final int floor = Math.min(seabed.applyAsInt(cx, cz), groundTop.applyAsInt(cx, cz));
                                if (floor < bed - 1) plan.place(cx, bed - 1, cz, springer(facing));
                            }
                        }
                    }
                }
                default -> {
                    plan.place(c.x(), bed, c.z(), even ? tie : BALLAST);
                    final boolean nearEnd = i < 2 || i > n - 3;
                    for (final int s : new int[] {-1, 1}) {
                        final boolean road = settings.road() && s == 1 && !nearEnd;
                        for (int k = 1; k <= 4; k++) {
                            final int cx = c.x() + side[0] * s * k;
                            final int cz = c.z() + side[1] * s * k;
                            if (track.contains(column(cx, cz))) continue;
                            final int g = groundTop.applyAsInt(cx, cz);
                            final boolean wet = water.applyAsInt(cx, cz) != Integer.MIN_VALUE;
                            if (k <= 2) {
                                if (k == 2) for (int y = Math.max(g, rail + 3); y >= rail; y--) plan.clear(cx, y, cz);
                                for (int y = g + 1; y < bed; y++) plan.fill(cx, y, cz);
                                plan.place(cx, bed, cz, k == 1 ? (even ? tie : BALLAST) : TOE);
                            } else if (road && Math.abs(g - bed) <= settings.roadMaxFill()) {
                                road(plan, cx, cz, g, bed, settings);
                            } else if (k == 3) {
                                if (c.kind() == CellKind.EMBANKMENT && g < bed - 1) {
                                    for (int y = g + 1; y < bed - 1; y++) plan.fill(cx, y, cz);
                                    plan.place(cx, bed - 1, cz, SLOPE);
                                } else if (!wet && g >= bed - 1) {
                                    for (int y = g; y >= bed; y--) plan.clear(cx, y, cz);
                                    plan.place(cx, bed - 1, cz, DITCH);
                                }
                            } else {
                                if (c.kind() == CellKind.EMBANKMENT && g < bed - 2) {
                                    for (int y = g + 1; y < bed - 2; y++) plan.fill(cx, y, cz);
                                    plan.place(cx, bed - 2, cz, SLOPE);
                                } else if (!wet && g > rail) {
                                    for (int y = g; y > rail; y--) plan.clear(cx, y, cz);
                                }
                            }
                        }
                    }
                    if (i > 0 && i < n - 1 && i % HECTOMETRE == 0 && straight) post(plan, cells, i, side, groundTop, water, track);
                }
            }
        }
        // A toe slab under anything standing at rail level (signal, post) would leave a half-block gap.
        for (final Map.Entry<Voxel, BlockOp> entry : List.copyOf(plan.ops.entrySet())) {
            final BlockOp op = entry.getValue();
            if (TOE.equals(op.data())) {
                final BlockOp above = plan.ops.get(new Voxel(op.x(), op.y() + 1, op.z()));
                if (above != null && above.mode() != BlockOp.Mode.CLEAR) plan.ops.put(entry.getKey(), BlockOp.raw(op.x(), op.y(), op.z(), TOE_FULL, BlockOp.Mode.PLACE));
            }
        }
        return List.copyOf(plan.ops.values());
    }

    private static void post(final Plan plan, final List<TrackCell> cells, final int i, final int[] side,
                             final ToIntBiFunction<Integer, Integer> groundTop, final ToIntBiFunction<Integer, Integer> water, final Set<Long> track) {
        final TrackCell c = cells.get(i);
        final int cx = c.x() - side[0] * 3;
        final int cz = c.z() - side[1] * 3;
        final int rail = c.y();
        final int bed = rail - 1;
        if (track.contains(column(cx, cz)) || water.applyAsInt(cx, cz) != Integer.MIN_VALUE) return;
        final int g = groundTop.applyAsInt(cx, cz);
        if (Math.abs(g - bed) > 3) return;
        final int height = i % KILOMETRE == 0 ? 2 : 1;
        for (int y = rail; y < rail + height; y++) if (plan.guard.contains(new Voxel(cx, y, cz))) return;
        for (int y = g + 1; y < bed; y++) plan.fill(cx, y, cz);
        plan.place(cx, bed, cz, PLINTH);
        for (int y = rail; y < rail + height; y++) plan.place(cx, y, cz, POST);
    }

    private static void road(final Plan plan, final int x, final int z, final int g, final int bed, final CellWorks.Settings s) {
        if (Math.abs(g - bed) > s.roadMaxFill()) return; // too steep beside the track: the road leaves the railway here
        for (int y = Math.max(g, bed + 3); y > bed; y--) plan.clear(x, y, z);
        for (int y = g + 1; y < bed; y++) plan.fill(x, y, z);
        plan.part(x, bed, z, BlockOp.Part.ROAD);
    }

    static String facing(final int dx, final int dz) {
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        return dz > 0 ? "south" : "north";
    }

    private static long column(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Merge rules: overlay never replaces structure; clears never undo a placement; one op per voxel. */
    private static final class Plan {
        private static final Set<BlockOp.Part> REPLACEABLE = EnumSet.of(BlockOp.Part.AIR, BlockOp.Part.FILL, BlockOp.Part.BED,
            BlockOp.Part.BRIDGE_DECK, BlockOp.Part.ROAD);
        final Map<Voxel, BlockOp> ops;
        final Set<Voxel> guard;
        private final Set<Voxel> base;

        Plan(final Map<Voxel, BlockOp> ops, final Set<Voxel> guard) {
            this.ops = ops;
            this.guard = guard;
            this.base = new HashSet<>(ops.keySet());
        }

        void place(final int x, final int y, final int z, final String data) {
            put(BlockOp.raw(x, y, z, data, BlockOp.Mode.PLACE));
        }

        void part(final int x, final int y, final int z, final BlockOp.Part part) {
            put(BlockOp.place(x, y, z, part));
        }

        /** Decoration that must give way to a clearance envelope: there it becomes an explicit clear instead. */
        void placeOrClear(final int x, final int y, final int z, final String data) {
            if (this.guard.contains(new Voxel(x, y, z))) clear(x, y, z);
            else place(x, y, z, data);
        }

        void fill(final int x, final int y, final int z) {
            final Voxel v = new Voxel(x, y, z);
            if (!this.ops.containsKey(v)) this.ops.put(v, BlockOp.fill(x, y, z, BlockOp.Part.FILL));
        }

        void clear(final int x, final int y, final int z) {
            final Voxel v = new Voxel(x, y, z);
            final BlockOp existing = this.ops.get(v);
            if (existing == null) this.ops.put(v, BlockOp.clear(x, y, z));
        }

        private void put(final BlockOp op) {
            final Voxel v = Voxel.of(op);
            // Above the bed every placed block is an obstacle; it may never enter a clearance envelope.
            if (this.guard.contains(v)) return;
            final BlockOp existing = this.ops.get(v);
            if (existing != null && this.base.contains(v) && !(existing.mode() != BlockOp.Mode.PLACE || REPLACEABLE.contains(existing.part()))) return;
            this.ops.put(v, op);
        }
    }
}
