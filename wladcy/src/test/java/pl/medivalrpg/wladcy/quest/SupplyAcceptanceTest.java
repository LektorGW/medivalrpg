// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Starts with country funds and an EMPTY escrow; exercises the same reservation method as QuestService.accept. */
class SupplyAcceptanceTest {
    private static final UUID PLAYER = SupplyDeliveryBridgeTest.PLAYER, COUNTRY = SupplyDeliveryBridgeTest.COUNTRY;
    private static final String UID = "acceptance-stable-uid";

    public static final class Bank {
        CompletableFuture<Void> ack = CompletableFuture.completedFuture(null);
        int calls;
        public CompletableFuture<Void> bankDurable() { this.calls++; return this.ack; }
    }

    private static final class Harness {
        final Gson gson = new Gson();
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
        QuestBook book = new QuestBook(this.clock, null);
        final Bank bank = new Bank();
        final Map<String, Long> balances = new HashMap<>();
        String durable;
        SupplyAcceptance.Phase failAt;
        int transfers;
        boolean throwAfterTransfer;
        final SkarbiecApi economy = (SkarbiecApi) Proxy.newProxyInstance(SkarbiecApi.class.getClassLoader(), new Class<?>[]{SkarbiecApi.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "balance" -> this.balances.getOrDefault((String) args[0], 0L);
                case "transfer" -> {
                    String from = (String) args[0], to = (String) args[1]; long amount = (long) args[2];
                    assertEquals(SkarbiecApi.countryAccount(COUNTRY), from);
                    assertEquals(QuestService.escrowAccount(UID), to);
                    assertNotNull(this.durable, "NO transfer before a durable UID exists");
                    var recorded = this.gson.fromJson(this.durable, QuestData.class).active.get(PLAYER.toString()).getFirst();
                    assertEquals(UID, recorded.uid); assertEquals(SupplyAcceptance.Phase.FUNDING, recorded.acceptancePhase);
                    assertEquals(100, recorded.reward); assertEquals("OAK_LOG", recorded.material); assertEquals(32, recorded.amount);
                    long have = this.balances.getOrDefault(from, 0L);
                    if (have < amount) yield TxResult.INSUFFICIENT_FUNDS;
                    this.transfers++; this.balances.put(from, have - amount); this.balances.merge(to, amount, Long::sum);
                    if (this.throwAfterTransfer) throw new IllegalStateException("lost response after atomic bank move");
                    yield TxResult.OK;
                }
                default -> throw new AssertionError("Unexpected economic action: " + method.getName());
            };
        });

        Harness() { this.balances.put(SkarbiecApi.countryAccount(COUNTRY), 1000L); }

