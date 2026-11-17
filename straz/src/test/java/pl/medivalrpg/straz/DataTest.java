// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.straz.economy.Flows;
import pl.medivalrpg.straz.economy.Pool;
import pl.medivalrpg.straz.kolej.RailReader;
import pl.medivalrpg.straz.kolej.RailSummary;
import pl.medivalrpg.straz.people.Census;

class DataTest {

    static final String ACCOUNTS = """
        {"format":1,"seq":19448,"saved":"2026-10-29T03:04:55Z","accounts":{
          "country:a":4996037,"country:b":5003963,
          "kolej:przewoznik":1301000,"kolej:budzet_c7f3fc34504cf3ab":818272,
          "player:ff923cf7-f43c-37b8-85a3-77cbac357c9c":821069,"player:x":1000,
          "wladcy:zlecenie-1":0,
          "world:cash":28085,"world:households":512589,"world:merchant":1049915,"world:payments":74462,
          "world:reserve":%d}}""";

    static String accounts(final long reserve) {
        return ACCOUNTS.formatted(reserve);
    }

    /** Reserve that makes the pool exactly 100 000 000. */
    static final long RESERVE = 100_000_000L - (4996037 + 5003963 + 1301000 + 818272 + 821069 + 1000 + 28085 + 512589 + 1049915 + 74462);

    @Test
    void poolSumsAllAccountsAndBreaksDown() {
        final Pool.Snapshot p = Pool.parse(accounts(RESERVE), 100_000_000L);
        assertEquals(100_000_000L, p.total());
        assertTrue(p.ok());
        assertEquals(0, p.diff());
        assertEquals(12, p.accountCount());
        assertEquals(19448, p.seq());
        final Map<String, Long> parts = new java.util.LinkedHashMap<>();
        p.parts().forEach(x -> parts.put(x.label(), x.amount()));
        assertEquals(RESERVE, parts.get("Rezerwa Skarbu Świata"));
        assertEquals(10_000_000L, parts.get("Skarbce krajów"));
        assertEquals(2_119_272L, parts.get("Kolej (przewoźnik i budżety)"));
        assertEquals(822_069L, parts.get("Konta graczy"));
        assertEquals(0L, parts.get("Pozostałe konta"));
        long sum = 0;
        for (final var x : p.parts()) {
            sum += x.amount();
        }
        assertEquals(p.total(), sum, "breakdown covers every account");
    }

    @Test
    void poolDifferenceAndNegativeAccounts() {
        final Pool.Snapshot more = Pool.parse(accounts(RESERVE + 500), 100_000_000L);
        assertFalse(more.ok());
        assertEquals(500, more.diff());
        final Pool.Snapshot neg = Pool.parse("{\"accounts\":{\"a\":-5,\"b\":105}}", 100);
        assertEquals(1, neg.negative());
        assertFalse(neg.ok(), "negative balance is a problem even with a correct sum");
    }

    @Test
    void poolReplaysOperationsAfterTheSnapshot() {
        final String snap = "{\"seq\":10,\"accounts\":{\"world:reserve\":900,\"a\":100}}";
        final List<String> after = List.of(
            "9\t2026-10-29T02:00:00Z\tTRANSFER\tworld:reserve\ta\t50\t850\t150\told (in snapshot)",
            "11\t2026-10-29T02:01:00Z\tDEPOSIT\t-\tstraz-test:probe\t500\t-\t500\tcreated money",
            "12\t2026-10-29T02:02:00Z\tTRANSFER\tworld:reserve\ta\t100\t800\t200\tgrant",
            "12\t2026-10-29T02:02:00Z\tTRANSFER\tworld:reserve\ta\t100\t800\t200\tgrant (duplicate from the journal)",
            "13\t2026-10-29T02:03:00Z\tWITHDRAW\tstraz-test:probe\t-\t500\t0\t-\tremoved",
            "14\t2026-10-29T02:04:00Z\tTRAN");
        final Pool.Snapshot p = Pool.parse(snap, after.subList(0, 3), 1000);
        assertEquals(1500, p.total(), "money created after the snapshot is visible");
        assertEquals(12, p.seq());
        assertTrue(p.certain());
        final Pool.Snapshot q = Pool.parse(snap, after, 1000);
        assertEquals(1000, q.total());
        assertEquals(13, q.seq());
        assertEquals(800, q.balance("world:reserve"));
        assertTrue(q.ok());
        final Pool.Snapshot gap = Pool.parse(snap, List.of(after.get(2)), 1000);
        assertFalse(gap.certain(), "seq 11 missing → uncertain cut, no alert");
    }

