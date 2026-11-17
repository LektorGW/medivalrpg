// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyRunTest {

    @Test
    void dayIsCommittedAndCheckpointedBeforeAnyMoneyMoves() {
        final List<String> log = new ArrayList<>();
        final LocalDate d1 = LocalDate.of(2026, 10, 28);
        final LocalDate d2 = LocalDate.of(2026, 10, 29);
        DailyRun.settle(List.of(d1, d2), d -> log.add("commit " + d), () -> log.add("save"), () -> log.add("pay"));
        assertEquals(List.of("commit 2026-10-28", "save", "pay", "commit 2026-10-29", "save", "pay"), log);
    }

    @Test
    void crashDuringPaymentsLeavesTheDayCommitted() {
        final LocalDate[] last = {LocalDate.of(2026, 10, 28)};
        final LocalDate day = LocalDate.of(2026, 10, 29);
        try {
            DailyRun.settle(List.of(day), d -> last[0] = d, () -> { }, () -> {
                throw new IllegalStateException("crash");
            });
        } catch (final IllegalStateException expected) {
            // the server died in the middle of the day
        }
        assertEquals(day, last[0], "a restart must not pay this day again");
    }

    @Test
    void oneFailingCountryDoesNotStopTheOthers() {
        final List<String> paid = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final int failed = DailyRun.eachIsolated(List.of("A", "B", "C"), c -> {
            if (c.equals("B")) {
                throw new IllegalArgumentException("broken state");
            }
            paid.add(c);
        }, (c, e) -> errors.add(c + ": " + e.getMessage()));
        assertEquals(1, failed);
        assertEquals(List.of("A", "C"), paid);
        assertEquals(List.of("B: broken state"), errors);
    }
}
