// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.log.LogParser;
import pl.medivalrpg.straz.log.LogTailer;

class LogTest {

    static final ZoneId WAW = ZoneId.of("Europe/Warsaw");
    static final long NOW = ZonedDateTime.of(2026, 10, 29, 9, 0, 0, 0, WAW).toInstant().toEpochMilli();
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;

    static List<LogParser.Event> parse(final String... lines) {
        final LogParser p = new LogParser(WAW);
        final List<LogParser.Event> out = new ArrayList<>(p.feed(List.of(lines), NOW));
        out.addAll(p.flush());
        return out;
    }

    @Test
    void parsesPaperErrorLinesWithPluginTag() {
        final var ev = parse(
            "[03:10:09] [Server thread/INFO]: [Kolej] Walidacja świata mr-preclear-01",
            "[03:10:09] [Server thread/ERROR]: [Kolej] Budowa wstrzymana: Zabudowa lub niezatwierdzony materiał w promieniu 8 m przy 3647:78:778",
            "[03:10:10] [Server thread/WARN]: [Armia] messages.list-footer: placeholder");
        assertEquals(1, ev.size(), "INFO and WARN are not errors");
        assertEquals("Kolej", ev.get(0).plugin());
        assertEquals("ERROR", ev.get(0).level());
        assertTrue(ev.get(0).message().startsWith("Budowa wstrzymana"));
        assertEquals(ZonedDateTime.of(2026, 10, 29, 3, 10, 9, 0, WAW).toInstant().toEpochMilli(), ev.get(0).time());
    }

    @Test
    void stackTraceGivesDetailAndPluginFromFrames() {
        final var ev = parse(
            "[12:00:00] [Server thread/ERROR]: Could not pass event PlayerJoinEvent to Kraje v1.0.0",
            "java.lang.IllegalStateException: zły stan 42",
            "\tat pl.medivalrpg.kraje.KrajePlugin.onJoin(KrajePlugin.java:10)",
            "[12:00:01] [Server thread/ERROR]: Unexpected exception",
            "java.lang.NullPointerException: null",
            "\tat ludnosc.jar//pl.medivalrpg.ludnosc.service.CitizenService.tick(CitizenService.java:5)",
            "[12:00:02] [Server thread/INFO]: ok");
        assertEquals(2, ev.size());
        assertEquals("Kraje", ev.get(0).plugin());
        assertEquals("java.lang.IllegalStateException: zły stan 42", ev.get(0).detail());
        assertEquals("Ludnosc", ev.get(1).plugin(), "plugin from the first pl.medivalrpg frame");
        assertEquals("java.lang.NullPointerException: null", ev.get(1).detail());
    }

    @Test
    void watchdogDumpIsOneEventWithPluginFrame() {
        final var ev = parse(
            "[05:15:36] [Paper Watchdog Thread/ERROR]: --- DO NOT REPORT THIS TO PAPER - THIS IS NOT A BUG OR A CRASH  - 26.3-218 ---",
            "[05:15:36] [Paper Watchdog Thread/ERROR]: The server has not responded for 10 seconds! Creating thread dump",
            "[05:15:36] [Paper Watchdog Thread/ERROR]: Current Thread: Server thread",
            "[05:15:36] [Paper Watchdog Thread/ERROR]: \t\tapp//org.bukkit.craftbukkit.CraftWorld.addPluginChunkTicket(CraftWorld.java:586)",
            "[05:15:36] [Paper Watchdog Thread/ERROR]: \t\tKolej-1.0.0.jar//pl.medivalrpg.kolej.NetworkCommission.tick(NetworkCommission.java:200)",
            "[05:15:36] [Paper Watchdog Thread/ERROR]: ------------------------------",
            "[05:15:37] [Server thread/ERROR]: [Kolej] Budowa wstrzymana: x");
        assertEquals(2, ev.size(), "a 6-line dump is one event");
        assertEquals(LogParser.WATCHDOG, ev.get(0).plugin());
        assertEquals("Serwer nie odpowiadał przez 10 s — zrzut wątków Paper Watchdog", ev.get(0).message());
        assertEquals("Wątek główny w kodzie pluginu: Kolej", ev.get(0).detail());
        assertEquals("Kolej", ev.get(1).plugin());
    }

    @Test
    void consoleFormatAlsoParsed() {
        final var ev = parse("[05:05:11 INFO]: [Kolej] ok", "[05:05:12 ERROR]: [Skarbiec] Zapis nieudany");
        assertEquals(1, ev.size());
        assertEquals("Skarbiec", ev.get(0).plugin());
        assertEquals("Zapis nieudany", ev.get(0).message());
    }

