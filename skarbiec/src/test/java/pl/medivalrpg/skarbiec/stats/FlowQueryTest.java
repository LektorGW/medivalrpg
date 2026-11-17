// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.TxRecord;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;

class FlowQueryTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_761_600_000_000L;
    private static final String BOLKO = "player:6a0e1f2c-1111-2222-3333-444455556666";
    private static final String POLANIE = "country:17b1bc20-8d55-4b16-8fab-30418b89d3ea";
    private static final Function<String, String> LABEL = a -> a.equals(BOLKO) ? "Bolko" : a.equals(POLANIE) ? "Królestwo Polan" : AccountTypes.technicalName(a);

    @TempDir
    Path dir;

    /** Records produced by a real closed ledger, exactly as LedgerStore would log them. */
    private static List<TxRecord> economy() {
        final List<TxRecord> out = new ArrayList<>();
        final long[] clock = {T0};
        final Ledger ledger = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord record) {
                out.add(record);
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        }, () -> clock[0]);
        ledger.restore(Map.of(WorldMoney.RESERVE, 99_000_000L, WorldMoney.CASH, 1_000L, WorldMoney.PAYMENTS, 500_000L,
            WorldMoney.MERCHANT, 0L, WorldMoney.HOUSEHOLDS, 0L, BOLKO, 10_000L, POLANIE, 489_000L), 100);
        ledger.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        ledger.allocateWorldMoney(WorldMoney.MANAGER, WorldMoney.MERCHANT, 50_000, "WorldSteward: przydział ze Skarbu Świata");
        clock[0] += 3_600_000;
        ledger.transfer(BOLKO, POLANIE, 930, "Kraje: podatek od obywateli");
        clock[0] += DAY;
        ledger.withdraw(POLANIE, 1_350, "Armia: żołd 12 żołnierzy");
        ledger.transfer(WorldMoney.MERCHANT, BOLKO, 120, "kupiec: skup 16x WHEAT");
        clock[0] += 60_000;
        ledger.redeemCash(BOLKO, 300, "cash " + BOLKO.substring(7) + ": wpłata fizycznych monet");
        return out;
    }

    private static FlowQuery.Filter all() {
        return new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, null, null, 0, 0, 200);
    }

    @Test
    void categoriesAndPluginsFromRealReasons() {
        assertEquals("tax", Categories.category("Kraje: podatek od obywateli", BOLKO, POLANIE));
        assertEquals("tax", Categories.category("Ludnosc: podatek od obywateli NPC i kopalnie", WorldMoney.HOUSEHOLDS, POLANIE));
        assertEquals("npcIncome", Categories.category("Dochód kraju NPC", WorldMoney.PAYMENTS, POLANIE));
        assertEquals("wages", Categories.category("Armia: żołd 12 żołnierzy", POLANIE, WorldMoney.PAYMENTS));
        assertEquals("army", Categories.category("Armia NPC: rekrutacja piechota", POLANIE, WorldMoney.PAYMENTS));
        assertEquals("construction", Categories.category("Ludnosc: budowa wieza", POLANIE, WorldMoney.PAYMENTS));
        assertEquals("railway", Categories.category("Kolej: budowa toru", "kolej:przewoznik", "kolej:obsluga"));
        assertEquals("land", Categories.category("Kraje: claim (Watykan)", POLANIE, WorldMoney.PAYMENTS));
        assertEquals("treasury", Categories.category("Kraje: wpłata do skarbca Watykan (monety)", BOLKO, POLANIE));
        assertEquals("research", Categories.category("Postęp: inwestycja w badania", POLANIE, WorldMoney.PAYMENTS));
        assertEquals("merchant", Categories.category("kupiec: skup 16x WHEAT", WorldMoney.MERCHANT, BOLKO));
        assertEquals("cash", Categories.category("cash x: przyjęcie monet", WorldMoney.CASH, BOLKO));
        assertEquals("world", Categories.category("WorldSteward: przydział ze Skarbu Świata", WorldMoney.RESERVE, WorldMoney.MERCHANT));
        assertEquals("admin", Categories.category("Panel: korekta admina WorldSteward", WorldMoney.PAYMENTS, BOLKO));
        assertEquals("quests", Categories.category("nagroda", "wladcy:zlecenie-1", BOLKO));
        assertEquals("other", Categories.category("coś zupełnie innego", BOLKO, POLANIE));
        assertEquals("Armia", Categories.plugin("Dochód kraju NPC", WorldMoney.PAYMENTS, POLANIE));
        assertEquals("Skarbiec", Categories.plugin("kupiec: skup", WorldMoney.MERCHANT, BOLKO));
        assertEquals("Kolej", Categories.plugin("Obsługa kolei t1", "kolej:przewoznik", "kolej:obsluga"));
        assertEquals("Ludnosc", Categories.plugin("Ludnosc: budowa", POLANIE, WorldMoney.PAYMENTS));
    }

    @Test
    void accountTypesCoverEveryKnownNamespace() {
        assertEquals("reserve", AccountTypes.type(WorldMoney.RESERVE));
        assertEquals("cash", AccountTypes.type(WorldMoney.CASH));
        assertEquals("players", AccountTypes.type(BOLKO));
        assertEquals("countries", AccountTypes.type(POLANIE));
        assertEquals("firms", AccountTypes.type("kolej:przewoznik"));
        assertEquals("railBudgets", AccountTypes.type("kolej:budzet_0123456789abcdef"));
        assertEquals("escrow", AccountTypes.type("wladcy:zlecenie-abc"));
        assertEquals("escrow", AccountTypes.type("szlaki:42"));
        assertEquals("escrow", AccountTypes.type("turnieje:7"));
        assertEquals("server", AccountTypes.type("serwer:turnieje"));
        assertEquals("other", AccountTypes.type("ludnosc:budowa"));
        assertEquals(AccountTypes.ORDER.size(), AccountTypes.LABELS.size());
    }

    @Test
    void aggregatesCoverAllMatchesWhilePagingRows() throws Exception {
        final List<TxRecord> records = economy();
        final Map<String, Object> r = FlowQuery.run(FlowQuery.Source.of(records), new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, null,
            null, 0, 1, 2), LABEL, ZONE, 90, null);
        assertEquals(5L, r.get("total"));
        final List<?> rows = (List<?>) r.get("rows");
        assertEquals(2, rows.size());
        final Map<?, ?> second = (Map<?, ?>) rows.get(0); // newest first, offset 1 → the merchant payout
        assertEquals("merchant", second.get("category"));
        assertEquals("Bolko", second.get("toLabel"));
        assertEquals("Skarbiec", second.get("plugin"));
        assertEquals(50_000L + 930 + 1_350 + 120 + 300, r.get("sumAmount"));
        final List<?> matrix = (List<?>) r.get("matrix");
        assertTrue(matrix.stream().map(Map.class::cast).anyMatch(m -> m.get("from").equals("countries") && m.get("to").equals("payments")
            && m.get("amount").equals(1_350L)));
        final List<?> days = (List<?>) r.get("daily");
        assertEquals(2, days.size());
        final Map<?, ?> d1 = (Map<?, ?>) days.get(0);
        assertEquals(0L, d1.get("in")); // reserve → merchant budget stays inside the world pools
        final Map<?, ?> d2 = (Map<?, ?>) days.get(1);
        assertEquals(120L, d2.get("in")); // merchant budget → player
        assertEquals(1_350L, d2.get("out")); // country → operating budget
    }

    @Test
    void filtersByAccountCategoryTypeQueryAndAmount() throws Exception {
        final List<TxRecord> records = economy();
        final FlowQuery.Source src = FlowQuery.Source.of(records);
        assertEquals(3L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, BOLKO, null, null, null, 0, 0, 10), LABEL, ZONE, 90, null).get("total"));
        assertEquals(1L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, "wages", null, null, 0, 0, 10), LABEL, ZONE, 90, null).get("total"));
        assertEquals(2L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, "countries", null, 0, 0, 10), LABEL, ZONE, 90, null).get("total"));
        // search ignores case and Polish letters, and finds account labels
        assertEquals(1L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, null, "ZOLD", 0, 0, 10), LABEL, ZONE, 90, null).get("total"));
        assertEquals(2L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, null, "królestwo polan", 0, 0, 10), LABEL, ZONE, 90, null).get("total"));
        assertEquals(2L, FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, null, null, null, null, 1_000, 0, 10), LABEL, ZONE, 90, null).get("total"));
        final Map<String, Object> bolko = FlowQuery.run(src, new FlowQuery.Filter(T0 - DAY, T0 + 3 * DAY, BOLKO, null, null, null, 0, 0, 10), LABEL, ZONE, 90, null);
        long in = 0;
        long out = 0;
        for (final Object o : (List<?>) bolko.get("daily")) {
            in += (Long) ((Map<?, ?>) o).get("in");
            out += (Long) ((Map<?, ?>) o).get("out");
        }
        assertEquals(120 + 300, in);
        assertEquals(930, out);
    }

    @Test
    void accountHistoryRebuildsBalancesFromTheLedger() throws Exception {
        final List<TxRecord> records = economy();
        final Map<String, Object> h = FlowQuery.account(FlowQuery.Source.of(records), BOLKO, T0 - DAY, T0 + 3 * DAY, 9_490, LABEL);
        final List<?> points = (List<?>) h.get("points");
        assertEquals(10_000L, ((Map<?, ?>) points.get(0)).get("balance"));
        assertEquals(9_070L, ((Map<?, ?>) points.get(1)).get("balance"));
        assertEquals(9_490L, ((Map<?, ?>) points.get(points.size() - 1)).get("balance"));
        assertEquals(420L, h.get("in"));
        assertEquals(930L, h.get("out"));
        final List<?> parties = (List<?>) h.get("counterparties");
        assertEquals(3, parties.size());
    }

    @Test
    void downsampleKeepsTheLastPoint() {
        final List<long[]> pts = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            pts.add(new long[] {i, i});
        }
        final List<long[]> d = FlowQuery.downsample(pts, 100);
        assertEquals(100, d.size());
        assertEquals(1999, d.get(99)[0]);
    }

    @Test
    void filterClampsLimits() {
        final FlowQuery.Filter f = new FlowQuery.Filter(0, 1, " ", "", null, "  ", -5, -3, 5_000);
        assertEquals(1000, f.limit());
        assertEquals(0, f.offset());
        assertEquals(0, f.minAmount());
        assertNull(f.account());
        assertNull(f.query());
    }

    @Test
    void indexReadsDailyLogsIncrementallyAndSkipsTornLines() throws Exception {
        final List<TxRecord> records = economy();
        final FlowIndex index = new FlowIndex(this.dir, ZONE);
        for (final TxRecord r : records) {
            final String day = java.time.Instant.ofEpochMilli(r.time()).atZone(ZONE).toLocalDate().toString();
            Files.writeString(this.dir.resolve("transakcje-" + day + ".log"), r.encode() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        assertEquals(5, index.range(T0 - DAY, T0 + 3 * DAY).size());
        final String lastDay = java.time.Instant.ofEpochMilli(records.get(4).time()).atZone(ZONE).toLocalDate().toString();
        final Path last = this.dir.resolve("transakcje-" + lastDay + ".log");
        final TxRecord extra = new TxRecord(999, records.get(4).time() + 1, TxRecord.Type.TRANSFER, BOLKO, POLANIE, 5, 1L, 2L, "przelew: x");
        final String line = extra.encode();
        Files.writeString(last, line.substring(0, 20), StandardCharsets.UTF_8, StandardOpenOption.APPEND); // torn write in progress
        assertEquals(5, index.range(T0 - DAY, T0 + 3 * DAY).size());
        Files.writeString(last, line.substring(20) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals(6, index.range(T0 - DAY, T0 + 3 * DAY).size());
        assertEquals(1, index.range(T0 + DAY, T0 + DAY + 2 * 3_600_000L).stream().filter(r -> r.seq() == 999).count());
        assertTrue(index.oldestDay() != null);
    }

    @Test
    void cashEventsComeFromLedgerConversions() {
        final List<TxRecord> records = economy();
        final TxRecord deposit = records.get(records.size() - 1);
        final TrailLog.Event e = WorldStats.cashEvent(deposit);
        assertEquals("DEPOSIT", e.kind());
        assertEquals(BOLKO.substring(7), e.playerId());
        assertEquals(300, e.amount());
        assertNull(WorldStats.cashEvent(records.get(1)));
    }

    @Test
    void onlyThePersonalManagerPassesTheOwnerCheck() {
        WorldStats.requireOwner(WorldMoney.MANAGER);
        final IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
            () -> WorldStats.requireOwner(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertEquals(WorldStats.DENIED, other.getMessage());
        assertThrows(IllegalArgumentException.class, () -> WorldStats.requireOwner(null));
        // Same nick, different (online-mode) UUID: still denied — only the UUID counts.
        assertThrows(IllegalArgumentException.class, () -> WorldStats.requireOwner(UUID.nameUUIDFromBytes("WorldSteward".getBytes(StandardCharsets.UTF_8))));
    }
}
