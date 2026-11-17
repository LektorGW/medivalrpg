// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.TxRecord;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;
import pl.medivalrpg.skarbiec.stats.Categories;

class NpcTreasuryTransferTest {

    static final UUID NPC_ID = UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66");
    static final NpcTreasuryTransfer.Country MAZOWSZE = new NpcTreasuryTransfer.Country(NPC_ID, "Księstwo_Mazowieckie", true, "#5E7C16");
    static final NpcTreasuryTransfer.Country PLAYERS = new NpcTreasuryTransfer.Country(UUID.randomUUID(), "Polanie", false, "#B02E26");
    static final long RESERVE = 1_000_000L;

    private final List<TxRecord> log = new ArrayList<>();

    private Ledger ledger() {
        final Ledger l = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord record) {
                NpcTreasuryTransferTest.this.log.add(record);
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        });
        l.restore(Map.of(WorldMoney.RESERVE, RESERVE, WorldMoney.CASH, 5_000L, WorldMoney.PAYMENTS, 0L, MAZOWSZE.account(), 20_000L,
            "player:x", WorldMoney.CAP - RESERVE - 25_000L), 10);
        l.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        return l;
    }

    @Test
    void ownerMovesReserveToTheNpcTreasuryWithReasonAndExactPool() {
        final Ledger l = this.ledger();
        final Map<String, Object> r = NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 100_000, 100_000, "odbudowa po wojnie",
            NpcTreasuryTransfer.TO_COUNTRY);
        assertEquals(true, r.get("ok"));
        assertEquals(RESERVE - 100_000, l.balance(WorldMoney.RESERVE));
        assertEquals(120_000L, l.balance(MAZOWSZE.account()));
        assertEquals(WorldMoney.CAP, l.totalMoney());
        final TxRecord tx = this.log.get(this.log.size() - 1);
        assertEquals(WorldMoney.RESERVE, tx.from());
        assertEquals(MAZOWSZE.account(), tx.to());
        assertTrue(tx.reason().contains("odbudowa po wojnie"));
        assertEquals("world", Categories.category(tx.reason(), tx.from(), tx.to()));
    }

    @Test
    void returnTransferGoesBackToTheReserve() {
        final Ledger l = this.ledger();
        NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 5_000, 5_000, "nadwyżka skarbca", NpcTreasuryTransfer.TO_RESERVE);
        assertEquals(RESERVE + 5_000, l.balance(WorldMoney.RESERVE));
        assertEquals(15_000L, l.balance(MAZOWSZE.account()));
        assertEquals(WorldMoney.CAP, l.totalMoney());
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 999_999, 999_999, "za dużo", NpcTreasuryTransfer.TO_RESERVE));
        assertEquals("Za mało środków w skarbcu kraju.", e.getMessage());
    }

    @Test
    void nonOwnerAndOtherAdminAreRefusedWithoutAnyMove() {
        final Ledger l = this.ledger();
        for (final UUID other : List.of(UUID.randomUUID(), UUID.nameUUIDFromBytes("OfflinePlayer:Admin".getBytes()))) {
            final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NpcTreasuryTransfer.execute(l, other, MAZOWSZE, 100, 100, "próba", NpcTreasuryTransfer.TO_COUNTRY));
            assertEquals("Tylko WorldSteward zarządza Skarbem Świata.", e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> NpcTreasuryTransfer.execute(l, null, MAZOWSZE, 100, 100, "próba", null));
        assertTrue(this.log.isEmpty());
        assertEquals(RESERVE, l.balance(WorldMoney.RESERVE));
    }

    @Test
    void insufficientReserveUnknownOrPlayerCountryAndBadInputAreRefused() {
        final Ledger l = this.ledger();
        assertEquals("Za mało środków w rezerwie Skarbu Świata.", assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, RESERVE + 1, RESERVE + 1, "za dużo", null)).getMessage());
        assertEquals("Nie ma takiego państwa NPC.", assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, null, 100, 100, "brak kraju", null)).getMessage());
        assertEquals("Nie ma takiego państwa NPC.", assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, PLAYERS, 100, 100, "kraj gracza", null)).getMessage());
        assertEquals("Potwierdzona kwota nie zgadza się z kwotą przelewu.", assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 1_000, 100, "literówka", null)).getMessage());
        assertEquals("Podaj powód przelewu (3–120 znaków).", assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 100, 100, " ", null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 0, 0, "zero", null));
        assertThrows(IllegalArgumentException.class, () -> NpcTreasuryTransfer.execute(l, WorldMoney.MANAGER, MAZOWSZE, 100, 100, "zły kierunek", "sideways"));
        assertTrue(this.log.isEmpty());
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void refusedBeforeTheClosedPoolIsActive() {
        final Ledger open = new Ledger(Ledger.Sink.NONE);
        assertThrows(IllegalArgumentException.class,
            () -> NpcTreasuryTransfer.execute(open, WorldMoney.MANAGER, MAZOWSZE, 100, 100, "przed migracją", null));
    }
}