        QuestData.Offer offer(QuestType type) {
            var offer = new QuestData.Offer(); offer.id = "offer"; offer.country = COUNTRY.toString(); offer.type = type.key();
            offer.material = "OAK_LOG"; offer.amount = 32; offer.timeLimitMinutes = 120;
            return offer;
        }
        QuestData.Active prepare(QuestType type) { return this.book.prepareFundedAcceptance(PLAYER, "Jan", this.offer(type), "Osada", 100, UID); }
        QuestData.Active active() { return this.book.active(PLAYER).getFirst(); }
        CompletableFuture<Void> checkpoint() {
            if (this.active().acceptancePhase == this.failAt) return CompletableFuture.failedFuture(new IllegalStateException("disk"));
            this.durable = this.gson.toJson(this.book.snapshot());
            return CompletableFuture.completedFuture(null);
        }
        SupplyAcceptance coordinator() { return new SupplyAcceptance(this.economy, new BankCheckpointBridge(() -> this.bank), this::checkpoint, this.clock); }
        void restart() { this.book = new QuestBook(this.clock, this.gson.fromJson(this.durable, QuestData.class)); }
        long countryFunds() { return this.balances.getOrDefault(SkarbiecApi.countryAccount(COUNTRY), 0L); }
        long escrowFunds() { return this.balances.getOrDefault(QuestService.escrowAccount(UID), 0L); }
    }

    @Test void everyFundedQuestPersistsItsUidBeforeMovingMoneyAndReservesOneOfferSlot() {
        for (QuestType type : QuestType.values()) {
            var h = new Harness(); var quest = h.prepare(type);
            assertEquals(0, h.transfers); assertEquals(0, h.escrowFunds()); assertTrue(SupplyAcceptance.pending(quest));
            var retry = h.book.prepareFundedAcceptance(PLAYER, "Jan", h.offer(type), "Osada", 100, "another-unused-uid");
            assertSame(quest, retry); assertEquals(1, h.book.active(PLAYER).size());
            assertTrue(h.book.used(PLAYER, "offer")); assertFalse(h.book.complete(quest, 100));
            assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(quest).status());
            assertEquals(1, h.transfers); assertEquals(900, h.countryFunds()); assertEquals(100, h.escrowFunds());
            assertTrue(h.bank.calls > 0); assertEquals(SupplyAcceptance.Phase.ACCEPTED, h.active().acceptancePhase);
            assertTrue(h.active().acceptanceNoticePending, "only the service may announce after the ACCEPTED checkpoint succeeded");
        }
    }

    @Test void failedFirstCheckpointNeverDebitsCountryAndFailedFundingIntentRecoversSameUid() {
        var h = new Harness(); var quest = h.prepare(QuestType.SUROWCE);
        h.failAt = SupplyAcceptance.Phase.INTENT;
        assertEquals(SupplyAcceptance.Status.UNAVAILABLE, h.coordinator().advance(quest).status());
        assertNull(h.durable); assertEquals(0, h.transfers); assertEquals(1000, h.countryFunds());
        h.failAt = SupplyAcceptance.Phase.FUNDING;
        assertEquals(SupplyAcceptance.Status.UNAVAILABLE, h.coordinator().advance(quest).status());
        assertEquals(0, h.transfers); assertNotNull(h.durable);
        h.restart(); h.failAt = null;
        assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(h.active()).status());
        assertEquals(UID, h.active().uid); assertEquals(1, h.transfers);
    }

    @Test void crashAfterTransferBeforeBankAckRecoversFullEscrowWithoutAnotherDebit() {
        var h = new Harness(); var quest = h.prepare(QuestType.SUROWCE); h.bank.ack = new CompletableFuture<>();
        assertEquals(SupplyAcceptance.Status.PENDING, h.coordinator().advance(quest).status());
        assertEquals(SupplyAcceptance.Phase.FUNDING, quest.acceptancePhase); assertEquals(1, h.transfers);
        h.restart(); h.bank.ack = CompletableFuture.completedFuture(null);
        assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(h.active()).status());
        assertEquals(UID, h.active().uid); assertEquals(1, h.transfers);
        assertEquals(900, h.countryFunds()); assertEquals(100, h.escrowFunds());
    }

    @Test void bankReplayWithNoPersistedTransferFundsOnlyTheSameDurableIntent() {
        var h = new Harness(); var quest = h.prepare(QuestType.DOSTAWA); h.bank.ack = new CompletableFuture<>();
        assertEquals(SupplyAcceptance.Status.PENDING, h.coordinator().advance(quest).status());
        // Simulate bank recovery to the atomic state BEFORE the unacknowledged move.
        h.balances.put(SkarbiecApi.countryAccount(COUNTRY), 1000L); h.balances.remove(QuestService.escrowAccount(UID));
        h.restart(); h.bank.ack = CompletableFuture.completedFuture(null);
        assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(h.active()).status());
        assertEquals(UID, h.active().uid); assertEquals(900, h.countryFunds()); assertEquals(100, h.escrowFunds());
    }

    @Test void lostTransferResponseIsRecoveredByTheAlreadyFundedEscrow() {
        var h = new Harness(); var quest = h.prepare(QuestType.TRANSPORT); h.throwAfterTransfer = true;
        assertEquals(SupplyAcceptance.Status.UNAVAILABLE, h.coordinator().advance(quest).status());
        h.restart(); h.throwAfterTransfer = false;
        assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(h.active()).status());
        assertEquals(1, h.transfers); assertEquals(900, h.countryFunds());
    }

    @Test void acceptanceWaitsForBankAndFinalQuestCheckpointAndStartsItsDeadlineAfterFunding() {
        var h = new Harness(); var quest = h.prepare(QuestType.SUROWCE); var coordinator = h.coordinator();
        h.bank.ack = new CompletableFuture<>();
        assertEquals(SupplyAcceptance.Status.PENDING, coordinator.advance(quest).status());
        h.clock.advanceMillis(3 * 60 * 60_000L); assertTrue(h.book.expired().isEmpty());
        h.bank.ack.complete(null); h.failAt = SupplyAcceptance.Phase.ACCEPTED;
        assertEquals(SupplyAcceptance.Status.UNAVAILABLE, coordinator.advance(quest).status());
        assertTrue(SupplyAcceptance.pending(quest));
        long acceptedAt = quest.acceptedAt; assertEquals(h.clock.millis(), acceptedAt);
        assertEquals(SupplyAcceptance.Phase.CONFIRMING, h.gson.fromJson(h.durable, QuestData.class).active.get(PLAYER.toString()).getFirst().acceptancePhase);
        h.restart(); h.failAt = null; h.clock.advanceMillis(20 * 60_000L);
        assertEquals(SupplyAcceptance.Status.ACCEPTED, h.coordinator().advance(h.active()).status());
        assertEquals(acceptedAt, h.active().acceptedAt); assertEquals(acceptedAt + 120 * 60_000L, h.active().deadline);
        assertEquals(1, h.transfers);
    }

    @Test void failedBankAckNeverConfirmsAcceptanceAndRetryDoesNotFundAgain() {
        var h = new Harness(); var quest = h.prepare(QuestType.SUROWCE); var coordinator = h.coordinator();
        h.bank.ack = CompletableFuture.failedFuture(new IllegalStateException("bank disk"));
        assertEquals(SupplyAcceptance.Status.UNAVAILABLE, coordinator.advance(quest).status());
        assertEquals(SupplyAcceptance.Phase.FUNDING, quest.acceptancePhase); assertEquals(1, h.transfers);
        h.bank.ack = CompletableFuture.completedFuture(null);
        assertEquals(SupplyAcceptance.Status.ACCEPTED, coordinator.advance(quest).status()); assertEquals(1, h.transfers);
    }

    @Test void partialEscrowAndChangedPayloadQuarantineTheIntentWithoutAnotherFundingMove() {
        var h = new Harness(); var quest = h.prepare(QuestType.SUROWCE); h.bank.ack = new CompletableFuture<>();
        h.coordinator().advance(quest); h.restart();
        h.balances.put(QuestService.escrowAccount(UID), 33L);
        assertEquals(SupplyAcceptance.Status.AUDIT, h.coordinator().advance(h.active()).status()); assertEquals(1, h.transfers);
        assertTrue(SupplySettlement.protectedFromCancellation(h.active())); assertFalse(h.book.cancel(h.active()));
        var changed = new Harness(); var intent = changed.prepare(QuestType.SUROWCE); changed.failAt = SupplyAcceptance.Phase.FUNDING;
        changed.coordinator().advance(intent); changed.restart(); changed.active().reward++;
        assertEquals(SupplyAcceptance.Status.AUDIT, changed.coordinator().advance(changed.active()).status()); assertEquals(0, changed.transfers);
    }
}
