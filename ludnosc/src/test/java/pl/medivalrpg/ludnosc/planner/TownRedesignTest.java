// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;

/** Plan v2: what stays, what goes, where the streets run, levelling without walls. */
class TownRedesignTest {

    private static BlockBox box(final int x1, final int z1, final int x2, final int z2) {
        return new BlockBox("w", x1, 64, z1, x2, 70, z2);
    }

    @Test
    void streetsGoThroughTheCentreAndDemolishOnlyWhatMayGo() {
        final UUID onAvenue = UUID.randomUUID();
        final UUID church = UUID.randomUUID();
        final UUID playerHouse = UUID.randomUUID();
        final UUID aside = UUID.randomUUID();
        final List<TownRedesign.Existing> existing = List.of(
            new TownRedesign.Existing(onAvenue, box(14, -3, 20, 3), true),       // on the east-west avenue
            new TownRedesign.Existing(church, box(-3, 14, 3, 22), false),        // heritage on the north-south avenue
            new TownRedesign.Existing(playerHouse, box(-25, -3, -18, 3), false), // not ours to demolish
            new TownRedesign.Existing(aside, box(8, 8, 14, 14), true));          // between the streets
        final TownRedesign.Plan p = TownRedesign.plan(0, 0, 60, 1, existing, Set.of(), (x, z) -> true);
        assertTrue(p.roads().contains(RoadPlanner.key(40, 0)), "avenue east");
        assertTrue(p.roads().contains(RoadPlanner.key(0, -40)), "avenue north");
        assertTrue(p.roads().contains(RoadPlanner.key(32, 50)), "cross street every 32 blocks in the Middle Ages");
        assertEquals(List.of(onAvenue), p.demolish(), "only the demolishable building in the way");
        assertFalse(p.roads().contains(RoadPlanner.key(0, 18)), "the street goes around the church");
        assertFalse(p.roads().contains(RoadPlanner.key(-20, 0)), "and around the player's house");
        assertTrue(p.roads().contains(RoadPlanner.key(0, 0)), "the square");
        // modern epoch: denser grid, two-lane avenues
        final TownRedesign.Plan modern = TownRedesign.plan(0, 0, 60, 4, List.of(), Set.of(), (x, z) -> true);
        assertTrue(modern.roads().contains(RoadPlanner.key(40, 1)), "second lane");
        assertTrue(modern.roads().contains(RoadPlanner.key(20, 50)), "cross street every 20 blocks");
        // own land only
        final TownRedesign.Plan small = TownRedesign.plan(0, 0, 60, 1, List.of(), Set.of(), (x, z) -> Math.abs(x) < 16 && Math.abs(z) < 16);
        assertTrue(small.roads().stream().allMatch(k -> Math.abs(RoadPlanner.keyX(k)) < 16 && Math.abs(RoadPlanner.keyZ(k)) < 16));
    }

    @Test
    void theDifferenceKeepsExistingStreetsAndListsNewOnes() {
        final Set<Long> roads = new HashSet<>();
        for (int x = -60; x <= 60; x++) {
            roads.add(RoadPlanner.key(x, 0)); // the avenue exists already
        }
        final TownRedesign.Plan p = TownRedesign.plan(0, 0, 60, 1, List.of(), roads, (x, z) -> true);
        assertTrue(p.newRoads().stream().noneMatch(roads::contains), "existing streets are not rebuilt");
        assertTrue(p.newRoads().contains(RoadPlanner.key(0, 30)));
        // quarters: some green, the ones with buildings are not free
        final TownRedesign.Plan q = TownRedesign.plan(0, 0, 60, 1, List.of(new TownRedesign.Existing(UUID.randomUUID(),
            box(8, 8, 14, 14), true)), Set.of(), (x, z) -> true);
        assertTrue(q.quarters().stream().anyMatch(TownRedesign.Quarter::green), "green quarters");
        assertTrue(q.quarters().stream().anyMatch(x -> !x.free()), "a quarter with a building is not levelled");
    }

    @Test
    void levellingAQuarterLeavesNoWallsHigherThanTwo() {
        // a hill in the middle of a quarter
        final Map<Long, Integer> h = new HashMap<>();
        final java.util.function.IntBinaryOperator hill = (x, z) -> 64 + Math.max(0, 8 - (int) Math.hypot(x - 15, z - 15) / 2);
        final Terraform.Plan plan = Terraform.plan(hill, 5, 5, 25, 25, 64, new Terraform.Settings(0, 4, 2));
        for (final Terraform.Column c : plan.columns()) {
            h.put(RoadPlanner.key(c.x(), c.z()), c.wall() ? c.wallTop() : c.newTop());
        }
        for (final Terraform.Column c : plan.columns()) {
            for (final int[] d : new int[][] {{1, 0}, {0, 1}}) {
                final Integer n = h.get(RoadPlanner.key(c.x() + d[0], c.z() + d[1]));
                final int here = h.get(RoadPlanner.key(c.x(), c.z()));
                if (n != null && !c.wall()) {
                    assertTrue(Math.abs(n - here) <= 2, "step at " + c.x() + "," + c.z() + ": " + here + " → " + n);
                }
            }
        }
        assertTrue(plan.columns().stream().filter(Terraform.Column::plot).allMatch(c -> c.newTop() == 64), "the quarter is level");
    }
}
