// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.straz.alert.Alert;
import pl.medivalrpg.straz.alert.AlertGate;
import pl.medivalrpg.straz.backup.BackupPlan;
import pl.medivalrpg.straz.economy.Flows;
import pl.medivalrpg.straz.economy.Pool;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.log.ErrorBook;
import pl.medivalrpg.straz.log.LogParser;
import pl.medivalrpg.straz.metrics.LowTpsDetector;
import pl.medivalrpg.straz.metrics.Metrics;
import pl.medivalrpg.straz.people.Census;
import pl.medivalrpg.straz.report.DiscordPayload;
import pl.medivalrpg.straz.report.ReportData;
import pl.medivalrpg.straz.report.ReportFormatter;
import pl.medivalrpg.straz.state.StateStore;
import pl.medivalrpg.straz.state.StrazState;

class AlertReportTest {

    static final ZoneId WAW = ZoneId.of("Europe/Warsaw");
    static final ZonedDateTime AT = ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, WAW);
    static final long NOW = AT.toInstant().toEpochMilli();
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;

    @Test
    void gateDeduplicatesAndLimitsPerHour() {
        final AlertGate g = new AlertGate();
        assertTrue(g.offer("pool|1", NOW, DAY, 3).send());
        assertFalse(g.offer("pool|1", NOW + HOUR, DAY, 3).send(), "same key within cooldown");
        assertTrue(g.offer("pool|2", NOW + HOUR, DAY, 3).send(), "another amount alerts");
        assertTrue(g.offer("a", NOW + HOUR, 0, 3).send());
        assertTrue(g.offer("b", NOW + HOUR, 0, 3).send());
        final AlertGate.Decision limited = g.offer("c", NOW + HOUR, 0, 3);
        assertFalse(limited.send());
        assertEquals("limit na godzinę", limited.why());
        final AlertGate.Decision later = g.offer("d", NOW + 3 * HOUR, 0, 3);
        assertTrue(later.send());
        assertEquals(1, later.suppressedBefore(), "reports how many were dropped");
        g.clear("pool|1");
        assertTrue(g.offer("pool|1", NOW + 3 * HOUR, DAY, 3).send(), "cleared condition alerts again");
    }

    @Test
    void severeBurstListsOurPluginsFirst() {
        final List<ErrorBook.Line> burst = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            burst.add(new ErrorBook.Line("PaperVersionFetcher", "Error while parsing version " + (char) ('a' + i / 26) + (char) ('a' + i % 26), null, 1, NOW, NOW - 60_000, true));
        }
        burst.add(new ErrorBook.Line(LogParser.WATCHDOG, "Serwer nie odpowiadał przez 10 s", "Wątek główny w kodzie pluginu: Kolej", 1, NOW, NOW - 30_000, true));
        burst.add(new ErrorBook.Line("Kolej", "Budowa wstrzymana: TEST", null, 1, NOW, NOW, true));
        burst.add(new ErrorBook.Line("Kolej", "Budowa wstrzymana: TEST", null, 1, NOW, NOW, true)); // duplicate
        final Alert a = pl.medivalrpg.straz.alert.SevereBatch.build(burst, NOW);
        assertEquals("nowe błędy SEVERE/ERROR (32)", a.title());
        assertTrue(a.lines().get(0).startsWith("Nowe rodzaje według pluginów: Kolej 1 · Serwer (watchdog) 1 · PaperVersionFetcher 30"), a.lines().get(0));
        assertTrue(a.lines().get(1).contains("[Kolej]") && a.lines().get(1).contains("TEST"));
        assertTrue(a.lines().get(2).contains("watchdog") && a.lines().get(2).contains("pluginu: Kolej"));
        assertEquals("… i 26 kolejnych (pełna lista w raporcie).", a.lines().get(a.lines().size() - 1));
        final Alert one = pl.medivalrpg.straz.alert.SevereBatch.build(burst.subList(31, 32), NOW);
        assertEquals("nowy błąd SEVERE/ERROR", one.title());
        assertEquals(1, one.lines().size(), "no per-plugin line for a single kind");
    }

    @Test
    void lowTpsAlertsOncePerStreak() {
        final LowTpsDetector d = new LowTpsDetector(15.0, 3);
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(19.9));
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(12));
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(14.9));
        assertEquals(LowTpsDetector.Signal.ALERT, d.sample(10), "third minute below 15");
        assertEquals(10.0, d.streakMin(), 1e-9);
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(9), "no repeat within the streak");
        assertEquals(LowTpsDetector.Signal.RECOVERED, d.sample(19));
        assertEquals(4, d.lastStreak());
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(14), "short dip");
        assertEquals(LowTpsDetector.Signal.NONE, d.sample(18), "a dip shorter than N minutes never alerts");
    }

    @Test
    void metricsWindowAggregates() {
        final Metrics m = new Metrics();
        m.tps(NOW - 30 * HOUR, 5, true);           // outside 24 h
        m.tps(NOW - 2 * HOUR, 20, false);
        m.tps(NOW - 2 * HOUR + 60_000, 18, false);
        m.tps(NOW - HOUR, 14, true);
        m.lagSpike(NOW - HOUR, 1240);
        m.lagSpike(NOW - HOUR, 600);
        m.players(NOW - 5 * HOUR, 3);
        m.players(NOW - 4 * HOUR, 7);
        m.players(NOW - 3 * HOUR, 2);
        m.citizenKilled(NOW - HOUR);
        final Metrics.Window w = m.window(NOW, DAY);
        assertEquals(3, w.samples());
        assertEquals(52.0 / 3, w.tpsAvg(), 1e-9);
        assertEquals(14, w.tpsMin(), 1e-9);
        assertEquals(2, w.lagSpikes());
        assertEquals(1240, w.worstTickMs(), 1e-9);
        assertEquals(7, w.peakPlayers());
        assertEquals(NOW - 4 * HOUR, w.peakAt());
        assertEquals(1, w.citizensKilled());
        assertEquals(1, w.lowTpsMinutes());
        m.prune(NOW, 2 * DAY);
        assertEquals(6, m.hours.size(), "30-h old hour kept with 2-day retention");
        m.prune(NOW, DAY);
        assertEquals(5, m.hours.size());
    }

    // ------------------------------------------------------------------ report

    static ReportData sample(final boolean problems) {
        final long reserve = DataTest.RESERVE + (problems ? 500 : 0);
        final Pool.Snapshot pool = Pool.parse(DataTest.accounts(reserve), 100_000_000L);
        final long from = NOW - DAY;
        final List<String> tx = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final String t = java.time.Instant.ofEpochMilli(from + i * HOUR + 1000).toString();
            tx.add((1000 + i) + "\t" + t + "\tTRANSFER\tworld:households\tcountry:a\t" + (problems ? 25_000 : 900) + "\t1\t1\tLudnosc: podatek");
            tx.add((2000 + i) + "\t" + t + "\tTRANSFER\tworld:payments\tworld:households\t300\t1\t1\tRobocizna");
            tx.add((3000 + i) + "\t" + t + "\tTRANSFER\tworld:merchant\tplayer:x\t1200\t1\t1\tKupiec: skup");
            tx.add((4000 + i) + "\t" + t + "\tTRANSFER\tplayer:x\tworld:merchant\t1000\t1\t1\tKupiec: sprzedaż");
        }
        tx.add("5000\t" + java.time.Instant.ofEpochMilli(NOW - HOUR).toString() + "\tTRANSFER\tworld:reserve\tworld:households\t312600\t1\t1\tzasilenie [grant:households]");
        final List<ReportData.AccountFlow> flows = new ArrayList<>();
        for (final String[] a : new String[][] {{"world:households", "Gospodarstwa NPC (world:households)"}, {"world:merchant", "Kupiec (world:merchant)"}}) {
            final Flows.Flow f = Flows.of(a[0], tx, from, NOW);
            final double avg = a[0].equals("world:merchant") ? -4500.0 : Double.NaN;
            flows.add(new ReportData.AccountFlow(a[0], a[1], pool.balance(a[0]), f, avg, Double.isNaN(avg) ? 0 : 5,
                Flows.daysLeft(pool.balance(a[0]), Double.isNaN(avg) ? f.net() : avg)));
        }
        final ErrorBook book = new ErrorBook();
        if (problems) {
            for (int i = 0; i < 5; i++) {
                book.record(new LogParser.Event(NOW - 6 * HOUR, "ERROR", "Kolej",
                    "Budowa wstrzymana: Zabudowa lub niezatwierdzony materiał w promieniu 8 m przy 3647:78:" + (778 + i), null), 7 * DAY);
            }
            book.record(new LogParser.Event(NOW - 2 * HOUR, "ERROR", "Skarbiec", "Nie zapisano bank/accounts.json @everyone",
                "java.io.IOException: No space left on device"), 7 * DAY);
            for (int i = 0; i < 12; i++) {
                book.record(new LogParser.Event(NOW - 3 * HOUR, "ERROR", "Ludnosc", "Błąd rodzaju " + (char) ('A' + i) + " w budowie", null), 7 * DAY);
            }
        }
        final List<String> parts = new ArrayList<>();
        for (int i = 1; i <= 63; i++) {
            parts.add("mr-part-" + i);
        }
        final Map<String, RailSummary.Job> jobs = new java.util.HashMap<>();
        for (int i = 1; i <= 10; i++) {
            jobs.put("mr-part-" + i, new RailSummary.Job("mr-part-" + i, "DONE", 1000, 1000, 800, ""));
        }
        jobs.put("mr-part-11", new RailSummary.Job("mr-part-11", problems ? "BLOCKED" : "QUEUED", 400, 1000, 800,
            problems ? "Zabudowa przy 3647:78:778" : ""));
        final RailSummary.Summary rail = RailSummary.of(parts, 818_789, jobs, 818_789 - 8_800,
            problems ? "Zatrzymane: mr-part-11 · Zabudowa przy 3647:78:778" : "Budowa mr-part-11 400/1000");
        final Metrics m = new Metrics();
        for (int i = 0; i < 24 * 60; i++) {
            final double tps = problems && i > 600 && i < 620 ? 11.5 : 19.9;
            m.tps(from + i * 60_000L, tps, tps < 15);
        }
        m.players(from + 13 * HOUR, 7);
        m.lagSpike(from + 10 * HOUR, 1240);
        final List<BackupPlan.Entry> files = new ArrayList<>();
        for (int d = 0; d < 14; d++) {
            final var day = AT.toLocalDate().minusDays(d);
            files.add(new BackupPlan.Entry(BackupPlan.nightlyName(day), day.atStartOfDay(), problems && d == 0 ? 2_000_000 : 8_400_000));
        }
        final BackupPlan.Health health = BackupPlan.health(files, AT.toLocalDateTime(), LocalTime.of(4, 30), 26, 1_000_000);
        final ReportData.LastRun last = new ReportData.LastRun(AT.withHour(4).withMinute(30).toInstant().toEpochMilli(), !problems,
            "plugins-20261030.zip", 8_400_000, 1342, 2_300, 0, problems ? "za mało miejsca na dysku: wolne 50 MB, wymagane 125 MB" : "");
        final Census.Data cd = new Census.Data(Set.of("a", "b", "c"), List.of(NOW - HOUR, NOW - 2 * HOUR),
            List.of(new Census.Building("dom", NOW - HOUR, false), new Census.Building("studnia", NOW - 3 * HOUR, false)), 7);
        final Census.Stats st = Census.stats(cd, Set.of("a", "b", "x"), from, NOW);
        final ReportData.Army army = new ReportData.Army(true, problems ? 1012 : 233, 1000,
            List.of(new ReportData.Top("Królestwo Polskie", 60), new ReportData.Top("Księstwo Mazowieckie", 41), new ReportData.Top("Ruś", 30)), "");
        return new ReportData(AT, "Paper 26.3", pool, "", flows, "", book.summary(NOW, DAY, 7 * DAY),
            book.summary(NOW, DAY, 7 * DAY).stream().mapToLong(ErrorBook.Group::count).sum(), rail, "",
            m.window(NOW, DAY), 3, 19.98, 15.0, 500,
            new ReportData.Backup(health, files.size(), last, true, ""), new ReportData.People(958, st, 1, ""), army);
    }

    @Test
    void healthyReport() throws Exception {
        final ReportFormatter.Report r = ReportFormatter.format(sample(false));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(8, r.sections().size());
        final String md = ReportFormatter.markdown(r);
        assertTrue(md.startsWith("# 📋 Raport dzienny MEDIVALRPG — piątek 30.10.2026, 09:00"), md);
        assertTrue(md.contains("✅ Suma: **100 000 000 md** = oczekiwana pula"));
        assertTrue(md.contains("✅ Brak błędów."));
        assertTrue(md.contains("Żołnierze: **233 / 1 000**"));
        assertTrue(md.contains("Szczyt graczy: **7**"));
        assertTrue(md.contains("plugins-20261030.zip"));
        assertTrue(md.contains("Maciej Kaczmarczyk — lektoro.pl"));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/sample-report-ok.md"), md);
    }

    @Test
    void reportWithProblemsListsIssues() throws Exception {
        final ReportFormatter.Report r = ReportFormatter.format(sample(true));
        assertFalse(r.ok());
        final String issues = String.join(" | ", r.issues());
        assertTrue(issues.contains("rozbieżność puli +500 md"), issues);
        assertTrue(issues.contains("budowa kolei zatrzymana"), issues);
        assertTrue(issues.contains("TPS poniżej 15,0 przez 19 min"), issues);
        assertTrue(issues.contains("nieudana kopia"), issues);
        assertTrue(issues.contains("wojsko ponad limit 1012/1000"), issues);
        assertTrue(issues.contains("world:households na"), issues);
        final String md = ReportFormatter.markdown(r);
        assertTrue(md.contains("**Kolej** — 5× (1 rodzaj)"), md);
        assertTrue(md.contains("**Ludnosc** — 12× (12 rodzajów)"), md);
        assertTrue(md.contains("… i 9 innych rodzajów"), "long error lists are cut with a counter");
        assertTrue(md.contains("Nie zapisano bank/accounts.json"), "other plugins stay visible");
        assertTrue(md.contains("## 📄 Wszystkie błędy ERROR/SEVERE (24 h)") && md.contains("Błąd rodzaju L"), "full list in the file");
        assertTrue(md.contains("↳ java.io.IOException: No space left on device"));
        assertFalse(DiscordPayload.report(r, "x").toString().contains("rodzaju L"), "Discord gets the short list only");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/sample-report-problems.md"), md);
        Files.writeString(Path.of("build/sample-report-problems.embed.json"), DiscordPayload.report(r, "Straż MEDIVALRPG").toString());
    }

    @Test
    void discordPayloadRespectsLimitsAndNeverPings() {
        final ReportFormatter.Report r = ReportFormatter.format(sample(true));
        final JsonObject body = DiscordPayload.report(r, "Straż MEDIVALRPG");
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        final String raw = body.toString();
        assertFalse(raw.contains("@everyone"), "mass mention neutralized");
        assertTrue(raw.contains("＠everyone"));
        final JsonObject embed = body.getAsJsonArray("embeds").get(0).getAsJsonObject();
        int total = embed.get("title").getAsString().length() + embed.get("description").getAsString().length()
            + embed.getAsJsonObject("footer").get("text").getAsString().length();
        final JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(8, fields.size());
        for (final JsonElement f : fields) {
            final String v = f.getAsJsonObject().get("value").getAsString();
            assertTrue(v.length() <= 1024, "field ≤ 1024");
            total += v.length() + f.getAsJsonObject().get("name").getAsString().length();
        }
        assertTrue(total <= 6000, "embed total " + total);
        assertEquals(0xC62828, embed.get("color").getAsInt(), "red for a pool difference");
        assertEquals(0x2E7D32, DiscordPayload.report(ReportFormatter.format(sample(false)), "x")
            .getAsJsonArray("embeds").get(0).getAsJsonObject().get("color").getAsInt());
    }

    @Test
    void alertPayload() {
        final Alert a = new Alert("POOL", "pool|1", "rozbieżność zamkniętej puli",
            List.of("Suma: **100 000 500 md**", "@here sprawdź"), NOW);
        final JsonObject body = DiscordPayload.alert(a, WAW, "Straż MEDIVALRPG", 2);
        final JsonObject embed = body.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("💰 ALERT — rozbieżność zamkniętej puli", embed.get("title").getAsString());
        final String d = embed.get("description").getAsString();
        assertTrue(d.contains("＠here"));
        assertTrue(d.contains("09:00"));
        assertTrue(d.contains("pominięte wcześniej przez limit alertów: 2"));
        assertEquals("Straż MEDIVALRPG", body.get("username").getAsString());
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }

    @Test
    void stateRoundTrip(@TempDir final Path dir) throws Exception {
        final StateStore store = new StateStore(dir.resolve("stan.json"), java.util.logging.Logger.getAnonymousLogger());
        final StrazState s = store.load();
        s.errors.record(new LogParser.Event(NOW, "ERROR", "Kolej", "x 1", null), 7 * DAY);
        s.metrics.tps(NOW, 19.5, false);
        s.alerts.offer("k", NOW, DAY, 5);
        s.citizens = List.of("a", "b");
        s.flowHistory.put("world:households", List.of(new StrazState.DayNet("2026-10-30", -150)));
        s.lastReportDay = "2026-10-30";
        store.save(s);
        store.save(s); // creates .bak
        assertTrue(Files.exists(dir.resolve("stan.json.bak")));
        final StrazState back = store.load();
        assertEquals("2026-10-30", back.lastReportDay);
        assertEquals(1, back.errors.sigs.size());
        assertEquals(1, back.errors.sigs.values().iterator().next().hours.firstEntry().getValue());
        assertEquals(1, back.metrics.window(NOW, DAY).samples());
        assertEquals(List.of("a", "b"), back.citizens);
        assertEquals(-150, back.flowHistory.get("world:households").get(0).net);
        assertFalse(back.alerts.offer("k", NOW + 1, DAY, 5).send(), "dedupe survives a restart");
        // damaged main file → .bak
        Files.writeString(dir.resolve("stan.json"), "{zepsute");
        assertEquals("2026-10-30", store.load().lastReportDay);
        assertTrue(LocalDateTime.now().getYear() >= 2026);
    }
}
