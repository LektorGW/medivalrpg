// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/** Equal transfer to every NPC country: owner only, all-or-nothing, idempotent by key, durable record, pool unchanged. */
class NpcBulkTransfersTest {

    @TempDir
    Path bank;

    private final List<TxRecord> journal = new ArrayList<>();

    private Ledger ledger(final long reserve) {
        final Ledger l = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord record) {
                NpcBulkTransfersTest.this.journal.add(record);
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        });
        l.restore(Map.of(WorldMoney.RESERVE, reserve, WorldMoney.CASH, 0L, WorldMoney.PAYMENTS, WorldMoney.CAP - reserve), 1);
        l.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        return l;
    }

    private static List<NpcBulkTransfers.Leg> countries(final int n) {
        final List<NpcBulkTransfers.Leg> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final UUID id = UUID.nameUUIDFromBytes(("kraj-" + i).getBytes(StandardCharsets.UTF_8));
            out.add(new NpcBulkTransfers.Leg(id.toString(), "Kraj " + i, SkarbiecApi.countryAccount(id)));
        }
        return out;
    }

    /** Writes the journal records as the bank's journal.log (what a restart would read). */
    private void persistJournal(final List<TxRecord> records) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (final TxRecord r : records) {
            sb.append(r.encode()).append('\n');
        }
        Files.writeString(this.bank.resolve("journal.log"), sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void paysEveryCountryOnceAndKeepsThePool() throws Exception {
        final Ledger l = this.ledger(1_000_000);
        final NpcBulkTransfers bulk = new NpcBulkTransfers(this.bank);
        final var legs = countries(17);
        final var r = bulk.execute(l, WorldMoney.MANAGER, "npcbulk-aaaaaaaaaaaa", legs, 2_500, 17 * 2_500, 17, "wsparcie zimowe", false, null);
        assertEquals(42_500L, r.get("total"));
        assertEquals(17, r.get("count"));
        assertEquals(1_000_000 - 42_500, l.balance(WorldMoney.RESERVE));
        for (final var leg : legs) {
            assertEquals(2_500, l.balance(leg.account()), leg.name());
        }
        assertEquals(WorldMoney.CAP, l.totalMoney(), "closed pool unchanged");
        assertEquals(17, this.journal.size());
        assertTrue(this.journal.stream().allMatch(t -> t.reason().contains("[npc-bulk:npcbulk-aaaaaaaaaaaa]")), "one shared operation id");
        assertTrue(this.journal.stream().allMatch(t -> WorldMoney.RESERVE.equals(t.from())));
        assertEquals("PENDING", bulk.op("npcbulk-aaaaaaaaaaaa").state(), "durable record written before the moves");
        bulk.markDone("npcbulk-aaaaaaaaaaaa");
        assertEquals("DONE", new NpcBulkTransfers(this.bank).op("npcbulk-aaaaaaaaaaaa").state(), "state survives a restart");
        // statistics: one operation with its breakdown
        final var d = NpcBulkTransfers.describe(bulk.recent(5).getFirst());
        assertEquals(42_500L, d.get("total"));
        assertEquals(17, ((List<?>) d.get("legs")).size());
    }

    @Test
    void sameKeyNeverPaysTwice() throws Exception {
        final Ledger l = this.ledger(1_000_000);
        final NpcBulkTransfers bulk = new NpcBulkTransfers(this.bank);
        final var legs = countries(3);
        bulk.execute(l, WorldMoney.MANAGER, "npcbulk-bbbbbbbbbbbb", legs, 1_000, 3_000, 3, "pomoc", false, null);
        final long reserve = l.balance(WorldMoney.RESERVE);
        final var again = bulk.execute(l, WorldMoney.MANAGER, "npcbulk-bbbbbbbbbbbb", legs, 1_000, 3_000, 3, "pomoc", false, null);
        assertEquals(true, again.get("replayed"));
        assertEquals(reserve, l.balance(WorldMoney.RESERVE));
        bulk.markDone("npcbulk-bbbbbbbbbbbb");
        final var restarted = new NpcBulkTransfers(this.bank);
        assertFalse(restarted.needsEvidence("npcbulk-bbbbbbbbbbbb"));
        assertEquals(true, restarted.execute(l, WorldMoney.MANAGER, "npcbulk-bbbbbbbbbbbb", legs, 1_000, 3_000, 3, "pomoc", true, null).get("replayed"));
        assertEquals(reserve, l.balance(WorldMoney.RESERVE), "even an explicit retry of a DONE operation moves nothing");
        assertEquals(3, this.journal.size());
    }

    @Test
    void refusesOthersEmptyListShortReserveAndChangedPreview() throws Exception {
        final Ledger l = this.ledger(10_000);
        final NpcBulkTransfers bulk = new NpcBulkTransfers(this.bank);
        final var legs = countries(4);
        final var e1 = assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, UUID.randomUUID(), "npcbulk-cccccccccccc", legs, 100, 400, 4, "pomoc", false, null));
        assertTrue(e1.getMessage().contains("Tylko WorldSteward"));
        assertThrows(IllegalArgumentException.class, () -> bulk.execute(l, null, "npcbulk-cccccccccccc", legs, 100, 400, 4, "pomoc", false, null));
        final var e2 = assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "npcbulk-cccccccccccc", List.of(), 100, 0, 0, "pomoc", false, null));
        assertTrue(e2.getMessage().contains("Brak państw NPC"));
        final var e3 = assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "npcbulk-cccccccccccc", legs, 3_000, 12_000, 4, "pomoc", false, null));
        assertTrue(e3.getMessage().contains("Za mało środków"), e3.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "npcbulk-cccccccccccc", legs, 100, 400, 3, "pomoc", false, null), "count changed");
        assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "npcbulk-cccccccccccc", legs, 100, 100, 4, "pomoc", false, null), "sum not confirmed");
        assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "npcbulk-cccccccccccc", legs, 100, 400, 4, "x", false, null), "reason");
        assertThrows(IllegalArgumentException.class,
            () -> bulk.execute(l, WorldMoney.MANAGER, "BAD KEY", legs, 100, 400, 4, "pomoc", false, null), "key");
        assertEquals(10_000, l.balance(WorldMoney.RESERVE), "nothing moved");
        assertTrue(this.journal.isEmpty());
        assertEquals(null, bulk.op("npcbulk-cccccccccccc"), "a refused operation leaves no record");
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void ledgerBatchIsAllOrNothing() {
        final Ledger l = this.ledger(1_000);
        final var targets = new java.util.LinkedHashMap<String, Long>();
        targets.put("country:a", 600L);
        targets.put("country:b", 600L);
        assertEquals(TxResult.INSUFFICIENT_FUNDS, l.allocateWorldMoneyAll(WorldMoney.MANAGER, targets, a -> "x"));
        assertEquals(1_000, l.balance(WorldMoney.RESERVE));
        assertEquals(0, l.balance("country:a"), "no partial move");
        targets.put(WorldMoney.CASH, 1L);
        assertEquals(TxResult.INVALID_ACCOUNT, l.allocateWorldMoneyAll(WorldMoney.MANAGER, targets, a -> "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.allocateWorldMoneyAll(UUID.randomUUID(), new java.util.LinkedHashMap<>(Map.of("country:a", 1L)), a -> "x"));
        assertTrue(this.journal.isEmpty());
    }

    @Test
    void interruptedOperationIsDecidedFromTheLedgerAndRetryPaysOnlyMissingLegs() throws Exception {
        final Ledger l = this.ledger(1_000_000);
        final NpcBulkTransfers bulk = new NpcBulkTransfers(this.bank);
        final var legs = countries(5);
        bulk.execute(l, WorldMoney.MANAGER, "npcbulk-dddddddddddd", legs, 1_000, 5_000, 5, "pomoc", false, null);
        // crash: the record stays PENDING and only the first 3 moves reached the journal on disk
        final List<TxRecord> onDisk = List.copyOf(this.journal.subList(0, 3));
        this.persistJournal(onDisk);
        final Ledger afterCrash = this.ledger(1_000_000 - 3_000);
        final NpcBulkTransfers restarted = new NpcBulkTransfers(this.bank);
        assertTrue(restarted.needsEvidence("npcbulk-dddddddddddd"));
        final Set<String> tagged = restarted.evidence("npcbulk-dddddddddddd");
        assertEquals(3, tagged.size());
        final var e = assertThrows(IllegalArgumentException.class,
            () -> restarted.execute(afterCrash, WorldMoney.MANAGER, "npcbulk-dddddddddddd", legs, 1_000, 5_000, 5, "pomoc", false, tagged));
        assertTrue(e.getMessage().contains("3 z 5"), e.getMessage());
        this.journal.clear();
        final var r = restarted.execute(afterCrash, WorldMoney.MANAGER, "npcbulk-dddddddddddd", legs, 1_000, 5_000, 5, "pomoc", true, tagged);
        assertEquals(2, r.get("paidNow"));
        assertEquals(2, this.journal.size(), "only the missing legs");
        assertEquals(1_000_000 - 5_000, afterCrash.balance(WorldMoney.RESERVE));
        // fully journaled before the crash: becomes DONE without moving anything
        final NpcBulkTransfers again = new NpcBulkTransfers(this.bank);
        final List<TxRecord> all = new ArrayList<>(onDisk);
        all.addAll(this.journal);
        this.persistJournal(all);
        final long reserve = afterCrash.balance(WorldMoney.RESERVE);
        final var done = again.execute(afterCrash, WorldMoney.MANAGER, "npcbulk-dddddddddddd", legs, 1_000, 5_000, 5, "pomoc", false,
            again.evidence("npcbulk-dddddddddddd"));
        assertEquals(true, done.get("replayed"));
        assertEquals("DONE", again.op("npcbulk-dddddddddddd").state());
        assertEquals(reserve, afterCrash.balance(WorldMoney.RESERVE));
    }
}
