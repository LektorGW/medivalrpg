// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionRouteTest {
    private TrackGraph graph() {
        TrackGraph graph = new TrackGraph();
        graph.putNode(new TrackNode("west", "West", NodeType.STATION, "world", -2, 64, 0, 1, 1));
        graph.putNode(new TrackNode("switch", "Switch", NodeType.JUNCTION, "world", 0, 64, 0, -1, 1));
        graph.putNode(new TrackNode("north", "North", NodeType.STATION, "world", 0, 64, -2, 0, 1));
        TrackSegment west = new TrackSegment("w", "west", "switch");
        west.cells(List.of(cell(-2, 0), cell(-1, 0), cell(0, 0)));
        west.status(SegmentStatus.BUILT);
        TrackSegment north = new TrackSegment("n", "switch", "north");
        north.cells(List.of(cell(0, 0), cell(0, -1), cell(0, -2)));
        north.status(SegmentStatus.BUILT);
        graph.putSegment(west); graph.putSegment(north);
        return graph;
    }
    private TrackCell cell(int x, int z) { return new TrackCell(x, 64, z, CellKind.GROUND); }
    @Test void curvedSwitchIsSameInBothTravelDirections() {
        TrackGraph g = graph();
        assertEquals("NORTH_WEST", JunctionSwitch.routeShape(g, Route.of(g,
            List.of("west", "switch", "north"), List.of("w", "n")), 1).orElseThrow());
        assertEquals("NORTH_WEST", JunctionSwitch.routeShape(g, Route.of(g,
            List.of("north", "switch", "west"), List.of("n", "w")), 1).orElseThrow());
    }
    @Test void missingPhysicalTrackAndSlopeDoNotAuthorizeSwitch() {
        TrackGraph g = graph();
        Route r = Route.of(g, List.of("west", "switch", "north"), List.of("w", "n"));
        g.segment("w").status(SegmentStatus.PLANNED);
        assertTrue(JunctionSwitch.routeShape(g, r, 1).isEmpty());
        g.segment("w").status(SegmentStatus.BUILT);
        g.segment("w").cells(List.of(cell(-2, 0), new TrackCell(-1, 65, 0, CellKind.GROUND), cell(0, 0)));
        assertTrue(JunctionSwitch.routeShape(g, r, 1).isEmpty());
    }
    @Test void malformedEndpointAndNonJunctionFailClosed() {
        TrackGraph g = graph();
        Route r = Route.of(g, List.of("west", "switch", "north"), List.of("w", "n"));
        g.segment("w").cells(List.of(cell(-2, 0), cell(-1, 0)));
        assertTrue(JunctionSwitch.routeShape(g, r, 1).isEmpty());
        assertTrue(JunctionSwitch.routeShape(g, r, 0).isEmpty());
        assertTrue(JunctionSwitch.routeShape(g, r, 2).isEmpty());
    }
}
