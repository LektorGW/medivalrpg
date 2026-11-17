// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldGrantsTest {

    @TempDir
    Path bank;

    static final WorldGrants.Grant HOUSEHOLDS = WorldGrants.DEPLOYMENT.get(0);
    static final WorldGrants.Grant MERCHANT = WorldGrants.DEPLOYMENT.get(1);

    /** Closed ledger whose operations are written to bank/logs like LedgerStore does. */
    private Ledger ledger(final long reserve) {
        final Ledger l = new Ledger(new Ledger.Sink() {
            @Override
            public void record(final TxRecord r) {
                try {
                    Files.createDirectories(WorldGrantsTest.this.bank.resolve("logs"));
                    Files.writeString(WorldGrantsTest.this.bank.resolve("logs/transakcje-2026-10-29.log"), r.encode() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        });
        l.restore(Map.of(WorldMoney.RESERVE, reserve, WorldMoney.CASH, 0L, WorldMoney.PAYMENTS, 0L, WorldMoney.MERCHANT, 0L,
            WorldMoney.HOUSEHOLDS, 0L, "player:x", WorldMoney.CAP - reserve), 1);
        l.enableClosedSupply(WorldMoney.CAP, WorldMoney.MANAGER);
        return l;
    }

    @Test
    void userDecisionAmounts() {
        assertEquals(312_600, HOUSEHOLDS.amount());
        assertEquals(WorldMoney.HOUSEHOLDS, HOUSEHOLDS.account());
        assertEquals(50_000, MERCHANT.amount());
        assertEquals(WorldMoney.MERCHANT, MERCHANT.account());
    }

    @Test
    void grantsPayOnceFromTheReserveAndKeepThePool() throws IOException {
        final Ledger l = this.ledger(1_000_000);
        final WorldGrants g = new WorldGrants(this.bank);
        assertEquals("NEW", g.effective(HOUSEHOLDS, g.evidence()));
        g.apply(l, WorldMoney.MANAGER, HOUSEHOLDS, g.evidence(), false);
        g.markDone(HOUSEHOLDS.key());
        g.apply(l, WorldMoney.MANAGER, MERCHANT, g.evidence(), false);
        g.markDone(MERCHANT.key());
        assertEquals(312_600, l.balance(WorldMoney.HOUSEHOLDS));
        assertEquals(50_000, l.balance(WorldMoney.MERCHANT));
        assertEquals(1_000_000 - 362_600, l.balance(WorldMoney.RESERVE));
        assertEquals(WorldMoney.CAP, l.totalMoney());
        // second attempt — also after a restart (new instance reading grants.json) — is refused
        final WorldGrants again = new WorldGrants(this.bank);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> again.apply(l, WorldMoney.MANAGER, HOUSEHOLDS, again.evidence(), true));
        assertTrue(e.getMessage().contains("już wykonane"));
        assertEquals(312_600, l.balance(WorldMoney.HOUSEHOLDS));
    }

    @Test
    void pendingWithLedgerTagIsDoneEvenIfTheMarkerWasNotWritten() throws IOException {
        final Ledger l = this.ledger(1_000_000);
        final WorldGrants g = new WorldGrants(this.bank);
        g.apply(l, WorldMoney.MANAGER, HOUSEHOLDS, g.evidence(), false); // crash before markDone
        final WorldGrants restarted = new WorldGrants(this.bank);
        assertEquals("PENDING", restarted.stored(HOUSEHOLDS.key()));
        assertEquals("DONE", restarted.effective(HOUSEHOLDS, restarted.evidence()), "the ledger tag proves the move");
        assertThrows(IllegalArgumentException.class, () -> restarted.apply(l, WorldMoney.MANAGER, HOUSEHOLDS, restarted.evidence(), false));
        assertEquals(312_600, l.balance(WorldMoney.HOUSEHOLDS));
    }

    @Test
    void uncertainStateIsNeverRetriedAutomatically() throws IOException {
        final Ledger l = this.ledger(1_000_000);
        final WorldGrants g = new WorldGrants(this.bank);
        g.apply(l, WorldMoney.MANAGER, MERCHANT, g.evidence(), false);
        Files.delete(this.bank.resolve("logs/transakcje-2026-10-29.log")); // simulate a lost log line (power loss)
        final WorldGrants restarted = new WorldGrants(this.bank);
        assertEquals("UNCERTAIN", restarted.effective(MERCHANT, restarted.evidence()));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> restarted.apply(l, WorldMoney.MANAGER, MERCHANT, restarted.evidence(), false));
        assertTrue(e.getMessage().contains("niepewny"));
        restarted.apply(l, WorldMoney.MANAGER, MERCHANT, restarted.evidence(), true); // explicit owner decision
        assertEquals(100_000, l.balance(WorldMoney.MERCHANT));
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void aGrantTagInsideAnotherReasonIsNoEvidence() throws IOException {
        final Ledger l = this.ledger(1_000_000);
        // e.g. a company named after the tag gets a loan from the reserve, or the owner types the tag into a transfer reason
        assertTrue(l.allocateWorldMoney(WorldMoney.MANAGER, "firma-kredyt:0123456789", 1_000,
            "WorldSteward: kredyt Skarbu Świata dla firmy (" + HOUSEHOLDS.tag() + ")").ok());
        final WorldGrants g = new WorldGrants(this.bank);
        assertTrue(g.evidence().tags().isEmpty(), "only a reason written by WorldGrants.apply carries a grant tag");
        assertEquals("NEW", g.effective(HOUSEHOLDS, g.evidence()));
        g.apply(l, WorldMoney.MANAGER, HOUSEHOLDS, g.evidence(), false);
        assertEquals(312_600, l.balance(WorldMoney.HOUSEHOLDS));
        assertTrue(g.evidence().tags().contains(HOUSEHOLDS.tag()));
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void aFailedRetryOfAnUncertainGrantStaysUncertain() throws IOException {
        final Ledger l = this.ledger(1_000_000);
        final WorldGrants g = new WorldGrants(this.bank);
        g.apply(l, WorldMoney.MANAGER, MERCHANT, g.evidence(), false);
        Files.delete(this.bank.resolve("logs/transakcje-2026-10-29.log")); // lost log line → UNCERTAIN
        final WorldGrants restarted = new WorldGrants(this.bank);
        assertEquals("UNCERTAIN", restarted.effective(MERCHANT, restarted.evidence()));
        // a retry whose move the ledger refuses (here: a protected target) must not turn the grant into NEW
        final WorldGrants.Grant refused = new WorldGrants.Grant(MERCHANT.key(), WorldMoney.CASH, MERCHANT.amount(), "test");
        assertThrows(IllegalArgumentException.class, () -> restarted.apply(l, WorldMoney.MANAGER, refused, restarted.evidence(), true));
        assertEquals("PENDING", restarted.stored(MERCHANT.key()));
        assertEquals("PENDING", new WorldGrants(this.bank).stored(MERCHANT.key()), "also on disk");
        assertEquals("UNCERTAIN", restarted.effective(MERCHANT, restarted.evidence()));
        assertEquals(50_000, l.balance(WorldMoney.MERCHANT));
        assertEquals(WorldMoney.CAP, l.totalMoney());
    }

    @Test
    void refusalsLeaveNoTrace() throws IOException {
        final Ledger poor = this.ledger(100_000);
        final WorldGrants g = new WorldGrants(this.bank);
        assertEquals("Za mało środków w rezerwie Skarbu Świata.", assertThrows(IllegalArgumentException.class,
            () -> g.apply(poor, WorldMoney.MANAGER, HOUSEHOLDS, g.evidence(), false)).getMessage());
        assertEquals("NEW", g.stored(HOUSEHOLDS.key()));
        for (final UUID other : new UUID[] {UUID.randomUUID(), null}) {
            assertEquals("Tylko WorldSteward zarządza Skarbem Świata.", assertThrows(IllegalArgumentException.class,
                () -> g.apply(poor, other, MERCHANT, g.evidence(), false)).getMessage());
        }
        assertEquals("NEW", g.stored(MERCHANT.key()));
        assertEquals(WorldMoney.CAP, poor.totalMoney());
    }
}
