// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrailLogTest {

    @TempDir
    Path dir;

    @Test
    void eventsRoundTripThroughTheDailyLog() throws Exception {
        final TrailLog log = new TrailLog(this.dir, ZoneId.of("Europe/Warsaw"), 30, Logger.getLogger("test"));
        log.start();
        final long now = System.currentTimeMillis();
        log.append(new TrailLog.Event(now, "CONTAINER_IN", "6a0e1f2c-1111-2222-3333-444455556666", "Bolko", 500, 2, "world", 12, 64, -30,
            "Skrzynia\t(chest)", "w pojemniku\nteraz 500 md"));
        log.append(new TrailLog.Event(now + 1, "HOPPER", null, null, 30, 3, null, null, null, null, "Lejek → Skrzynia", ""));
        log.countDropped();
        log.close();
        final List<TrailLog.Event> read = new ArrayList<>();
        log.forEach(now - 1000, now + 1000, read::add);
        assertEquals(2, read.size());
        final TrailLog.Event e = read.get(0);
        assertEquals("CONTAINER_IN", e.kind());
        assertEquals("Bolko", e.player());
        assertEquals(500, e.amount());
        assertEquals(-30, e.z());
        assertEquals("Skrzynia (chest)", e.target()); // tabs and new lines never break the line format
        assertEquals("w pojemniku teraz 500 md", e.note());
        assertNull(read.get(1).player());
        assertNull(read.get(1).x());
        assertEquals("Lejek / przenoszenie automatyczne", read.get(1).toMap().get("kindLabel"));
        assertEquals(1, log.dropped());
    }

    @Test
    void malformedLinesAreIgnored() {
        assertNull(TrailLog.Event.decode("nonsense"));
        assertNull(TrailLog.Event.decode("x\tPICKUP\t-\t-\t1\t1\t-\t-\t-\t-\t\t"));
    }
}