    @Test
    void untaggedErrorIsServer() {
        final var ev = parse("[08:00:00] [Netty Epoll Server IO #1/ERROR]: Something broke");
        assertEquals(LogParser.SERVER, ev.get(0).plugin());
    }

    @Test
    void lineFromYesterdayEvening() {
        final var ev = parse("[23:59:00] [Server thread/ERROR]: [Skarbiec] x");
        assertEquals(NOW - 9 * HOUR - 60_000, ev.get(0).time());
    }

    @Test
    void normalizeMakesSameErrorSameSignature() {
        assertEquals(LogParser.normalize("Zabudowa przy 3647:78:778 (12.5 s)"), LogParser.normalize("Zabudowa przy -10:64:5 (3,1 s)"));
        assertEquals("konto ‹uuid› brak", LogParser.normalize("konto ff923cf7-f43c-37b8-85a3-77cbac357c9c   brak"));
    }

    @Test
    void errorBookDeduplicatesAndGroupsByPlugin() {
        final ErrorBook book = new ErrorBook();
        int newOnes = 0;
        for (int i = 0; i < 5; i++) {
            if (book.record(new LogParser.Event(NOW - 6 * HOUR, "ERROR", "Kolej", "Budowa wstrzymana przy 3647:78:" + (770 + i), null), 7 * DAY)) {
                newOnes++;
            }
        }
        assertEquals(1, newOnes, "five occurrences, one new signature");
        assertTrue(book.record(new LogParser.Event(NOW - HOUR, "ERROR", "Skarbiec", "Zapis nieudany", "java.io.IOException: disk"), 7 * DAY));
        book.record(new LogParser.Event(NOW - 2 * DAY, "ERROR", "Armia", "stary błąd", null), 7 * DAY);
        final var groups = book.summary(NOW, DAY, 7 * DAY);
        assertEquals(2, groups.size(), "the 2-day-old error is outside 24 h");
        assertEquals("Kolej", groups.get(0).plugin());
        assertEquals(5, groups.get(0).count());
        assertEquals(1, groups.get(0).lines().size());
        assertTrue(groups.get(0).lines().get(0).newInWindow());
        assertEquals("Skarbiec", groups.get(1).plugin());
        assertEquals("java.io.IOException: disk", groups.get(1).lines().get(0).detail());
    }

    @Test
    void errorSeenAgainAfterWindowIsNewAgain() {
        final ErrorBook book = new ErrorBook();
        assertTrue(book.record(new LogParser.Event(NOW - 10 * DAY, "ERROR", "Kolej", "x", null), 7 * DAY));
        assertFalse(book.record(new LogParser.Event(NOW - 9 * DAY, "ERROR", "Kolej", "x", null), 7 * DAY));
        assertTrue(book.record(new LogParser.Event(NOW, "ERROR", "Kolej", "x", null), 7 * DAY), "unseen for 9 days");
        book.prune(NOW, 3 * DAY, 30 * DAY);
        assertEquals(1, book.sigs.size());
        assertEquals(1, book.sigs.values().iterator().next().hours.size(), "old hour buckets pruned");
    }

    @Test
    void tailerFollowsAppendsAndRestarts(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("latest.log");
        Files.writeString(log, "[09:00:00] [Server thread/ERROR]: [Kolej] a\n[09:00:01] [Server thread/INFO]: x\n[09:00:02] [Ser", StandardCharsets.UTF_8);
        final LogTailer.Position pos = new LogTailer.Position();
        final LogTailer t = new LogTailer(log, WAW, pos);
        final var first = t.poll(NOW);
        assertEquals(1, first.size());
        assertEquals("a", first.get(0).message());
        Files.writeString(log, "ver thread/ERROR]: [Skarbiec] b\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        final var second = t.poll(NOW);
        assertEquals(1, second.size(), "the half line is completed on the next poll");
        assertEquals("Skarbiec", second.get(0).plugin());
        assertTrue(t.poll(NOW).isEmpty(), "nothing new");
        // restart: Paper writes a new, shorter latest.log
        Files.delete(log);
        Files.writeString(log, "[10:00:00] [Server thread/ERROR]: [Armia] c\n", StandardCharsets.UTF_8);
        final var third = t.poll(NOW + HOUR);
        assertEquals(1, third.size());
        assertEquals("Armia", third.get(0).plugin());
        assertNull(third.get(0).detail());
    }
}
