// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobBoardTest {

    @Test
    void workersNeverFillSlotsInAnotherWorld() {
        final var citizens = List.of(
            new JobBoard.Citizen(id(1), "farmer", true, 0, 0, "world"),
            new JobBoard.Citizen(id(2), "farmer", true, 0, 0, "world_nether"));
        final var places = List.of(new JobBoard.Workplace(id(100), JobBoard.Job.FARMER, 2, 0, 0, "world"));
        final var result = JobBoard.assign(citizens, places, JobBoard.defaultProfessions());
        assertEquals(id(100), result.workplaceOf().get(id(1)));
        assertFalse(result.workplaceOf().containsKey(id(2)));
    }

    @Test
    void nearestWorkerWinsAcrossLargeCoordinatesWithoutIntegerOverflow() {
        final var citizens = List.of(c(1, "farmer", Integer.MIN_VALUE), c(2, "farmer", Integer.MAX_VALUE - 10));
        final var places = List.of(new JobBoard.Workplace(id(100), JobBoard.Job.FARMER, 1, Integer.MAX_VALUE, 0));
        final var result = JobBoard.assign(citizens, places, JobBoard.defaultProfessions());
        assertEquals(id(100), result.workplaceOf().get(id(2)));
    }

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    private static JobBoard.Citizen c(final int n, final String profession, final int x) {
        return new JobBoard.Citizen(id(n), "minecraft:" + profession, true, x, 0);
    }

    @Test
    void farmersGoToFieldsMasonsToTheMine() {
        final List<JobBoard.Citizen> people = List.of(c(1, "farmer", 0), c(2, "farmer", 5), c(3, "mason", 0), c(4, "librarian", 0));
        final UUID field = id(100);
        final UUID mine = id(101);
        final JobBoard.Assignment a = JobBoard.assign(people, List.of(
            new JobBoard.Workplace(field, JobBoard.Job.FARMER, 2, 0, 0),
            new JobBoard.Workplace(mine, JobBoard.Job.MINER, 2, 50, 0)), JobBoard.defaultProfessions());
        assertEquals(field, a.workplaceOf().get(id(1)));
        assertEquals(field, a.workplaceOf().get(id(2)));
        assertEquals(mine, a.workplaceOf().get(id(3)));
        assertNull(a.workplaceOf().get(id(4)), "a librarian does not dig");
        assertEquals(2, a.workers(JobBoard.Job.FARMER));
        assertEquals(1, a.workersAt(mine));
        assertEquals(JobBoard.Job.MINER, a.jobOf().get(id(3)));
    }

    @Test
    void slotsAreRespectedAndUnemployedFillTheMine() {
        final List<JobBoard.Citizen> people = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            people.add(c(i, "none", i));
        }
        people.add(c(10, "mason", 99));
        final UUID mine = id(200);
        final JobBoard.Assignment a = JobBoard.assign(people, List.of(new JobBoard.Workplace(mine, JobBoard.Job.MINER, 4, 0, 0)),
            JobBoard.defaultProfessions());
        assertEquals(4, a.workersAt(mine));
        assertEquals(mine, a.workplaceOf().get(id(10)), "the mason comes first even though he lives far away");
        assertEquals(mine, a.workplaceOf().get(id(0)), "then the nearest unemployed");
        assertEquals(mine, a.workplaceOf().get(id(1)));
        assertEquals(mine, a.workplaceOf().get(id(2)));
        assertNull(a.workplaceOf().get(id(5)));
    }

    @Test
    void workersSpreadOverWorkplaces() {
        final List<JobBoard.Citizen> people = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            people.add(c(i, "farmer", 0));
        }
        final UUID near = id(300);
        final UUID far = id(301);
        final JobBoard.Assignment a = JobBoard.assign(people, List.of(
            new JobBoard.Workplace(near, JobBoard.Job.FARMER, 3, 0, 0),
            new JobBoard.Workplace(far, JobBoard.Job.FARMER, 3, 200, 0)), JobBoard.defaultProfessions());
        assertEquals(2, a.workersAt(near), "fields take turns: 2 + 2, not 3 + 1");
        assertEquals(2, a.workersAt(far));
    }

    @Test
    void childrenNeverWork() {
        final List<JobBoard.Citizen> people = List.of(new JobBoard.Citizen(id(1), "minecraft:farmer", false, 0, 0));
        final JobBoard.Assignment a = JobBoard.assign(people, List.of(new JobBoard.Workplace(id(2), JobBoard.Job.FARMER, 2, 0, 0)),
            JobBoard.defaultProfessions());
        assertTrue(a.workplaceOf().isEmpty());
    }

    @Test
    void masonsShareMinesAndQuarries() {
        final List<JobBoard.Citizen> people = List.of(c(1, "mason", 0), c(2, "mason", 0), c(3, "fletcher", 0), c(4, "fisherman", 0));
        final UUID mine = id(10);
        final UUID quarry = id(11);
        final UUID mill = id(12);
        final UUID pier = id(13);
        final JobBoard.Assignment a = JobBoard.assign(people, List.of(
            new JobBoard.Workplace(mine, JobBoard.Job.MINER, 2, 0, 0),
            new JobBoard.Workplace(quarry, JobBoard.Job.QUARRYMAN, 2, 0, 0),
            new JobBoard.Workplace(mill, JobBoard.Job.WOODCUTTER, 2, 0, 0),
            new JobBoard.Workplace(pier, JobBoard.Job.FISHER, 2, 0, 0)), JobBoard.defaultProfessions());
        assertEquals(1, a.workersAt(mine));
        assertEquals(1, a.workersAt(quarry));
        assertEquals(1, a.workersAt(mill));
        assertEquals(1, a.workersAt(pier));
    }

    @Test
    void deterministicAndEmptyInputs() {
        final List<JobBoard.Citizen> people = List.of(c(1, "farmer", 3), c(2, "farmer", 3));
        final List<JobBoard.Workplace> places = List.of(new JobBoard.Workplace(id(9), JobBoard.Job.FARMER, 1, 0, 0));
        assertEquals(JobBoard.assign(people, places, JobBoard.defaultProfessions()).workplaceOf(),
            JobBoard.assign(List.of(people.get(1), people.get(0)), places, JobBoard.defaultProfessions()).workplaceOf());
        assertEquals(JobBoard.Assignment.EMPTY, JobBoard.assign(List.of(), places, JobBoard.defaultProfessions()));
        assertEquals(JobBoard.Assignment.EMPTY, JobBoard.assign(people, List.of(), JobBoard.defaultProfessions()));
        assertFalse(JobBoard.defaultProfessions().get(JobBoard.Job.MINER).isEmpty());
    }
}
