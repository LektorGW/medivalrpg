// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** plugins/Panel/history/<id>.json: one sample per hour, 30 days kept, last N days read back. */
class HistoryLogTest {

    private static final long H = HistoryLog.HOUR;
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static HistoryLog.Point p(final long t, final long treasury) {
        return new HistoryLog.Point(t, treasury, 10, 5, 40, null);
    }

    @Test
    void oneSamplePerHourSortedAndAligned() {
        final HistoryLog log = new HistoryLog(ID, "Mazowsze");
        final long now = 1000 * H + 1234;
        log.add(p(now, 1), now, 30 * HistoryLog.DAY, null);
        log.add(p(now + 60_000, 2), now, 30 * HistoryLog.DAY, null); // same hour → replaced
        log.add(p(now - 2 * H, 3), now, 30 * HistoryLog.DAY, null);  // older → inserted before
        final List<HistoryLog.Point> all = log.since(0, 1000);
        assertEquals(2, all.size());
        assertEquals(998 * H, all.get(0).t());
        assertEquals(1000 * H, all.get(1).t());
        assertEquals(2L, all.get(1).treasury());
    }

    @Test
    void keepsThirtyDaysAndReturnsTheLastDays() {
        final HistoryLog log = new HistoryLog(ID, "X");
        final long keep = 30 * HistoryLog.DAY;
        final long now = 2000 * H;
        for (long t = now - 40 * 24 * H; t <= now; t += H) {
            log.add(p(t, t / H), t, keep, null);
        }
        assertEquals(30 * 24 + 1, log.size());
        final List<HistoryLog.Point> week = log.since(now - 7 * HistoryLog.DAY, 7 * 24 + 1);
        assertEquals(7 * 24 + 1, week.size());
        assertEquals(now, week.get(week.size() - 1).t());
        assertEquals(5, log.since(0, 5).size(), "max keeps the newest");
        assertEquals(now, log.since(0, 5).get(4).t());
    }

    @Test
    void jsonRoundTripKeepsNulls() {
        final HistoryLog log = new HistoryLog(ID, "Ruś");
        log.add(new HistoryLog.Point(5 * H, 100L, null, 3, 7, null), 5 * H, 30 * HistoryLog.DAY, null);
        final String json = log.toJson();
        assertTrue(json.contains("\"name\":\"Ruś\""), json);
        final HistoryLog back = HistoryLog.fromJson(ID, json);
        final HistoryLog.Point pt = back.since(0, 10).get(0);
        assertEquals(100L, pt.treasury());
        assertNull(pt.population());
        assertNull(pt.food());
        assertEquals(3, pt.soldiers());
    }

    @Test
    void brokenFileGivesEmptyLog() {
        assertEquals(0, HistoryLog.fromJson(ID, "{nie json").size());
        assertEquals(0, HistoryLog.fromJson(ID, "").size());
    }
}
