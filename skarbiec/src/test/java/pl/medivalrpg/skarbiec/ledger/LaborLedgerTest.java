// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.stats.AccountTypes;
import pl.medivalrpg.skarbiec.stats.Categories;

class LaborLedgerTest {

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    static final long HOUR = 3_600_000L;
    static final String COUNTRY = "country:17b1bc20-8d55-4b16-8fab-30418b89d3ea";
    static final String CITIZENS = "ludnosc:obywatele_17b1bc20-8d55-4b16-8fab-30418b89d3ea";
    static final LaborShare POLICY = new LaborShare(6_000, LaborShare.DEFAULT_PREFIXES, LaborShare.DEFAULT_REFUND_PREFIXES, 3_000, 400_000, 24);

    final long[] clock = {1_761_700_000_000L};
    final List<TxRecord> log = new ArrayList<>();
    Ledger ledger;
    LaborLedger labor;

    void setUp(final long payments, final long households) {
        this.ledger = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord record) {
                LaborLedgerTest.this.log.add(record);
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        }, () -> this.clock[0]);
        this.ledger.restore(Map.of(WorldMoney.RESERVE, WorldMoney.CAP - 1_000_000 - payments - households, WorldMoney.CASH, 0L,
            WorldMoney.PAYMENTS, payments, WorldMoney.HOUSEHOLDS, households, COUNTRY, 900_000L, CITIZENS, 100_000L), 1);
        this.ledger.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        this.labor = new LaborLedger(POLICY, ZONE);
        this.ledger.setRefundPrefixes(POLICY.refundPrefixes());
        this.ledger.addObserver(this.labor::observe);
    }

    @Test
    void paymentCreatesPendingLabourThatSettlesOnlyAfterMaturity() {
        this.setUp(0, 0);
        assertTrue(this.ledger.withdraw(COUNTRY, 4_000, "Ludnosc: budowa dom_szachulec").ok());
        assertEquals(2_400, this.labor.pendingTotal());
        assertEquals(0, this.labor.settle(this.ledger, this.clock[0]), "not mature yet");
        this.clock[0] += 25 * HOUR;
        assertEquals(2_400, this.labor.settle(this.ledger, this.clock[0]));
        assertEquals(2_400, this.ledger.balance(WorldMoney.HOUSEHOLDS));
        assertEquals(1_600, this.ledger.balance(WorldMoney.PAYMENTS));
        assertEquals(WorldMoney.CAP, this.ledger.totalMoney());
        final TxRecord settled = this.log.get(this.log.size() - 1);
        assertTrue(settled.reason().startsWith(LaborShare.REASON_PREFIX));
        assertEquals("labor", Categories.category(settled.reason(), settled.from(), settled.to()));
        assertEquals(0, this.labor.pendingTotal());
    }

    @Test
    void fullRefundOfAQueuedOrderCancelsItsLabour() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 4_000, "Ludnosc: budowa dom (kolejka)");
        assertTrue(this.ledger.deposit(COUNTRY, 4_000, "Ludnosc: zwrot (kolejka) dom").ok());
        assertEquals(0, this.labor.pendingTotal());
        this.clock[0] += 48 * HOUR;
        assertEquals(0, this.labor.settle(this.ledger, this.clock[0]));
        assertEquals(0, this.ledger.balance(WorldMoney.HOUSEHOLDS));
        assertEquals(900_000, this.ledger.balance(COUNTRY), "country got everything back");
    }

    @Test
    void partialRefundCancelsProportionally() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 1_000, "Ludnosc: naprawa kamienica");
        this.ledger.deposit(COUNTRY, 500, "Ludnosc: zwrot za budowę kamienica");
        assertEquals(300, this.labor.pendingTotal());
        assertEquals(300, this.labor.cancelledTotal());
    }

    @Test
    void citizensPayingForTheirHutAndOnlyListedReasonsCount() {
        this.setUp(0, 0);
        this.ledger.withdraw(CITIZENS, 1_000, "Ludnosc: obywatele opłacają budowę chaty z własnych oszczędności");
        this.ledger.withdraw(COUNTRY, 1_000, "Ludnosc: kopalnia_glebinowa");
        this.ledger.withdraw(COUNTRY, 1_000, "Armia: żołd 12 żołnierzy");
        assertEquals(600, this.labor.pendingTotal());
        assertEquals("citizens", AccountTypes.type(CITIZENS));
        this.ledger.deposit(CITIZENS, 1_000, "Ludnosc: zwrot oszczędności obywateli — chata nie powstała");
        assertEquals(0, this.labor.pendingTotal());
    }

    @Test
    void dailyCapTargetAndPaymentsBalanceLimitTheSettlement() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 100_000, "Ludnosc: budowa palac");
        this.clock[0] += 25 * HOUR;
        assertEquals(3_000, this.labor.settle(this.ledger, this.clock[0]), "daily cap");
        assertEquals(0, this.labor.settle(this.ledger, this.clock[0] + HOUR), "cap used up today");
        this.clock[0] += 24 * HOUR;
        assertEquals(3_000, this.labor.settle(this.ledger, this.clock[0]));
        assertEquals(54_000, this.labor.pendingTotal());

        this.setUp(0, 399_000);
        this.ledger.withdraw(COUNTRY, 100_000, "Ludnosc: drogi");
        this.clock[0] += 25 * HOUR;
        assertEquals(1_000, this.labor.settle(this.ledger, this.clock[0]), "only up to the households target");
    }

    @Test
    void refundIsNeverBlockedByLabourAlreadyPassedOn() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 5_000, "Ludnosc: budowa dom");
        this.clock[0] += 25 * HOUR;
        this.labor.settle(this.ledger, this.clock[0]); // 3 000 to households (cap), 2 000 left in payments
        assertEquals(2_000, this.ledger.balance(WorldMoney.PAYMENTS));
        final TxResult r = this.ledger.deposit(COUNTRY, 5_000, "Ludnosc: zwrot za budowę dom");
        assertTrue(r.ok(), "shortfall comes back from households");
        assertEquals(0, this.ledger.balance(WorldMoney.PAYMENTS));
        assertEquals(0, this.ledger.balance(WorldMoney.HOUSEHOLDS));
        assertEquals(900_000, this.ledger.balance(COUNTRY));
        assertEquals(WorldMoney.CAP, this.ledger.totalMoney());
        // A non-refund deposit is never topped up from the households pool.
        assertEquals(TxResult.INSUFFICIENT_FUNDS, this.ledger.deposit(COUNTRY, 1, "Dochód kraju NPC"));
        // Not enough in both pools: refused without any partial move.
        final int before = this.log.size();
        assertEquals(TxResult.INSUFFICIENT_FUNDS, this.ledger.deposit(COUNTRY, 10, "Ludnosc: zwrot za budowę dom"));
        assertEquals(before, this.log.size());
    }

    @Test
    void pendingSurvivesSaving() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 1_000, "Ludnosc: modernizacja dom");
        final LaborLedger back = new LaborLedger(POLICY, ZONE);
        back.load(this.labor.toJson());
        assertEquals(600, back.pendingTotal());
        assertEquals(600, back.due(this.clock[0] + 25 * HOUR));
    }

    @Test
    void aMalformedFileLeavesTheStateUntouched() {
        this.setUp(0, 0);
        this.ledger.withdraw(COUNTRY, 1_000, "Ludnosc: modernizacja dom");
        final com.google.gson.JsonObject broken = this.labor.toJson();
        broken.addProperty("settledTotal", 777);
        broken.getAsJsonObject("pending").add("country:zepsuty", com.google.gson.JsonParser.parseString("[[1,\"x\"]]"));
        final LaborLedger back = new LaborLedger(POLICY, ZONE);
        back.load(this.labor.toJson());
        assertThrows(RuntimeException.class, () -> back.load(broken));
        assertEquals(600, back.pendingTotal(), "the previous state survives a failed load");
        assertEquals(0, back.settledTotal(), "no field of the broken file is half applied");
        back.load(null);
        assertEquals(0, back.pendingTotal());
    }
}
