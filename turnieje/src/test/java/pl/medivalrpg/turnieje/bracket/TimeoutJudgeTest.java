// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimeoutJudgeTest {

    @Test
    void mostDamageWins() {
        final List<String> r = TimeoutJudge.rank(List.of(
            new TimeoutJudge.Score<>("a", 5.0, 1.0),
            new TimeoutJudge.Score<>("b", 12.0, 0.1),
            new TimeoutJudge.Score<>("c", 8.0, 0.5)), new Random(1));
        assertEquals(List.of("b", "c", "a"), r);
    }

    @Test
    void healthBreaksDamageTie() {
        final List<String> r = TimeoutJudge.rank(List.of(
            new TimeoutJudge.Score<>("a", 6.0, 0.4),
            new TimeoutJudge.Score<>("b", 6.0000001, 0.9)), new Random(1));
        assertEquals(List.of("b", "a"), r);
    }

    @Test
    void fullTieIsACoinToss() {
        final Set<String> winners = new HashSet<>();
        final Random random = new Random(12345);
        for (int trial = 0; trial < 100; trial++) {
            winners.add(TimeoutJudge.rank(List.of(
                new TimeoutJudge.Score<>("a", 3.0, 0.5),
                new TimeoutJudge.Score<>("b", 3.0, 0.5)), random).getFirst());
        }
        assertEquals(Set.of("a", "b"), winners);
    }

    @Test
    void emptyInput() {
        assertTrue(TimeoutJudge.rank(List.<TimeoutJudge.Score<String>>of(), new Random(1)).isEmpty());
    }
}
