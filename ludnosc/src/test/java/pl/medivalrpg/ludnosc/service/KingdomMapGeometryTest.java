// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.api.KingdomMapInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.logic.KingdomMapGeometry;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import static org.junit.jupiter.api.Assertions.*;

class KingdomMapGeometryTest {
    @Test void thousandsOfRoadCellsBecomeTwoLinesAtACrossing() {
        final Set<Long> cells = new HashSet<>();
        for (int n = -1000; n <= 1000; n++) {
            cells.add(RoadPlanner.key(n, 0));
            cells.add(RoadPlanner.key(0, n));
        }
        final var lines = KingdomMapGeometry.built("world", cells, 256);
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(r -> r.points().size() == 2 && !r.planned()));
    }

    @Test void longestTrunksWinTheMarkerBudgetAndGapsStayEmpty() {
        final Set<Long> cells = new HashSet<>();
        for (int n = 0; n < 100; n++) cells.add(RoadPlanner.key(n, 0));
        for (int n = 0; n < 10; n++) cells.add(RoadPlanner.key(n, 20));
        cells.add(RoadPlanner.key(200, 0)); cells.add(RoadPlanner.key(201, 0));
        final var lines = KingdomMapGeometry.built("world", cells, 1);
        assertEquals(1, lines.size());
        assertEquals(new KingdomMapInfo.Point(99, 0), lines.getFirst().points().getLast());
        assertTrue(KingdomMapGeometry.built("world", cells, 0).isEmpty());
    }

    @Test void pendingTrunkRetainsBendsButRemovesCollinearIntermediateCells() {
        final List<Long> path = new ArrayList<>();
        for (int n = 0; n <= 100; n++) path.add(RoadPlanner.key(n, 0));
        for (int n = 1; n <= 100; n++) path.add(RoadPlanner.key(100, n));
        final var lines = KingdomMapGeometry.planned("world", path, 32);
        assertEquals(1, lines.size());
        assertEquals(List.of(new KingdomMapInfo.Point(0, 0), new KingdomMapInfo.Point(100, 0),
            new KingdomMapInfo.Point(100, 100)), lines.getFirst().points());
        assertTrue(lines.getFirst().planned());
    }

    @Test void disjointPendingSectionsAreNeverJoinedAcrossUnbuiltLand() {
        final var lines = KingdomMapGeometry.planned("world", List.of(RoadPlanner.key(0, 0), RoadPlanner.key(1, 0),
            RoadPlanner.key(100, 0), RoadPlanner.key(101, 0)), 32);
        assertEquals(2, lines.size());
        assertEquals(2, lines.getFirst().points().size());
        assertEquals(1, KingdomMapGeometry.planned("world", List.of(RoadPlanner.key(0, 0), RoadPlanner.key(1, 0),
            RoadPlanner.key(100, 0), RoadPlanner.key(101, 0)), 1).size());
    }

    @Test void snapshotListsAreImmutableAndApiMethodHasAnOlderProviderFallback() throws Exception {
        final List<KingdomMapInfo.Settlement> centres = new ArrayList<>();
        centres.add(new KingdomMapInfo.Settlement("world", 4, 5, true));
        final var snapshot = new KingdomMapInfo(centres, List.of());
        centres.clear();
        assertEquals(1, snapshot.settlements().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.settlements().clear());
        assertTrue(LudnoscApi.class.getMethod("kingdomMap", java.util.UUID.class).isDefault());
    }
}
