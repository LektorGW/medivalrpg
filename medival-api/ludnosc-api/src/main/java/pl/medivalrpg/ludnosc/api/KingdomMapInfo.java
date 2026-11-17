// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.api;

import java.util.List;

/** Immutable, bounded kingdom map snapshot. Coordinates are blocks; routes never join different worlds. */
public record KingdomMapInfo(List<Settlement> settlements, List<Road> roads) {
    public KingdomMapInfo {
        settlements = List.copyOf(settlements);
        roads = List.copyOf(roads);
    }

    public record Settlement(String world, int x, int z, boolean capital) { }
    public record Point(int x, int z) { }
    /** Planned roads are displayed separately from physically completed roads. */
    public record Road(String world, List<Point> points, boolean planned) {
        public Road { points = List.copyOf(points); }
    }
}
