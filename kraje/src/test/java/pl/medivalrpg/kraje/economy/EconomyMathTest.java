// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EconomyMathTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);

    private static long at(final int day, final int hour, final int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void claimCostGrowsWithCountrySize() {
        assertEquals(50, Costs.claimCost(50, 5, 0));
        assertEquals(55, Costs.claimCost(50, 5, 1));
        assertEquals(50 + 5 * 24, Costs.claimCost(50, 5, 24));
        assertEquals(0, Costs.claimCost(0, 0, 100));
        assertEquals(50, Costs.claimCost(50, -3, 10), "negative config values count as 0");
    }

    @Test
    void upkeepPerChunkWithFreeChunks() {
        assertEquals(0, Costs.upkeep(2, 1, 1), "only the capital: free");
        assertEquals(2 * 23, Costs.upkeep(2, 1, 24));
        assertEquals(48, Costs.upkeep(2, 0, 24));
        assertEquals(0, Costs.upkeep(2, 30, 24));
    }

    @Test
    void taxPaymentTakesWhatIsAvailable() {
        assertEquals(10, Costs.taxPayment(10, 0, 100));
        assertEquals(25, Costs.taxPayment(10, 15, 100), "debt is collected together with today's tax");
        assertEquals(7, Costs.taxPayment(10, 15, 7));
        assertEquals(0, Costs.taxPayment(10, 0, 0));
    }

    @Test
    void moneyFallbackFormatAndParse() {
        assertEquals("0 md", Money.format(0));
        assertEquals("3 zł 4 sr 5 md", Money.format(345));
        assertEquals("5 zł", Money.format(500));
        assertEquals("2 sr", Money.format(20));
        assertEquals(Optional.of(150L), Money.parse("150"));
        assertEquals(Optional.of(300L), Money.parse("3zl"));
        assertEquals(Optional.of(150L), Money.parse("1zł 5sr"));
        assertEquals(Optional.of(23L), Money.parse("2 sr 3 md"));
        assertTrue(Money.parse("abc").isEmpty());
        assertTrue(Money.parse("0").isEmpty());
        assertTrue(Money.parse("-5").isEmpty());
    }

    @Test
    void latestScheduledTick() {
        assertEquals(at(24, 18, 0), DailySchedule.latestScheduled(at(25, 17, 59), SIX_PM, ZONE));
        assertEquals(at(25, 18, 0), DailySchedule.latestScheduled(at(25, 18, 0), SIX_PM, ZONE));
        assertEquals(at(25, 18, 0), DailySchedule.latestScheduled(at(25, 23, 30), SIX_PM, ZONE));
    }

    @Test
    void firstRunChargesNothing() {
        assertTrue(DailySchedule.dueTicks(0, at(25, 19, 0), SIX_PM, ZONE, 7).isEmpty());
    }

    @Test
    void oneTickPerDayAndNoDoubleProcessing() {
        final long last = at(24, 18, 0);
        assertTrue(DailySchedule.dueTicks(last, at(25, 17, 0), SIX_PM, ZONE, 7).isEmpty());
        assertEquals(List.of(at(25, 18, 0)), DailySchedule.dueTicks(last, at(25, 18, 1), SIX_PM, ZONE, 7));
        assertTrue(DailySchedule.dueTicks(at(25, 18, 0), at(25, 23, 0), SIX_PM, ZONE, 7).isEmpty());
    }

    @Test
    void catchUpAfterRestartIsCapped() {
        // server off from the 20th (tick done) until the 25th 19:00 → 21, 22, 23, 24, 25 missed
        final List<Long> all = DailySchedule.dueTicks(at(20, 18, 0), at(25, 19, 0), SIX_PM, ZONE, 7);
        assertEquals(List.of(at(21, 18, 0), at(22, 18, 0), at(23, 18, 0), at(24, 18, 0), at(25, 18, 0)), all);
        final List<Long> capped = DailySchedule.dueTicks(at(20, 18, 0), at(25, 19, 0), SIX_PM, ZONE, 2);
        assertEquals(List.of(at(24, 18, 0), at(25, 18, 0)), capped, "only the most recent ones, oldest first");
    }

    @Test
    void daylightSavingChange() {
        // 25.10.2026 03:00 ends summer time in Poland; 18:00 local stays 18:00 local
        final List<Long> due = DailySchedule.dueTicks(at(23, 18, 0), at(25, 18, 30), SIX_PM, ZONE, 7);
        assertEquals(List.of(at(24, 18, 0), at(25, 18, 0)), due);
        assertEquals(25 * 3_600_000L, due.get(1) - due.get(0), "the day of the change has 25 hours");
    }

    @Test
    void parseTime() {
        assertEquals(LocalTime.of(18, 0), DailySchedule.parseTime("18:00"));
        assertEquals(LocalTime.of(6, 30), DailySchedule.parseTime(" 06:30 "));
        assertEquals(LocalTime.of(18, 0), DailySchedule.parseTime("nonsense"));
    }
}
