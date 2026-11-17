// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

class LedgerTest {

    private static final String A = SkarbiecApi.playerAccount(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final String B = SkarbiecApi.playerAccount(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final String K = SkarbiecApi.countryAccount(UUID.fromString("00000000-0000-0000-0000-0000000000cc"));

    /** Records everything the ledger hands to its sink. */
    private static final class Recorder implements Ledger.Sink {
        final ConcurrentLinkedQueue<TxRecord> records = new ConcurrentLinkedQueue<>();

        @Override
        public void record(final TxRecord record) {
            this.records.add(record);
        }

        @Override
        public void snapshot(final Map<String, Long> balances, final long seq) {
        }
    }

    @Test
    void unknownAccountHasZeroAndIsCreatedOnDeposit() {
        final Ledger l = new Ledger(Ledger.Sink.NONE);
        assertEquals(0, l.balance(A));
        assertFalse(l.exists(A));
        assertEquals(TxResult.OK, l.deposit(A, 150, "test"));
        assertTrue(l.exists(A));
        assertEquals(150, l.balance(A));
    }

    @Test
    void transferMovesMoneyAtomically() {
        final Recorder rec = new Recorder();
        final Ledger l = new Ledger(rec);
        l.deposit(A, 500, "start");
        assertEquals(TxResult.OK, l.transfer(A, K, 120, "podatek"));
        assertEquals(380, l.balance(A));
        assertEquals(120, l.balance(K));
        final TxRecord last = new ArrayList<>(rec.records).get(1);
        assertEquals(TxRecord.Type.TRANSFER, last.type());
        assertEquals(2, last.seq());
        assertEquals(380L, last.fromAfter());
        assertEquals(120L, last.toAfter());
        assertEquals("podatek", last.reason());
    }

    @Test
    void insufficientFundsChangesNothing() {
        final Recorder rec = new Recorder();
        final Ledger l = new Ledger(rec);
        l.deposit(A, 100, "start");
        assertEquals(TxResult.INSUFFICIENT_FUNDS, l.withdraw(A, 101, "x"));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, l.transfer(A, B, 101, "x"));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, l.withdraw(B, 1, "x"));
        assertEquals(100, l.balance(A));
        assertEquals(0, l.balance(B));
        assertFalse(l.exists(B), "a failed transfer must not create the target account");
        assertEquals(1, rec.records.size());
        assertEquals(1, l.seq());
    }

    @Test
    void invalidAmountsAndAccounts() {
        final Ledger l = new Ledger(Ledger.Sink.NONE);
        l.deposit(A, 100, "start");
        assertEquals(TxResult.INVALID_AMOUNT, l.deposit(A, 0, "x"));
        assertEquals(TxResult.INVALID_AMOUNT, l.deposit(A, -5, "x"));
        assertEquals(TxResult.INVALID_AMOUNT, l.withdraw(A, -5, "x"));
        assertEquals(TxResult.INVALID_AMOUNT, l.transfer(A, B, 0, "x"));
        assertEquals(TxResult.INVALID_AMOUNT, l.transfer(A, A, 10, "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.deposit("bez-dwukropka", 10, "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.deposit(null, 10, "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.deposit("player:", 10, "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.deposit("player:a\tb", 10, "x"));
        assertEquals(TxResult.INVALID_ACCOUNT, l.transfer(A, "zle konto", 10, "x"));
        assertEquals(TxResult.OK, l.deposit("ludnosc:budowa-1", 10, "x"));
        assertEquals(100, l.balance(A));
    }

    @Test
    void overflowIsRejected() {
        final Ledger l = new Ledger(Ledger.Sink.NONE);
        assertEquals(TxResult.OK, l.deposit(A, Long.MAX_VALUE, "max"));
        assertEquals(TxResult.INVALID_AMOUNT, l.deposit(A, 1, "overflow"));
        l.deposit(B, 1, "b");
        assertEquals(TxResult.INVALID_AMOUNT, l.transfer(B, A, 1, "overflow"));
        assertEquals(Long.MAX_VALUE, l.balance(A));
        assertEquals(1, l.balance(B));
    }

    @Test
    void reasonIsSanitised() {
        final Recorder rec = new Recorder();
        final Ledger l = new Ledger(rec);
        l.deposit(A, 1, "a\tb\nc");
        assertEquals("a b c", rec.records.peek().reason());
    }

    /** Many threads transferring randomly: money is conserved, no balance goes negative, every op is logged once. */
    @Test
    void concurrentTransfersConserveMoney() throws Exception {
        final Recorder rec = new Recorder();
        final Ledger l = new Ledger(rec);
        final List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String acc = "test:konto" + i;
            accounts.add(acc);
            l.deposit(acc, 1_000, "start");
        }
        final int threads = 8;
        final int opsPerThread = 20_000;
        final AtomicLong ok = new AtomicLong();
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                final ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    final String from = accounts.get(r.nextInt(accounts.size()));
                    final String to = accounts.get(r.nextInt(accounts.size()));
                    if (l.transfer(from, to, 1 + r.nextInt(300), "los") == TxResult.OK) {
                        ok.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (final Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long sum = 0;
        for (final String acc : accounts) {
            assertTrue(l.balance(acc) >= 0, acc);
            sum += l.balance(acc);
        }
        assertEquals(10_000, sum);
        assertEquals(10 + ok.get(), l.seq());
        assertEquals(10 + ok.get(), rec.records.size());
        // Sequence numbers are unique and complete.
        final boolean[] seen = new boolean[(int) l.seq() + 1];
        for (final TxRecord r : rec.records) {
            assertFalse(seen[(int) r.seq()]);
            seen[(int) r.seq()] = true;
        }
    }

    /** Concurrent withdrawals racing for the same money: exactly as many succeed as the balance allows. */
    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        final Ledger l = new Ledger(Ledger.Sink.NONE);
        l.deposit(A, 1_000, "start");
        final AtomicLong ok = new AtomicLong();
        final ExecutorService pool = Executors.newFixedThreadPool(16);
        final CountDownLatch go = new CountDownLatch(1);
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                if (l.withdraw(A, 1, "race").ok()) {
                    ok.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (final Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1_000, ok.get());
        assertEquals(0, l.balance(A));
    }

    @Test
    void recordRoundTrip() {
        final TxRecord r = new TxRecord(42, 1_700_000_000_000L, TxRecord.Type.TRANSFER, A, K, 15, 85L, 15L, "podatek dzienny");
        assertEquals(r, TxRecord.decode(r.encode()));
        final TxRecord d = new TxRecord(43, 1_700_000_000_001L, TxRecord.Type.DEPOSIT, null, A, 5, null, 90L, "");
        assertEquals(d, TxRecord.decode(d.encode()));
        assertEquals(null, TxRecord.decode("43\t2026-01-01T00:00:00Z\tDEPOSIT\t-\tplayer:x\t5"));
        assertEquals(null, TxRecord.decode("zepsuta linia"));
    }
}
