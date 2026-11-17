// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MineProductionScenarioTest {
    @Test void staffedMedievalMineWithSmithyProducesAndSellsPartOfItsOutput() {
        final UUID mine = new UUID(0, 100);
        final var people = java.util.stream.IntStream.range(1, 5).mapToObj(n ->
            new JobBoard.Citizen(new UUID(0, n), "mason", true, n, 0, "world")).toList();
        final var yield = MineYield.defaults();
        final var board = JobBoard.assign(people,
            List.of(new JobBoard.Workplace(mine, JobBoard.Job.MINER, yield.slots(2), 0, 0, "world")),
            JobBoard.defaultProfessions());
        final var out = yield.daily(board.workersAt(mine), 2, yield.toolFactor(true, 1), 1);
        assertEquals(new MineYield.Output(39, 65, 2), out);
        final var stock = new HashMap<>(Map.of("ore", out.ore(), "stone", out.stone(), "precious", out.precious()));
        final var sold = Map.of("ore", yield.sold(out.ore()), "stone", yield.sold(out.stone()),
            "precious", yield.sold(out.precious()));
        final long proceeds = sold.get("ore") * 3 + sold.get("stone") + sold.get("precious") * 25;
        assertEquals(133, proceeds);
        assertTrue(ResourceSale.sell(stock, sold, proceeds, () -> true));
        assertEquals(Map.of("ore", 16L, "stone", 26L, "precious", 1L), stock);
    }

    @Test void childrenAndCitizensInAnotherWorldCannotGenerateMinerOutput() {
        final UUID mine = new UUID(0, 100);
        final var people = List.of(
            new JobBoard.Citizen(new UUID(0, 1), "mason", false, 0, 0, "world"),
            new JobBoard.Citizen(new UUID(0, 2), "mason", true, 0, 0, "world_nether"));
        final var board = JobBoard.assign(people,
            List.of(new JobBoard.Workplace(mine, JobBoard.Job.MINER, 4, 0, 0, "world")), JobBoard.defaultProfessions());
        assertEquals(MineYield.NONE, MineYield.defaults().daily(board.workersAt(mine), 2, 1.4, 1.25));
    }
}
