// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla village detection (pure, unit tested): the bell (meeting point) is the centre; the village covers the
 * chunks holding its beds, job sites and fields within a radius of the bell. The chunk set is made connected
 * (4-neighbourhood) by adding the chunks of a straight path between separated parts, so the country that takes the
 * village owns one continuous territory.
 */
public final class VillageScan {

    /** Kind of a point of interest. */
    public enum Kind { MEETING, HOME, JOB }

    /** A point of interest (bell, bed head, job-site block). */
    public record Poi(Kind kind, int x, int y, int z) {

        public long chunk() {
            return key(this.x >> 4, this.z >> 4);
        }
    }

    /**
     * Result.
     *
     * @param bell     the centre
     * @param chunks   connected chunk keys ({@link #key})
     * @param beds     beds (HOME POIs) inside the chunks
     * @param jobSites job-site blocks inside the chunks
     * @param farmland farmland blocks inside the chunks
     */
    public record Result(Poi bell, Set<Long> chunks, int beds, int jobSites, int farmland) {
    }

    private VillageScan() {
    }

    public static long key(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int cx(final long key) {
        return (int) (key >> 32);
    }

    public static int cz(final long key) {
        return (int) key;
    }

    /** The meeting point nearest to (x, z), or null. */
    public static Poi bell(final Collection<Poi> pois, final int x, final int z) {
        Poi best = null;
        long bestD = Long.MAX_VALUE;
        for (final Poi p : pois) {
            if (p.kind() != Kind.MEETING) {
                continue;
            }
            final long d = (long) (p.x() - x) * (p.x() - x) + (long) (p.z() - z) * (p.z() - z);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * The village around {@code bell}.
     *
     * @param pois             points of interest around the bell (any radius; filtered here)
     * @param farmlandByChunk  farmland blocks per chunk key
     * @param radius           horizontal radius around the bell (blocks)
     * @param minFarmland      farmland blocks that make a chunk part of the village
     */
    public static Result scan(final Poi bell, final Collection<Poi> pois, final Map<Long, Integer> farmlandByChunk,
                              final int radius, final int minFarmland) {
        final Set<Long> core = new LinkedHashSet<>();
        core.add(bell.chunk());
        final long r2 = (long) radius * radius;
        for (final Poi p : pois) {
            if (p.kind() != Kind.MEETING && dist2(bell, p.x(), p.z()) <= r2) {
                core.add(p.chunk());
            }
        }
        for (final Map.Entry<Long, Integer> e : farmlandByChunk.entrySet()) {
            final int mx = cx(e.getKey()) * 16 + 8;
            final int mz = cz(e.getKey()) * 16 + 8;
            if (e.getValue() >= minFarmland && dist2(bell, mx, mz) <= r2 + 2L * 16 * 16) {
                core.add(e.getKey());
            }
        }
        final Set<Long> chunks = connect(core, bell.chunk());
        int beds = 0;
        int jobs = 0;
        for (final Poi p : pois) {
            if (!chunks.contains(p.chunk())) {
                continue;
            }
            if (p.kind() == Kind.HOME) {
                beds++;
            } else if (p.kind() == Kind.JOB) {
                jobs++;
            }
        }
        int farmland = 0;
        for (final Map.Entry<Long, Integer> e : farmlandByChunk.entrySet()) {
            if (chunks.contains(e.getKey())) {
                farmland += e.getValue();
            }
        }
        return new Result(bell, Set.copyOf(chunks), beds, jobs, farmland);
    }

    private static long dist2(final Poi bell, final int x, final int z) {
        return (long) (x - bell.x()) * (x - bell.x()) + (long) (z - bell.z()) * (z - bell.z());
    }

    /** Adds the chunks of straight (L-shaped) paths so that every chunk is 4-connected to {@code root}. */
    public static Set<Long> connect(final Set<Long> chunks, final long root) {
        final Set<Long> out = new LinkedHashSet<>(chunks);
        out.add(root);
        while (true) {
            final Set<Long> reached = reach(out, root);
            if (reached.size() == out.size()) {
                return out;
            }
            // nearest pair (outside chunk, reached chunk); join them
            long bestA = 0;
            long bestB = 0;
            long bestD = Long.MAX_VALUE;
            for (final long a : out) {
                if (reached.contains(a)) {
                    continue;
                }
                for (final long b : reached) {
                    final long d = Math.abs((long) cx(a) - cx(b)) + Math.abs((long) cz(a) - cz(b));
                    if (d < bestD) {
                        bestD = d;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            int x = cx(bestB);
            int z = cz(bestB);
            while (x != cx(bestA)) {
                x += Integer.signum(cx(bestA) - x);
                out.add(key(x, z));
            }
            while (z != cz(bestA)) {
                z += Integer.signum(cz(bestA) - z);
                out.add(key(x, z));
            }
        }
    }

    /** Chunks 4-connected to {@code root} within the set. */
    public static Set<Long> reach(final Set<Long> chunks, final long root) {
        final Set<Long> seen = new HashSet<>();
        if (!chunks.contains(root)) {
            return seen;
        }
        final ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty()) {
            final long c = queue.poll();
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final long n = key(cx(c) + d[0], cz(c) + d[1]);
                if (chunks.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** True when every chunk of the set is 4-connected to every other. */
    public static boolean connected(final Set<Long> chunks) {
        return chunks.isEmpty() || reach(chunks, chunks.iterator().next()).size() == chunks.size();
    }

    // ------------------------------------------------------------------ houses

    /** Block access for the house finder: what is at a position. */
    @FunctionalInterface
    public interface Blocks {
        /** {@link #OPEN} (air, bed, carpet, torch...), {@link #WALL} (solid, glass, door) or {@link #UNKNOWN}. */
        int at(int x, int y, int z);
    }

    public static final int OPEN = 0;
    public static final int WALL = 1;
    public static final int UNKNOWN = 2;

    /** A house: box of the room(s) with its walls, floor and roof, and the beds in it. */
    public record House(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int beds) {

        boolean touches(final House o) {
            return this.minX <= o.maxX && o.minX <= this.maxX && this.minY <= o.maxY && o.minY <= this.maxY
                && this.minZ <= o.maxZ && o.minZ <= this.maxZ;
        }

        House merge(final House o) {
            return new House(Math.min(this.minX, o.minX), Math.min(this.minY, o.minY), Math.min(this.minZ, o.minZ),
                Math.max(this.maxX, o.maxX), Math.max(this.maxY, o.maxY), Math.max(this.maxZ, o.maxZ), this.beds + o.beds);
        }
    }

    /**
     * Houses around beds: the room of each bed is flood-filled through open cells (doors and windows close it) within
     * {@code reach} blocks horizontally and 5 up; the house is the room's box grown by one block (walls, floor,
     * roof). A bed whose "room" leaks out of that range (a bed in the open, a ruin) gets a small box around it.
     * Houses that touch are merged (beds summed).
     */
    public static List<House> houses(final Blocks world, final List<int[]> beds, final int reach) {
        final List<House> out = new ArrayList<>();
        for (final int[] bed : beds) {
            House h = room(world, bed, reach);
            if (h == null) {
                h = new House(bed[0] - 2, bed[1] - 1, bed[2] - 2, bed[0] + 2, bed[1] + 3, bed[2] + 2, 1);
            }
            out.add(h);
        }
        // merge touching houses until stable
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < out.size(); i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    if (out.get(i).touches(out.get(j))) {
                        out.set(i, out.get(i).merge(out.remove(j)));
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return out;
    }

    private static House room(final Blocks world, final int[] bed, final int reach) {
        final int bx = bed[0];
        final int by = bed[1];
        final int bz = bed[2];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        final Set<Long> seen = new HashSet<>();
        queue.add(new int[] {bx, by, bz});
        seen.add(MineDigger.key(bx, by, bz));
        int minX = bx;
        int minY = by;
        int minZ = bz;
        int maxX = bx;
        int maxY = by;
        int maxZ = bz;
        while (!queue.isEmpty()) {
            final int[] c = queue.poll();
            for (final int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                final int x = c[0] + d[0];
                final int y = c[1] + d[1];
                final int z = c[2] + d[2];
                if (!seen.add(MineDigger.key(x, y, z))) {
                    continue;
                }
                final int what = world.at(x, y, z);
                if (what == WALL) {
                    continue;
                }
                if (what == UNKNOWN || Math.abs(x - bx) > reach || Math.abs(z - bz) > reach || y > by + 5 || y < by - 1) {
                    return null; // leaks out: not a closed room
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                queue.add(new int[] {x, y, z});
            }
        }
        return new House(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1, 1);
    }
}