    static final List<String> TX = List.of(
        "18859\t2026-10-28T23:29:17.664Z\tTRANSFER\tworld:reserve\tworld:households\t100000\t76525502\t200000\tWorldSteward: przydział ze Skarbu Świata",
        "19402\t2026-10-29T02:09:44.655Z\tTRANSFER\tworld:households\tcountry:2a\t300\t199999\t4999581\tLudnosc: podatek",
        "19403\t2026-10-29T02:10:00.000Z\tTRANSFER\tworld:payments\tworld:households\t100\t1\t2\tRobocizna",
        "19404\t2026-10-29T02:11:00.000Z\tDEPOSIT\t-\tworld:households\t50\t-\t3\tx",
        "19405\t2026-10-29T02:12:00.000Z\tSET\t-\tworld:households\t0\t-\t0\tMigracja",
        "19406\t2026-10-29T02:13:00.000Z\tTRANSFER\tworld:households\tworld:reserve\t1000\t1\t2\tZwrot",
        "19407\t2026-10-27T02:13:00.000Z\tTRANSFER\tworld:households\tcountry:x\t9999\t1\t2\tstare",
        "zepsuta linia");

    @Test
    void flowsSeparateGrantsFromOrganicFlow() {
        final long from = java.time.Instant.parse("2026-10-28T08:00:00Z").toEpochMilli();
        final long to = java.time.Instant.parse("2026-10-29T08:00:00Z").toEpochMilli();
        final Flows.Flow f = Flows.of("world:households", TX, from, to);
        assertEquals(150, f.in());
        assertEquals(300, f.out());
        assertEquals(100_000, f.grantsIn());
        assertEquals(1000, f.returnsOut());
        assertEquals(-150, f.net());
        assertEquals(5, f.transactions());
        assertEquals(1000, Flows.daysLeft(150_000, -150));
        assertEquals(-1, Flows.daysLeft(150_000, 10), "growing balance has no end");
        assertEquals(0, Flows.daysLeft(0, -5));
    }

    static final String JOBS = """
        {"version":1,"jobs":[
          {"id":"baza_krolewska","world":"w","country":"c","entries":[{"x":1},{"x":2},{"x":3}],"cursor":3,"cost":41829,"status":"DONE","reason":"","reused":[],"financeHeld":false},
          {"id":"mr-preclear-01","entries":[{"x":1},{"x":2}],"cursor":2,"cost":12,"status":"DONE","reason":null},
          {"id":"mr-preclear-02","entries":[{"x":1},{"x":2},{"x":3},{"x":4}],"cursor":1,"cost":40,"status":"QUEUED","reason":""},
          {"id":"mr-preclear-03","entries":[],"cursor":0,"cost":0,"status":"BLOCKED","reason":"Zabudowa przy 1:2:3"}
        ]}""";

    @Test
    void railReaderStreamsJobs() throws Exception {
        final Map<String, RailSummary.Job> jobs = RailReader.readJobs(new StringReader(JOBS));
        assertEquals(4, jobs.size());
        assertEquals(3, jobs.get("baza_krolewska").entries());
        assertEquals("", jobs.get("mr-preclear-01").reason(), "null reason → empty");
        assertEquals(4, jobs.get("mr-preclear-02").entries());
        assertEquals("BLOCKED", jobs.get("mr-preclear-03").status());
    }

