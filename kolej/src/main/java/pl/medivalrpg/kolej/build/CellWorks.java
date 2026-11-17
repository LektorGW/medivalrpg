// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import pl.medivalrpg.kolej.network.CellKind;
import pl.medivalrpg.kolej.network.TrackCell;

/**
 * Pure block plan for one track cell: earthworks (embankment fill, cutting), bed and rail, bridge deck with railings
 * and pillars, tunnel bore with lining and lights, the road (trakt) alongside, lamp posts and signal posts. Operations
 * are ordered so they can be executed one by one (clear first, then fill bottom-up, then bed and rail, then extras).
 */
public final class CellWorks {

    /** Settings (from config). */
    public record Settings(int clearance, int pillarEvery, int lampEvery, boolean road, int roadMaxFill, int signalInset) {
        public static Settings defaults() {
            return new Settings(5, 4, 24, true, 3, 6);
        }
    }

    private CellWorks() {
    }

    /**
     * @param cells      all cells of the segment
     * @param i          index of the cell to plan
     * @param groundTop  current ground top (y) at the cell
     * @param seabed     ground under water at the cell (= groundTop when dry)
     * @param water      water surface y or {@link Integer#MIN_VALUE}
     * @param isTrack    true for columns occupied by any track cell (road/lamps never go there)
     * @param sideGround ground top lookup for side columns (road)
     */
    public static List<BlockOp> plan(final List<TrackCell> cells, final int i, final int groundTop, final int seabed, final int water,
                                     final BiPredicate<Integer, Integer> isTrack, final java.util.function.ToIntBiFunction<Integer, Integer> sideGround,
                                     final Settings s) {
        final TrackCell c = cells.get(i);
        final int x = c.x();
        final int z = c.z();
        final int rail = c.y();
        final int bed = rail - 1;
        // Models reach rail + 4m, including RAIL_TOP; keep a full block of margin.
        // A grade additionally raises the envelope towards the higher neighbouring rail.
        final boolean grade = cells.get(Math.max(0, i - 1)).y() != rail
            || cells.get(Math.min(cells.size() - 1, i + 1)).y() != rail;
        final int clearance = Math.max(5, s.clearance()) + (grade ? 1 : 0);
        final int[] side = perpendicular(cells, i);
        final List<BlockOp> ops = new ArrayList<>();
        final boolean nearEnd = i < 2 || i > cells.size() - 3;

        switch (c.kind()) {
            case TUNNEL -> {
                if (nearSharpCurve(cells, i)) {
                    // Local chamber only: 7m body + wheel width sweeps up to 3.74m
                    // from its centre over a turn. Nine interior columns cover every yaw.
                    for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                        int cx = x + dx, cz = z + dz;
                        if ((dx != 0 || dz != 0) && isTrack.test(cx, cz)) continue;
                        boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                        if (wall) {
                            for (int y = bed; y < rail + clearance; y++) ops.add(BlockOp.place(cx, y, cz, BlockOp.Part.TUNNEL_WALL));
                        } else {
                            for (int y = rail + clearance - 1; y >= rail; y--) ops.add(BlockOp.clear(cx, y, cz));
                            ops.add(BlockOp.place(cx, rail + clearance, cz, BlockOp.Part.TUNNEL_CEILING));
                            if (dx != 0 || dz != 0) ops.add(BlockOp.place(cx, bed, cz, BlockOp.Part.TUNNEL_WALL));
                        }
                    }
                    ops.add(BlockOp.place(x, bed, z, BlockOp.Part.BED));
                    ops.add(BlockOp.place(x, rail, z, BlockOp.Part.RAIL));
                    break;
                }
                for (int w = -2; w <= 2; w++) {
                    final int cx = x + side[0] * w;
                    final int cz = z + side[1] * w;
                    if (w != 0 && isTrack.test(cx, cz)) {
                        continue;
                    }
                    for (int y = rail + clearance - 1; y >= rail; y--) {
                        ops.add(BlockOp.clear(cx, y, cz));
                    }
                    ops.add(BlockOp.place(cx, rail + clearance, cz, BlockOp.Part.TUNNEL_CEILING));
                    if (w != 0) {
                        ops.add(BlockOp.place(cx, bed, cz, BlockOp.Part.TUNNEL_WALL));
                    }
                }
                for (final int w : new int[] {-3, 3}) {
                    final int cx = x + side[0] * w;
                    final int cz = z + side[1] * w;
                    if (isTrack.test(cx, cz)) {
                        continue;
                    }
                    for (int y = bed; y < rail + clearance; y++) {
                        final boolean light = w == 3 && y == rail + 1 && s.lampEvery() > 0 && i % Math.max(4, s.lampEvery() / 2) == 0;
                        ops.add(BlockOp.place(cx, y, cz, light ? BlockOp.Part.TUNNEL_LIGHT : BlockOp.Part.TUNNEL_WALL));
                    }
                }
                ops.add(BlockOp.place(x, bed, z, BlockOp.Part.BED));
                ops.add(BlockOp.place(x, rail, z, BlockOp.Part.RAIL));
            }
            case BRIDGE -> {
                for (int w = -2; w <= 2; w++) {
                    final int cx = x + side[0] * w;
                    final int cz = z + side[1] * w;
                    if (w != 0 && isTrack.test(cx, cz)) continue;
                    if (Math.abs(w) <= 1) {
                        for (int y = rail + clearance - 1; y >= rail; y--) ops.add(BlockOp.clear(cx, y, cz));
                    } else {
                        ops.add(BlockOp.clear(cx, rail + 1, cz));
                    }
                }
                if (i % Math.max(1, s.pillarEvery()) == 0) {
                    final int bottom = Math.min(seabed, groundTop) + 1;
                    for (int y = bottom; y < bed; y++) {
                        ops.add(BlockOp.place(x, y, z, BlockOp.Part.BRIDGE_PILLAR));
                    }
                }
                for (int w = -2; w <= 2; w++) {
                    final int cx = x + side[0] * w;
                    final int cz = z + side[1] * w;
                    if (w != 0 && isTrack.test(cx, cz)) continue;
                    ops.add(BlockOp.place(cx, bed, cz, BlockOp.Part.BRIDGE_DECK));
                    if (Math.abs(w) == 2) ops.add(BlockOp.place(cx, rail, cz, BlockOp.Part.BRIDGE_RAILING));
                }
                ops.add(BlockOp.place(x, rail, z, BlockOp.Part.RAIL));
                if (s.lampEvery() > 0 && i % s.lampEvery() == 0 && !isTrack.test(x - side[0] * 2, z - side[1] * 2)) {
                    ops.add(BlockOp.place(x - side[0] * 2, rail + 1, z - side[1] * 2, BlockOp.Part.LAMP));
                }
            }
            default -> {
                for (int w = -1; w <= 1; w++) {
                    final int cx = x + side[0] * w;
                    final int cz = z + side[1] * w;
                    if (w != 0 && isTrack.test(cx, cz)) continue;
                    final int top = Math.max(w == 0 ? groundTop : sideGround.applyAsInt(cx, cz), rail + clearance - 1);
                    for (int y = top; y >= rail; y--) ops.add(BlockOp.clear(cx, y, cz));
                }
                for (int y = groundTop + 1; y < bed; y++) {
                    ops.add(BlockOp.fill(x, y, z, BlockOp.Part.FILL));
                }
                if (c.kind() == CellKind.EMBANKMENT) {
                    // shoulders so the embankment does not look like a wall one block wide
                    for (final int w : new int[] {-1, 1}) {
                        final int cx = x + side[0] * w;
                        final int cz = z + side[1] * w;
                        if (!isTrack.test(cx, cz)) {
                            for (int y = sideGround.applyAsInt(cx, cz) + 1; y < bed; y++) {
                                ops.add(BlockOp.fill(cx, y, cz, BlockOp.Part.FILL));
                            }
                        }
                    }
                }
                ops.add(BlockOp.place(x, bed, z, c.kind() == CellKind.PLATFORM ? BlockOp.Part.BED : BlockOp.Part.BED));
                ops.add(BlockOp.place(x, rail, z, BlockOp.Part.RAIL));
                if (s.road() && c.kind() != CellKind.PLATFORM && !nearEnd) {
                    for (final int w : new int[] {2, 3}) {
                        road(ops, x + side[0] * w, z + side[1] * w, bed, isTrack, sideGround, s);
                    }
                }
                if (s.lampEvery() > 0 && i % s.lampEvery() == 0 && !nearEnd) {
                    lamp(ops, x - side[0] * 2, z - side[1] * 2, bed, rail, isTrack, sideGround, false);
                }
                if (s.signalInset() > 0 && (i == s.signalInset() || i == cells.size() - 1 - s.signalInset())) {
                    lamp(ops, x - side[0] * 2, z - side[1] * 2, bed, rail, isTrack, sideGround, true);
                }
            }
        }
        return ops;
    }

    private static void road(final List<BlockOp> ops, final int x, final int z, final int bed, final BiPredicate<Integer, Integer> isTrack,
                             final java.util.function.ToIntBiFunction<Integer, Integer> sideGround, final Settings s) {
        if (isTrack.test(x, z)) {
            return;
        }
        final int g = sideGround.applyAsInt(x, z);
        if (Math.abs(g - bed) > s.roadMaxFill()) {
            return; // too steep beside the track: the road leaves the railway here
        }
        for (int y = Math.max(g, bed + 3); y > bed; y--) {
            ops.add(BlockOp.clear(x, y, z));
        }
        for (int y = g + 1; y < bed; y++) {
            ops.add(BlockOp.fill(x, y, z, BlockOp.Part.FILL));
        }
        ops.add(BlockOp.place(x, bed, z, BlockOp.Part.ROAD));
    }

    private static void lamp(final List<BlockOp> ops, final int x, final int z, final int bed, final int rail,
                             final BiPredicate<Integer, Integer> isTrack, final java.util.function.ToIntBiFunction<Integer, Integer> sideGround,
                             final boolean signal) {
        if (isTrack.test(x, z)) {
            return;
        }
        final int g = sideGround.applyAsInt(x, z);
        if (Math.abs(g - bed) > 3) {
            return;
        }
        for (int y = g + 1; y <= bed; y++) {
            ops.add(BlockOp.fill(x, y, z, BlockOp.Part.FILL));
        }
        final BlockOp.Part post = signal ? BlockOp.Part.SIGNAL_POST : BlockOp.Part.LAMP_POST;
        ops.add(BlockOp.place(x, rail, z, post));
        ops.add(BlockOp.place(x, rail + 1, z, post));
        ops.add(BlockOp.place(x, rail + 2, z, signal ? BlockOp.Part.SIGNAL_HEAD : BlockOp.Part.LAMP));
    }

    /** Sharp turn within four cells; only tunnel cells use this local chamber rule. */
    static boolean nearSharpCurve(final List<TrackCell> cells, final int i) {
        // Constant-size lookaround, not a sweep against the entire graph.
        for (int j = Math.max(1, i - 4); j <= Math.min(cells.size() - 2, i + 4); j++) {
            TrackCell a = cells.get(j - 1), c = cells.get(j), b = cells.get(j + 1);
            int ax = c.x() - a.x(), az = c.z() - a.z(), bx = b.x() - c.x(), bz = b.z() - c.z();
            if (ax * bz != az * bx) return true;
        }
        return false;
    }

    /** Horizontal unit perpendicular (right-hand side) of the track direction at cell {@code i}. */
    public static int[] perpendicular(final List<TrackCell> cells, final int i) {
        final TrackCell a = cells.get(Math.max(0, i - 1));
        final TrackCell b = cells.get(Math.min(cells.size() - 1, i + 1));
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        if (dx == 0 && dz == 0) {
            dz = 1;
        }
        // right-hand perpendicular of (dx, dz) is (-dz, dx); snap to the dominant axis
        final int px = -dz;
        final int pz = dx;
        if (Math.abs(px) >= Math.abs(pz)) {
            return new int[] {Integer.signum(px), 0};
        }
        return new int[] {0, Integer.signum(pz)};
    }

    /** Position of the signal head for the segment end at index {@code i} (or null when this cell has none). */
    public static int[] signalHead(final List<TrackCell> cells, final int i, final Settings s) {
        if (s.signalInset() <= 0 || (i != s.signalInset() && i != cells.size() - 1 - s.signalInset())) {
            return null;
        }
        final int[] side = perpendicular(cells, i);
        final TrackCell c = cells.get(i);
        return new int[] {c.x() - side[0] * 2, c.y() + 2, c.z() - side[1] * 2};
    }
}
