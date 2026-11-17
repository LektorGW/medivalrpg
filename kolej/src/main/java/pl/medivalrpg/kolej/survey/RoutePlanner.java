// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.survey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import pl.medivalrpg.kolej.network.CellKind;
import pl.medivalrpg.kolej.network.TrackCell;

/**
 * Surveys a railway line over the terrain ("inteligentnie"): A* over the grid with costs for steep ground (earthwork),
 * water (bridges) and curves, inside a corridor around the manager's way points, never through blocked columns. The
 * found path is smoothed, the vertical profile is fitted to the grade limit ({@link VerticalProfile}) and every cell is
 * classified as ground, embankment, cutting, bridge or tunnel. Pure and thread-safe (runs off the main thread).
 */
public final class RoutePlanner {

    /** Tunables (from config). */
    public record Params(
        double maxGrade,
        int corridorHalfWidth,
        int maxFill,
        int maxCut,
        int maxTunnel,
        int maxBridge,
        double slopeWeight,
        double waterCost,
        double turnPenalty,
        int minStraight,
        int maxExpanded,
        int stationLead
    ) {
        public static Params defaults() {
            return new Params(0.05, 48, 8, 6, 64, 96, 6.0, 4.0, 2.5, 2, 1_500_000, 10);
        }

        /**
         * Realistic style: at most 4% grade (1 block per 25), dearer and longer-spaced direction changes, so the
         * 45-degree raster steps form gentler, longer curves. Everything else is unchanged.
         */
        public static Params realistic() {
            return new Params(0.04, 48, 8, 6, 64, 96, 6.0, 4.0, 4.0, 4, 1_500_000, 10);
        }
    }

    /** An end of the surveyed line: rail cell, rail height and (for stations) the forced lead direction. */
    public record End(int x, int y, int z, int leadDx, int leadDz, int leadLength) {
        public static End junction(final int x, final int y, final int z) {
            return new End(x, y, z, 0, 0, 0);
        }

        boolean hasLead() {
            return (this.leadDx != 0 || this.leadDz != 0) && this.leadLength > 0;
        }
    }

    /** Survey outcome. {@code cells} is empty on failure; {@code problems} holds Polish notes for the manager. */
    public record Result(List<TrackCell> cells, List<String> problems, Map<CellKind, Integer> counts, long blocks) {
        public boolean ok() {
            return !this.cells.isEmpty();
        }
    }

    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    private final TerrainGrid terrain;
    private final Params p;

    public RoutePlanner(final TerrainGrid terrain, final Params params) {
        this.terrain = terrain;
        this.p = params;
    }

    /**
     * Picks the direction of a station lead: along the station axis ({@code axis} 0 = Z, 1 = X), towards the target.
     */
    public static int[] leadDirection(final int axis, final int fromX, final int fromZ, final int toX, final int toZ) {
        if (axis == 0) {
            return new int[] {0, toZ >= fromZ ? 1 : -1};
        }
        return new int[] {toX >= fromX ? 1 : -1, 0};
    }