    @Test
    void railSummaryCountsPartsAndStops() throws Exception {
        final Map<String, RailSummary.Job> jobs = RailReader.readJobs(new StringReader(JOBS));
        final List<String> parts = List.of("mr-preclear-01", "mr-preclear-02", "mr-preclear-03", "mr-preclear-04", "mr-factory-01");
        final RailSummary.Summary s = RailSummary.of(parts, 818_789, jobs, 818_272, "Budowa mr-preclear-02 1/4");
        assertEquals(5, s.parts());
        assertEquals(1, s.done());
        assertEquals(1, s.inProgress());
        assertEquals(1, s.stopped());
        assertEquals(2, s.waiting());
        assertEquals(517, s.spent(), "allocated budget minus balance");
        assertEquals(52, s.jobsCost());
        assertEquals(3, s.blocksDone());
        assertEquals(6, s.blocksTotal());
        assertTrue(s.isStopped());
        assertEquals("mr-preclear-03", s.stops().get(0).part());
        assertTrue(s.stops().get(0).reason().contains("Zabudowa"));
    }

    @Test
    void railStopVisibleOnlyInStatusLine() {
        final List<String> parts = List.of("mr-preclear-01", "mr-preclear-02");
        final RailSummary.Summary s = RailSummary.of(parts, 818_789, Map.of(), -1,
            "Budowa wstrzymana: Zabudowa lub niezatwierdzony materiał w promieniu 8 m przy 3647:78:778");
        assertTrue(s.isStopped());
        assertEquals(1, s.stopped());
        assertEquals(1, s.waiting());
        assertEquals(0, s.spent(), "budget not allocated yet");
        assertEquals("mr-preclear-01", s.stops().get(0).part());
        final RailSummary.Summary waiting = RailSummary.of(parts, 818_789, Map.of(), -1,
            "Oczekiwanie na kontrolę wdrożenia przed rozpoczęciem robót");
        assertFalse(waiting.isStopped());
        assertTrue(RailSummary.isBuildingStatus("Budowa mr-preclear-02 10/40"));
        assertFalse(RailSummary.isBuildingStatus("Budowa wstrzymana: x"));
        assertTrue(RailSummary.isStopStatus("Zatrzymane: mr-x · powód"));
    }

    @Test
    void censusBirthsDeparturesAndBuildings() {
        final long day = 86_400_000L;
        final long now = 1_793_300_000_000L;
        final String data = """
            {"version":2,"citizens":[{"entityId":"a"},{"entityId":"b"},{"entityId":"c"},{"entityId":"d"}],
             "buildings":[{"type":"dom","createdAt":%d,"registered":false},{"type":"studnia","createdAt":%d,"registered":false},
                          {"type":"dom","createdAt":%d,"registered":false},{"type":"zgloszony","createdAt":%d,"registered":true}],
             "sites":[{},{},{}]}""".formatted(now - 1000, now - 2000, now - 3 * day, now - 1000);
        final String life = """
            {"version":1,"citizens":{"a":{"born":0},"c":{"born":%d},"d":{"born":%d}}}""".formatted(now - 5000, now - 5 * day);
        final Census.Data d = Census.parse(data, life);
        final Census.Stats first = Census.stats(d, null, now - day, now);
        assertEquals(4, first.citizens());
        assertEquals(1, first.births());
        assertEquals(-1, first.removed(), "no register before the first report");
        final Census.Stats st = Census.stats(d, Set.of("a", "b", "x", "y"), now - day, now);
        assertEquals(2, st.removed(), "x and y left the register");
        assertEquals(1, st.arrived(), "c born, d arrived");
        assertEquals(2, st.buildingsFinished(), "registered houses and old buildings do not count");
        assertEquals(Map.of("dom", 1, "studnia", 1), st.buildingTypes());
        assertEquals(3, st.sitesActive());
    }
}
