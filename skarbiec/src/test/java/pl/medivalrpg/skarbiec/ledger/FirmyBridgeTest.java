// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.stats.AccountTypes;
import pl.medivalrpg.skarbiec.stats.Categories;

/** Firmy: atomic batch transfers in the closed pool and the statistics categories of company accounts. */
class FirmyBridgeTest {

    private final List<TxRecord> journal = new ArrayList<>();

    private Ledger closedLedger() {
        final Ledger l = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord record) {
                FirmyBridgeTest.this.journal.add(record);
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        });
        final Map<String, Long> s = new HashMap<>();
        s.put(WorldMoney.RESERVE, WorldMoney.CAP - 30_000);
        s.put(WorldMoney.CASH, 0L);
        s.put(WorldMoney.PAYMENTS, 0L);
        s.put("firma:abc", 20_000L);
        s.put("player:p1", 10_000L);
        l.restore(s, 0);
        l.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        return l;
    }

    @Test
    void batchMovesEverythingOrNothing() {
        final Ledger l = this.closedLedger();
        final TxResult ok = l.transferAll(List.of(new Ledger.Leg("firma:abc", "player:p1", 15_000, "Firmy: dywidenda"),
            new Ledger.Leg("firma:abc", "player:p2", 5_000, "Firmy: dywidenda")));
        assertEquals(TxResult.OK, ok);
        assertEquals(0, l.balance("firma:abc"));
        assertEquals(25_000, l.balance("player:p1"));
        assertEquals(5_000, l.balance("player:p2"));
        assertEquals(WorldMoney.CAP, l.totalMoney());
        assertEquals(2, this.journal.size(), "each leg journaled");
        final TxResult short_ = l.transferAll(List.of(new Ledger.Leg("player:p1", "firma:abc", 20_000, "x"),
            new Ledger.Leg("player:p1", "firma:abc", 6_000, "y")));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, short_, "the payer must cover the SUM of its legs");
        assertEquals(25_000, l.balance("player:p1"));
        assertEquals(2, this.journal.size());
    }

    @Test
    void batchRefusesWorldAccountsAndBadLegs() {
        final Ledger l = this.closedLedger();
        assertEquals(TxResult.INVALID_ACCOUNT, l.transferAll(List.of(new Ledger.Leg(WorldMoney.RESERVE, "firma:abc", 1, "x"))));
        assertEquals(TxResult.INVALID_ACCOUNT, l.transferAll(List.of(new Ledger.Leg("firma:abc", WorldMoney.CASH, 1, "x"))));
        assertEquals(TxResult.INVALID_AMOUNT, l.transferAll(List.of(new Ledger.Leg("firma:abc", "player:p1", 0, "x"))));
        assertEquals(TxResult.INVALID_AMOUNT, l.transferAll(List.of(new Ledger.Leg("firma:abc", "firma:abc", 5, "x"))));
        assertEquals(TxResult.INVALID_ACCOUNT, l.transferAll(List.of(new Ledger.Leg("bad account", "firma:abc", 5, "x"))));
        assertEquals(TxResult.INVALID_AMOUNT, l.transferAll(List.of()));
        assertTrue(this.journal.isEmpty());
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void companyAccountsHaveTheirOwnTypesAndCategory() {
        assertEquals("firms", AccountTypes.type("firma:0a1b2c3d4e"));
        assertEquals("escrow", AccountTypes.type("firma-kontrakt:0a1b2c3d4e"));
        assertEquals("escrow", AccountTypes.type("firma-kredyt:0a1b2c3d4e"));
        assertEquals("escrow", AccountTypes.type("firma-wniosek:0a1b2c3d4e"));
        assertEquals("firms", AccountTypes.type("kolej:przewoznik"));
        assertEquals("firms", Categories.category("Firmy: pensja w firmie Złota Igła", "firma:a", "player:b"));
        assertEquals("firms", Categories.category("Firmy: podatek od sprzedaży w sklepie firmy X", "firma:a", "country:b"));
        assertEquals("trade", Categories.category("sklep: sprzedaż 1x BREAD dla Ola", "world:payments", "firma:a"));
        assertEquals("transfer", Categories.category("przelew od Ola", "player:a", "firma:a"), "a company account alone is not railway");
        assertEquals("railway", Categories.category("x", "kolej:przewoznik", "player:a"));
        assertEquals("Firmy", Categories.plugin("Firmy: dywidenda", "firma:a", "player:b"));
        assertTrue(AccountTypes.technicalName("firma-kredyt:42").contains("kredytu"));
    }
}
