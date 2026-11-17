// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import pl.medivalrpg.ludnosc.api.KingdomMapInfo;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;

/** Pure geometry compression: long road runs replace thousands of individual block markers. */
public final class KingdomMapGeometry {
    private KingdomMapGeometry() { }

    public static List<KingdomMapInfo.Road> built(final String world, final Set<Long> cells, final int limit) {
        final List<KingdomMapInfo.Road> out = new ArrayList<>();
        for (final long cell : cells) {
            final int x = RoadPlanner.keyX(cell);
            final int z = RoadPlanner.keyZ(cell);
            if (!cells.contains(RoadPlanner.key(x - 1, z))) {
                int end = x;
                while (end < Integer.MAX_VALUE && cells.contains(RoadPlanner.key(end + 1, z))) end++;
                if (end > x) out.add(road(world, x, z, end, z, false));
            }
            if (!cells.contains(RoadPlanner.key(x, z - 1))) {
                int end = z;
                while (end < Integer.MAX_VALUE && cells.contains(RoadPlanner.key(x, end + 1))) end++;
                if (end > z) out.add(road(world, x, z, x, end, false));
            }
        }
        // Long trunk roads remain visible even when a country has a dense street grid.
        out.sort(Comparator.<KingdomMapInfo.Road>comparingLong(KingdomMapGeometry::length).reversed()
            .thenComparingInt(r -> r.points().getFirst().x()).thenComparingInt(r -> r.points().getFirst().z())
            .thenComparingInt(r -> r.points().getLast().x()).thenComparingInt(r -> r.points().getLast().z()));
        return List.copyOf(out.subList(0, Math.min(out.size(), Math.max(0, limit))));
    }

    private static long length(final KingdomMapInfo.Road road) {
        final var a = road.points().getFirst();
        final var b = road.points().getLast();
        return Math.abs((long) b.x() - a.x()) + Math.abs((long) b.z() - a.z());
    }

    /** Keeps bends; splits discontinuities instead of drawing imaginary roads over gaps. */
    public static List<KingdomMapInfo.Road> planned(final String world, final List<Long> path, final int limit) {
        final List<KingdomMapInfo.Road> out = new ArrayList<>();
        List<KingdomMapInfo.Point> line = new ArrayList<>();
        KingdomMapInfo.Point previous = null;
        for (final long key : path) {
            final var next = new KingdomMapInfo.Point(RoadPlanner.keyX(key), RoadPlanner.keyZ(key));
            if (next.equals(previous)) continue;
            if (previous != null && Math.abs((long) next.x() - previous.x()) + Math.abs((long) next.z() - previous.z()) != 1) {
                add(out, world, line, limit);
                line = new ArrayList<>();
            }
            if (line.size() >= 2) {
                final var a = line.get(line.size() - 2);
                final var b = line.getLast();
                if ((long) (b.x() - a.x()) * (next.z() - b.z()) == (long) (b.z() - a.z()) * (next.x() - b.x())
                    && (long) (b.x() - a.x()) * (next.x() - b.x()) + (long) (b.z() - a.z()) * (next.z() - b.z()) > 0) {
                    line.removeLast();
                }
            }
            line.add(next);
            previous = next;
            if (line.size() >= 128) {
                add(out, world, line, limit);
                line = new ArrayList<>(List.of(next));
            }
            if (out.size() >= Math.max(0, limit)) break;
        }
        add(out, world, line, limit);
        return List.copyOf(out);
    }

    private static void add(final List<KingdomMapInfo.Road> out, final String world,
                            final List<KingdomMapInfo.Point> points, final int limit) {
        if (out.size() < limit && points.size() >= 2) out.add(new KingdomMapInfo.Road(world, points, true));
    }

    private static KingdomMapInfo.Road road(String world, int x1, int z1, int x2, int z2, boolean planned) {
        return new KingdomMapInfo.Road(world, List.of(new KingdomMapInfo.Point(x1, z1), new KingdomMapInfo.Point(x2, z2)), planned);
    }
}
