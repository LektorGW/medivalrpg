// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.LudnoscPlugin;

/** Companies (Firmy manufactories) hire only unemployed adult citizens of the seat country, the nearest loaded one. */
class WorkerHireTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    private static WorkerHire.Candidate idle(final int n, final double d) {
        return new WorkerHire.Candidate(id(n), true, true, d, false, false, false);
    }

    @Test
    void neverTakesACitizenWithWorkEvenWhenNearer() {
        final List<WorkerHire.Candidate> c = List.of(
            new WorkerHire.Candidate(id(1), true, true, 1, false, true, false), // employed, right next to the workshop
            idle(2, 400));
        assertEquals(Optional.of(id(2)), WorkerHire.pick(c));
    }

    @Test
    void emptyWhenEverybodyWorksOrIsAChild() {
        final List<WorkerHire.Candidate> c = List.of(
            new WorkerHire.Candidate(id(1), true, true, 1, false, true, false),
            new WorkerHire.Candidate(id(2), false, true, 4, false, false, false), // child
            new WorkerHire.Candidate(id(3), true, true, 9, false, false, true)); // trading with a player
        assertTrue(WorkerHire.pick(c).isEmpty());
        assertTrue(WorkerHire.pick(List.of()).isEmpty());
    }

    @Test
    void nearestUnemployedWinsTradedAndOtherWorldLast() {
        final List<WorkerHire.Candidate> c = List.of(
            new WorkerHire.Candidate(id(1), true, true, 4, true, false, false), // traded with players: last
            new WorkerHire.Candidate(id(2), true, false, 0, false, false, false), // another world
            idle(3, 900),
            idle(4, 100));
        assertEquals(Optional.of(id(4)), WorkerHire.pick(c));
        assertEquals(Optional.of(id(2)), WorkerHire.pick(c.subList(0, 2)));
        assertEquals(Optional.of(id(1)), WorkerHire.pick(c.subList(0, 1)));
    }

    @Test
    void deterministicOnTies() {
        final List<WorkerHire.Candidate> a = new ArrayList<>(List.of(idle(7, 25), idle(5, 25), idle(9, 25)));
        assertEquals(Optional.of(id(5)), WorkerHire.pick(a));
        java.util.Collections.reverse(a);
        assertEquals(Optional.of(id(5)), WorkerHire.pick(a));
    }

    @Test
    void randomPopulationsNeverYieldAnEmployedChildOrBusyCitizen() {
        final Random r = new Random(29102026L);
        for (int round = 0; round < 2000; round++) {
            final List<WorkerHire.Candidate> c = new ArrayList<>();
            final int n = r.nextInt(12);
            for (int i = 0; i < n; i++) {
                c.add(new WorkerHire.Candidate(id(round * 100 + i), r.nextInt(5) > 0, r.nextInt(6) > 0, r.nextInt(10_000),
                    r.nextInt(8) == 0, r.nextBoolean(), r.nextInt(10) == 0));
            }
            final Optional<UUID> pick = WorkerHire.pick(c);
            final boolean anyEligible = c.stream().anyMatch(x -> x.adult() && !x.employed() && !x.busy());
            assertEquals(anyEligible, pick.isPresent());
            pick.ifPresent(p -> {
                final WorkerHire.Candidate chosen = c.stream().filter(x -> x.id().equals(p)).findFirst().orElseThrow();
                assertTrue(chosen.adult() && !chosen.employed() && !chosen.busy());
            });
        }
    }

    @Test
    void savingsStayWithTheCitizensExactly() {
        assertEquals(Map.of(id(1), 101L), WorkerHire.shareOut(101, List.of(id(1)))); // partner
        final Map<UUID, Long> kids = WorkerHire.shareOut(100, List.of(id(1), id(2), id(3)));
        assertEquals(100L, kids.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(34L, kids.get(id(1)));
        assertEquals(33L, kids.get(id(3)));
        assertTrue(WorkerHire.shareOut(500, List.of()).isEmpty()); // no heirs: stays in the account for everybody
        assertTrue(WorkerHire.shareOut(0, List.of(id(1))).isEmpty());
        final Random r = new Random(7);
        for (int i = 0; i < 1000; i++) {
            final long s = r.nextInt(1_000_000);
            final List<UUID> heirs = new ArrayList<>();
            for (int h = 0, n = 1 + r.nextInt(6); h < n; h++) {
                heirs.add(id(h));
            }
            assertEquals(s, WorkerHire.shareOut(s, heirs).values().stream().mapToLong(Long::longValue).sum());
        }
    }

    /** Firmy calls {@code ludnosc.getClass().getMethod("hireWorker", UUID.class, Location.class)} and expects an Optional. */
    @Test
    void reflectionContractForFirmy() throws NoSuchMethodException {
        final Method m = LudnoscPlugin.class.getMethod("hireWorker", UUID.class, Location.class);
        assertTrue(Modifier.isPublic(m.getModifiers()));
        assertEquals(Optional.class, m.getReturnType());
        assertTrue(m.getGenericReturnType().getTypeName().contains("java.lang.String"));
    }
}