    public Result survey(final End a, final End b, final List<int[]> via) {
        final List<String> problems = new ArrayList<>();
        // straight station leads (platform track) at both ends
        final List<int[]> leadA = lead(a);
        final List<int[]> leadB = lead(b);
        final int[] startCell = leadA.getLast();
        final int[] goalCell = leadB.getLast();
        final int startDir = a.hasLead() ? dirIndex(a.leadDx(), a.leadDz()) : -1;
        final int goalArrivalDir = b.hasLead() ? dirIndex(-b.leadDx(), -b.leadDz()) : -1;

        final List<int[]> waypoints = new ArrayList<>();
        waypoints.add(startCell);
        waypoints.addAll(via);
        waypoints.add(goalCell);
        for (final int[] w : waypoints) {
            if (!this.terrain.known(w[0], w[1])) {
                return fail("Teren w punkcie " + w[0] + ", " + w[1] + " nie jest zbadany.", problems);
            }
        }
        final List<int[]> grid = new ArrayList<>();
        int dir = startDir;
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            final boolean lastHop = i + 2 == waypoints.size();
            final List<int[]> hop = this.astar(waypoints.get(i), waypoints.get(i + 1), dir, lastHop ? goalArrivalDir : -1, waypoints);
            if (hop == null) {
                return fail("Nie znaleziono trasy między punktami " + fmt(waypoints.get(i)) + " i " + fmt(waypoints.get(i + 1))
                    + " (teren zablokowany, obcy kraj bez zgody albo zbyt skomplikowany — dodaj punkt pośredni).", problems);
            }
            if (!grid.isEmpty()) {
                hop.removeFirst();
            }
            grid.addAll(hop);
            if (grid.size() >= 2) {
                final int[] p1 = grid.get(grid.size() - 2);
                final int[] p2 = grid.getLast();
                dir = dirIndex(p2[0] - p1[0], p2[1] - p1[1]);
            }
        }
        final List<int[]> middle = RasterPath.smooth(grid, (x, z) -> this.terrain.known(x, z) && !this.terrain.blocked(x, z));
        final List<int[]> cells = new ArrayList<>(leadA.subList(0, leadA.size() - 1));
        cells.addAll(middle);
        final List<int[]> tail = new ArrayList<>(leadB.subList(0, leadB.size() - 1));
        java.util.Collections.reverse(tail);
        cells.addAll(tail);
        if (!RasterPath.valid(cells, (x, z) -> true)) {
            return fail("Trasa przecina samą siebie przy stacji — ustaw stację inaczej albo dodaj punkt pośredni.", problems);
        }
        return this.profile(cells, a, b, problems);
    }

    private Result profile(final List<int[]> cells, final End a, final End b, final List<String> problems) {
        final int n = cells.size();
        final double[] desired = new double[n];
        for (int i = 0; i < n; i++) {
            desired[i] = this.desiredRail(cells.get(i)[0], cells.get(i)[1]);
        }
        final double[] fitted = VerticalProfile.fit(desired, this.p.maxGrade(), a.y(), b.y());
        if (fitted == null) {
            return fail("Różnica wysokości " + Math.abs(b.y() - a.y()) + " bl. na " + n + " bl. trasy przekracza nachylenie "
                + Math.round(this.p.maxGrade() * 1000) / 10.0 + "% — wydłuż trasę punktem pośrednim.", problems);
        }
        final int[] h = new int[n];
        for (int i = 0; i < n; i++) {
            h[i] = (int) Math.round(fitted[i]);
        }
        h[0] = a.y();
        h[n - 1] = b.y();
        for (int i = 1; i < n; i++) {
            if (Math.abs(h[i] - h[i - 1]) > 1) {
                h[i] = h[i - 1] + Integer.signum(h[i] - h[i - 1]);
            }
        }
        final int unfixed = RasterPath.fixSteps(cells, h, 6);
        if (unfixed > 0) {
            problems.add(unfixed + " zmian wysokości wypada na łukach — tam szyny będą płaskie.");
        }
        final List<TrackCell> out = new ArrayList<>(n);
        final int leadA = a.hasLead() ? a.leadLength() : 0;
        final int leadB = b.hasLead() ? b.leadLength() : 0;
        for (int i = 0; i < n; i++) {
            final int x = cells.get(i)[0];
            final int z = cells.get(i)[1];
            CellKind kind = this.classify(x, z, h[i]);
            if ((i < leadA || i >= n - leadB) && kind != CellKind.BRIDGE && kind != CellKind.TUNNEL) {
                kind = CellKind.PLATFORM;
            }
            out.add(new TrackCell(x, h[i], z, kind));
        }
        final List<TrackCell> cleaned = cleanup(out, this.p);
        final Map<CellKind, Integer> counts = new EnumMap<>(CellKind.class);
        long blocks = 0;
        for (final TrackCell c : cleaned) {
            counts.merge(c.kind(), 1, Integer::sum);
            blocks += this.blocksFor(c);
        }
        this.checkRuns(cleaned, CellKind.TUNNEL, this.p.maxTunnel(), "Tunel", problems);
        this.checkRuns(cleaned, CellKind.BRIDGE, this.p.maxBridge(), "Most", problems);
        return new Result(cleaned, problems, counts, blocks);
    }

    /** Desired rail height: on the ground, or a bridge deck two blocks above water. */
    private double desiredRail(final int x, final int z) {
        final int w = this.terrain.water(x, z);
        if (w != TerrainGrid.NO_WATER) {
            return w + 3;
        }
        return this.terrain.ground(x, z) + 1;
    }

    private CellKind classify(final int x, final int z, final int railY) {
        if (this.terrain.water(x, z) != TerrainGrid.NO_WATER) {
            return CellKind.BRIDGE;
        }
        final int g = this.terrain.ground(x, z);
        final int bed = railY - 1;
        if (bed > g) {
            return bed - g > this.p.maxFill() ? CellKind.BRIDGE : CellKind.EMBANKMENT;
        }
        if (bed < g) {
            return g - bed > this.p.maxCut() ? CellKind.TUNNEL : CellKind.CUTTING;
        }
        return CellKind.GROUND;
    }

    /** Single-cell tunnels become cuttings and single-cell viaducts embankments (cheaper, nicer). */
    static List<TrackCell> cleanup(final List<TrackCell> in, final Params p) {
        final List<TrackCell> out = new ArrayList<>(in);
        for (int i = 1; i + 1 < out.size(); i++) {
            final TrackCell c = out.get(i);
            final CellKind before = out.get(i - 1).kind();
            final CellKind after = out.get(i + 1).kind();
            if (c.kind() == CellKind.TUNNEL && before != CellKind.TUNNEL && after != CellKind.TUNNEL) {
                out.set(i, c.withKind(CellKind.CUTTING));
            } else if (c.kind() == CellKind.BRIDGE && before != CellKind.BRIDGE && after != CellKind.BRIDGE) {
                out.set(i, c.withKind(CellKind.EMBANKMENT));
            }
        }
        return out;
    }

    private void checkRuns(final List<TrackCell> cells, final CellKind kind, final int max, final String name, final List<String> problems) {
        int run = 0;
        for (int i = 0; i <= cells.size(); i++) {
            if (i < cells.size() && cells.get(i).kind() == kind) {
                run++;
                continue;
            }
            if (run > max) {
                final TrackCell c = cells.get(i - 1);
                problems.add(name + " długości " + run + " bl. (limit " + max + ") koło " + c.x() + ", " + c.z() + ".");
            }
            run = 0;
        }
    }

    /** Rough number of blocks to place/remove for a cell (cost estimate). */
    long blocksFor(final TrackCell c) {
        final int g = this.terrain.ground(c.x(), c.z());
        final int bed = c.y() - 1;
        return switch (c.kind()) {
            case GROUND, PLATFORM -> 2 + 3;
            case EMBANKMENT -> 2 + Math.max(0, bed - g) * 3L;
            case CUTTING -> 2 + Math.max(0, g - bed) * 3L;
            case BRIDGE -> 6 + Math.max(0, bed - g) / 2;
            case TUNNEL -> 2 + 3 * 3 + 6;
        };
    }

    // ------------------------------------------------------------------ A*

    private record Node(int cell, double f) {
    }

    private List<int[]> astar(final int[] start, final int[] goal, final int startDir, final int arrivalDir, final List<int[]> waypoints) {
        final int hw = this.p.corridorHalfWidth();
        final int minX = Math.min(start[0], goal[0]) - hw;
        final int minZ = Math.min(start[1], goal[1]) - hw;
        final int maxX = Math.max(start[0], goal[0]) + hw;
        final int maxZ = Math.max(start[1], goal[1]) + hw;
        final int w = maxX - minX + 1;
        final int d = maxZ - minZ + 1;
        if ((long) w * d > 16_000_000L) {
            return null;
        }
        // compact numbering of the corridor cells only (memory ~ corridor area, not the bounding box)
        final int[] index = new int[w * d];
        int count = 0;
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < d; iz++) {
                index[ix * d + iz] = inCorridor(ix + minX, iz + minZ, waypoints, hw) ? count++ : -1;
            }
        }
        final int sCount = this.p.minStraight() + 1;
        final long states = (long) count * 8 * sCount;
        if (states > 24_000_000L) {
            return null;
        }
        final int[] cellX = new int[count];
        final int[] cellZ = new int[count];
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < d; iz++) {
                final int c = index[ix * d + iz];
                if (c >= 0) {
                    cellX[c] = ix + minX;
                    cellZ[c] = iz + minZ;
                }
            }
        }
        final float[] g = new float[(int) states];
        final int[] parent = new int[(int) states];
        java.util.Arrays.fill(g, Float.MAX_VALUE);
        java.util.Arrays.fill(parent, -1);
        final PriorityQueue<Node> open = new PriorityQueue<>((x, y) -> Double.compare(x.f(), y.f()));
        final int startIndex = index[(start[0] - minX) * d + (start[1] - minZ)];
        if (startIndex < 0) {
            return null;
        }
        for (int dir = 0; dir < 8; dir++) {
            if (startDir >= 0 && dir != startDir) {
                continue;
            }
            final int st = ((startIndex * 8 + dir) * sCount) + this.p.minStraight();
            g[st] = 0;
            open.add(new Node(st, heuristic(start[0], start[1], goal)));
        }
        int expanded = 0;
        int reached = -1;
        while (!open.isEmpty()) {
            final Node cur = open.poll();
            final int st = cur.cell();
            final int straight = st % sCount;
            final int dir = (st / sCount) % 8;
            final int cell = st / sCount / 8;
            final int x = cellX[cell];
            final int z = cellZ[cell];
            final double gc = g[st];
            if (cur.f() - heuristic(x, z, goal) > gc + 1e-6) {
                continue;
            }
            if (x == goal[0] && z == goal[1] && (arrivalDir < 0 || dir == arrivalDir)) {
                reached = st;
                break;
            }
            if (++expanded > this.p.maxExpanded()) {
                return null;
            }
            for (int turn = -1; turn <= 1; turn++) {
                if (turn != 0 && straight < this.p.minStraight()) {
                    continue;
                }
                final int nd = (dir + turn + 8) % 8;
                final int nx = x + DX[nd];
                final int nz = z + DZ[nd];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                final int ncell = index[(nx - minX) * d + (nz - minZ)];
                if (ncell < 0 || !this.passable(nx, nz)) {
                    continue;
                }
                if (nd % 2 == 1 && (!this.passable(x + DX[nd], z) || !this.passable(x, z + DZ[nd]))) {
                    continue; // diagonal needs both in-between cells (the rail staircase uses one of them)
                }
                final double len = nd % 2 == 1 ? Math.sqrt(2) : 1;
                double cost = len + this.stepCost(x, z, nx, nz, len);
                if (turn != 0) {
                    cost += this.p.turnPenalty();
                }
                final int ns = Math.min(this.p.minStraight(), turn == 0 ? straight + 1 : 0);
                final int nst = ((ncell * 8 + nd) * sCount) + ns;
                final double ng = gc + cost;
                if (ng < g[nst]) {
                    g[nst] = (float) ng;
                    parent[nst] = st;
                    open.add(new Node(nst, ng + heuristic(nx, nz, goal)));
                }
            }
        }
        if (reached < 0) {
            return null;
        }
        final List<int[]> out = new ArrayList<>();
        for (int st = reached; st >= 0; st = parent[st]) {
            final int cell = st / sCount / 8;
            final int[] c = {cellX[cell], cellZ[cell]};
            if (out.isEmpty() || out.getFirst()[0] != c[0] || out.getFirst()[1] != c[1]) {
                out.addFirst(c);
            }
        }
        return out;
    }

    private boolean passable(final int x, final int z) {
        return this.terrain.known(x, z) && !this.terrain.blocked(x, z);
    }

    private double stepCost(final int x, final int z, final int nx, final int nz, final double len) {
        double cost = 0;
        if (this.terrain.water(nx, nz) != TerrainGrid.NO_WATER) {
            cost += len * this.p.waterCost();
        } else {
            final int dg = Math.abs(this.terrain.ground(nx, nz) - this.terrain.ground(x, z));
            final double excess = dg - this.p.maxGrade() * len;
            if (excess > 0) {
                cost += excess * this.p.slopeWeight();
            }
        }
        return cost;
    }

    private static double heuristic(final int x, final int z, final int[] goal) {
        final int dx = Math.abs(goal[0] - x);
        final int dz = Math.abs(goal[1] - z);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    /** Within {@code hw} of the polyline through the way points. */
    static boolean inCorridor(final int x, final int z, final List<int[]> waypoints, final int hw) {
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            final int[] a = waypoints.get(i);
            final int[] b = waypoints.get(i + 1);
            final double abx = b[0] - a[0];
            final double abz = b[1] - a[1];
            final double len2 = abx * abx + abz * abz;
            double t = len2 == 0 ? 0 : ((x - a[0]) * abx + (z - a[1]) * abz) / len2;
            t = Math.max(0, Math.min(1, t));
            final double px = a[0] + abx * t - x;
            final double pz = a[1] + abz * t - z;
            if (px * px + pz * pz <= (double) hw * hw) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> lead(final End e) {
        final List<int[]> out = new ArrayList<>();
        out.add(new int[] {e.x(), e.z()});
        if (e.hasLead()) {
            for (int i = 1; i <= e.leadLength(); i++) {
                out.add(new int[] {e.x() + e.leadDx() * i, e.z() + e.leadDz() * i});
            }
        }
        return out;
    }

    private static int dirIndex(final int dx, final int dz) {
        for (int i = 0; i < 8; i++) {
            if (DX[i] == Integer.signum(dx) && DZ[i] == Integer.signum(dz)) {
                return i;
            }
        }
        return -1;
    }

    private static String fmt(final int[] c) {
        return c[0] + ", " + c[1];
    }

    private static Result fail(final String why, final List<String> problems) {
        final List<String> all = new ArrayList<>(problems);
        all.add(why);
        return new Result(List.of(), all, Map.of(), 0);
    }
}
