// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.wladcy.testutil.TestSupport;

class SupplySettlementTest {
    /** Simulates provider-owned physical movement and receipt replay, not Wladcy item deletion. */
    public static final class Warehouse {
        String status = "PENDING";
        int playerItems = 32, warehouseItems, calls;
        final List<String> ids = new ArrayList<>();
        final Map<String, String> delivered = new HashMap<>();
        String replayStatus = "DELIVERED";
        String replayReceipt;
        public Map<String, Object> supplyDelivery(UUID player, UUID country, String id, String material, int amount) {
            this.calls++; this.ids.add(id);
            String payload = player + "|" + country + "|" + material + "|" + amount;
            if (this.delivered.containsKey(id)) {
                if (!payload.equals(this.delivered.get(id))) return Map.of("status", "BLOCKED", "reason", "conflict");
                return Map.of("status", this.replayStatus, "receipt", this.replayReceipt == null ? "receipt:" + id : this.replayReceipt);
            }
            if (this.status.equals("DELIVERED")) {
                if (this.playerItems < amount) return Map.of("status", "BLOCKED", "reason", "missing");
                this.playerItems -= amount; this.warehouseItems += amount; this.delivered.put(id, payload);
                return Map.of("status", "DELIVERED", "receipt", "receipt:" + id);
            }
            return Map.of("status", this.status, "reason", "warehouse " + this.status);
        }
    }

    private static final class Economy {
        final Map<String, Long> balances = new HashMap<>();
        int transfers, paid;
        boolean reject;
        final Bank bank = new Bank();
        final SkarbiecApi api = (SkarbiecApi) Proxy.newProxyInstance(SkarbiecApi.class.getClassLoader(), new Class<?>[]{SkarbiecApi.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "balance" -> this.balances.getOrDefault((String) args[0], 0L);
                case "transfer" -> {
                    this.transfers++;
                    String from = (String) args[0], to = (String) args[1]; long amount = (long) args[2];
                    assertTrue(from.startsWith("wladcy:zlecenie-"), "no fallback country debit");
                    long have = this.balances.getOrDefault(from, 0L);
                    if (this.reject || have < amount) yield TxResult.INSUFFICIENT_FUNDS;
                    this.balances.put(from, have - amount); this.balances.merge(to, amount, Long::sum); this.paid++;
                    yield TxResult.OK;
                }
                default -> throw new AssertionError("Unexpected economic operation (mint/refund): " + method.getName());
            };
        });
        void fund(QuestData.Active quest) { this.balances.put(QuestService.escrowAccount(quest.uid), quest.reward); }
    }

    public static final class Bank {
        int calls;
        CompletableFuture<Void> funding = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> payment = CompletableFuture.completedFuture(null);
        public CompletableFuture<Void> bankDurable() {
            this.calls++;
            return this.calls == 1 ? this.funding : this.payment;
        }
    }

    private static QuestData.Active quest() {
        var quest = new QuestData.Active(); quest.uid = "supply-test"; quest.offerId = "offer";
        quest.player = SupplyDeliveryBridgeTest.PLAYER.toString(); quest.country = SupplyDeliveryBridgeTest.COUNTRY.toString();
        quest.type = QuestType.SUROWCE.key(); quest.material = "OAK_LOG"; quest.amount = 32;
        quest.reward = 100; quest.escrow = true; quest.supplyDelivery = true;
        return quest;
    }

    private static QuestData.Active copy(QuestData.Active quest) {
        var gson = new Gson(); return gson.fromJson(gson.toJson(quest), QuestData.Active.class);
    }

    private static SupplySettlement settlement(Warehouse warehouse, Economy economy, SupplySettlement.Checkpoint checkpoint) {
        return new SupplySettlement(new SupplyDeliveryBridge(() -> warehouse), economy.api,
            new BankCheckpointBridge(() -> economy.bank), checkpoint);
    }

    private static CompletableFuture<Void> saved() { return CompletableFuture.completedFuture(null); }

    @Test void pendingBlockedAndUnavailableKeepItemsAndRewardUntouched() {
        for (String status : List.of("PENDING", "BLOCKED", "UNAVAILABLE", "UNKNOWN")) {
            var quest = quest(); var warehouse = new Warehouse(); warehouse.status = status;
            var economy = new Economy(); economy.fund(quest);
            var state = settlement(warehouse, economy, SupplySettlementTest::saved);
            assertNotEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
            assertEquals(32, warehouse.playerItems); assertEquals(0, warehouse.warehouseItems); assertEquals(0, economy.transfers);
            assertEquals(100L, economy.balances.get(QuestService.escrowAccount(quest.uid)));
        }
    }

    @Test void retryUsesSameIdAndDeliveredReplayPaysOnlyOnceIncludingAfterReload() {
        var quest = quest(); var warehouse = new Warehouse(); var economy = new Economy(); economy.fund(quest);
        var state = settlement(warehouse, economy, SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        warehouse.status = "DELIVERED";
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(SupplySettlement.Status.PAID, settlement(warehouse, economy, SupplySettlementTest::saved).advance(copy(quest)).status());
        assertEquals(List.of("wladcy:supply-test", "wladcy:supply-test"), warehouse.ids);
        assertEquals(0, warehouse.playerItems); assertEquals(32, warehouse.warehouseItems);
        assertEquals(1, economy.paid); assertEquals(1, economy.transfers);
        assertEquals(100L, economy.balances.get(SkarbiecApi.playerAccount(SupplyDeliveryBridgeTest.PLAYER)));
    }

    @Test void failedCheckpointBeforeSubmissionNeverInvokesProvider() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest);
        var state = settlement(warehouse, economy, () -> CompletableFuture.failedFuture(new IllegalStateException("disk")));
        assertEquals(SupplySettlement.Status.UNAVAILABLE, state.advance(quest).status());
        assertEquals(0, warehouse.calls); assertEquals(0, economy.transfers); assertEquals(32, warehouse.playerItems);
    }

    @Test void receiptMustBeCheckpointedBeforeMoneyMovesAndRetryDoesNotRedeliver() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); boolean[] fail = {true};
        var state = settlement(warehouse, economy, () -> quest.supplyPhase == SupplySettlement.Phase.DELIVERED && fail[0]
            ? CompletableFuture.failedFuture(new IllegalStateException("disk")) : saved());
        assertEquals(SupplySettlement.Status.UNAVAILABLE, state.advance(quest).status());
        assertEquals(32, warehouse.warehouseItems); assertEquals(0, economy.transfers);
        fail[0] = false;
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(2, warehouse.calls, "retry must freshly verify the provider before payout"); assertEquals(1, economy.paid);
    }

    @Test void crashAfterTransferBeforeCheckpointQuarantinesEmptyEscrowInsteadOfChargingAgain() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); QuestData.Active[] durable = {null};
        var state = settlement(warehouse, economy, () -> {
            if (quest.supplyPhase == SupplySettlement.Phase.PAID) return CompletableFuture.failedFuture(new IllegalStateException("disk"));
            durable[0] = copy(quest); return saved();
        });
        assertEquals(SupplySettlement.Status.UNAVAILABLE, state.advance(quest).status());
        assertEquals(1, economy.paid); assertEquals(SupplySettlement.Phase.PAYING, durable[0].supplyPhase);
        assertEquals(SupplySettlement.Status.AUDIT,
            settlement(warehouse, economy, SupplySettlementTest::saved).advance(durable[0]).status());
        assertEquals(1, economy.transfers); assertEquals(2, warehouse.calls, "resume rechecks the receipt before rejecting an empty escrow");
    }

    @Test void failedPaymentWaitsAndRetriesOnlyTheExistingEscrow() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); economy.reject = true;
        var state = settlement(warehouse, economy, SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.PAYMENT, state.advance(quest).status());
        assertEquals(0, economy.paid); assertEquals(32, warehouse.warehouseItems);
        economy.reject = false;
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(1, economy.paid); assertEquals(2, warehouse.calls, "each new payout attempt needs fresh delivery authorization");
    }

    @Test void changedPayloadAndMissingFundingStopBeforeAnyFurtherItemOrMoneyOperation() {
        var quest = quest(); var warehouse = new Warehouse(); var economy = new Economy(); economy.fund(quest);
        var state = settlement(warehouse, economy, SupplySettlementTest::saved);
        state.advance(quest); quest.amount++;
        assertEquals(SupplySettlement.Status.AUDIT, state.advance(quest).status());
        assertEquals(1, warehouse.calls); assertEquals(0, economy.transfers);
        var empty = quest();
        assertEquals(SupplySettlement.Status.AUDIT,
            settlement(new Warehouse(), new Economy(), SupplySettlementTest::saved).advance(empty).status());
    }

    @Test void asyncCheckpointMustFinishBeforeProviderInvocation() {
        var quest = quest(); var warehouse = new Warehouse(); var economy = new Economy(); economy.fund(quest);
        var writes = new ArrayList<CompletableFuture<Void>>();
        var state = settlement(warehouse, economy, () -> { var next = new CompletableFuture<Void>(); writes.add(next); return next; });
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        assertEquals(0, warehouse.calls);
        writes.getFirst().complete(null);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        assertEquals(0, warehouse.calls);
        writes.getLast().complete(null);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        assertEquals(0, warehouse.calls, "REQUESTED must be persisted after the funding bank acknowledgement");
        writes.getLast().complete(null);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        assertEquals(1, warehouse.calls); assertEquals(0, economy.transfers);
    }

    @Test void inFlightDeliveriesCannotExpireBeAbandonedOrCancelAndCompleteOnlyOnceAfterPaid() {
        var clock = new TestSupport.MutableClock(Instant.parse("2026-10-28T10:00:00Z")); var book = new QuestBook(clock, null);
        var offer = new QuestData.Offer(); offer.id = "test"; offer.country = SupplyDeliveryBridgeTest.COUNTRY.toString();
        offer.type = QuestType.SUROWCE.key(); offer.amount = 32; offer.material = "OAK_LOG"; offer.timeLimitMinutes = 1;
        var quest = book.accept(SupplyDeliveryBridgeTest.PLAYER, "Jan", offer, "Osada", 100, true, "supply-test");
        quest.supplyDelivery = true; quest.supplyPhase = SupplySettlement.Phase.REQUESTED;
        clock.advanceMillis(120_000);
        assertTrue(book.expired().isEmpty()); assertFalse(book.fail(quest, 30)); assertFalse(book.cancel(quest));
        assertFalse(book.complete(quest, 100));
        quest.supplyPhase = SupplySettlement.Phase.PAID;
        assertFalse(book.complete(quest, 0)); assertTrue(book.complete(quest, 100)); assertFalse(book.complete(quest, 100));
        assertEquals(1, book.completedToday(SupplyDeliveryBridgeTest.PLAYER)); assertEquals(100, book.paidToday(SupplyDeliveryBridgeTest.COUNTRY));
    }

    @Test void newSupplyCannotUseLegacyMintAndOldJsonKeepsLegacyBehavior() {
        var config = QuestGeneratorTest.cfg(Map.of("zlecenia.reward.create-money", true));
        assertFalse(QuestRules.quote(100, RepTier.OBCY, config, 0, 0).affordable(), "the legacy mint switch is ignored (closed pool)");
        assertFalse(QuestRules.quoteFunded(100, RepTier.OBCY, config, 0, 0).affordable());
        assertTrue(QuestRules.quoteFunded(100, RepTier.OBCY, config, 10_000, 0).affordable());
        var old = new Gson().fromJson("{\"uid\":\"old\",\"type\":\"surowce\"}", QuestData.Active.class);
        assertFalse(old.supplyDelivery); assertFalse(SupplySettlement.protectedFromCancellation(old));
        var unknown = new Gson().fromJson("{\"type\":\"unknown-old-type\"}", QuestData.Active.class);
        assertEquals(QuestType.DOSTAWA, unknown.type()); assertFalse(unknown.supplyDelivery);
    }

    @Test void fundingBankBarrierMustCompleteBeforeCallingWarehouse() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); economy.bank.funding = new CompletableFuture<>();
        var state = settlement(warehouse, economy, SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        assertEquals(SupplySettlement.Phase.FUNDING, quest.supplyPhase);
        assertEquals(0, warehouse.calls); assertEquals(0, economy.transfers);
        economy.bank.funding.complete(null);
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(1, economy.paid);
    }

    @Test void paidPhaseIsNeverCheckpointedUntilBankAcknowledgesTheActualTransfer() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); economy.bank.payment = new CompletableFuture<>();
        var phases = new ArrayList<SupplySettlement.Phase>();
        var state = settlement(warehouse, economy, () -> { phases.add(quest.supplyPhase); return saved(); });
        assertEquals(SupplySettlement.Status.PAYMENT, state.advance(quest).status());
        assertEquals(1, economy.paid); assertEquals(SupplySettlement.Phase.PAYING, quest.supplyPhase);
        assertFalse(phases.contains(SupplySettlement.Phase.PAID));
        assertEquals(SupplySettlement.Status.PAYMENT, state.advance(quest).status());
        assertEquals(1, economy.transfers);
        economy.bank.payment.complete(null);
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertTrue(phases.contains(SupplySettlement.Phase.PAID)); assertEquals(1, economy.transfers);
    }

    @Test void bankAcknowledgementFailureRetriesOnlyTheBarrierNeverTheTransfer() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest);
        economy.bank.payment = CompletableFuture.failedFuture(new IllegalStateException("bank disk"));
        var state = settlement(warehouse, economy, SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.UNAVAILABLE, state.advance(quest).status());
        assertEquals(SupplySettlement.Phase.PAYING, quest.supplyPhase); assertEquals(1, economy.paid);
        economy.bank.payment = saved();
        assertEquals(SupplySettlement.Status.PAID, state.advance(quest).status());
        assertEquals(1, economy.transfers); assertEquals(1, warehouse.calls);
    }

    @Test void blockedWithARegisteredReceiptStaysProtectedInsteadOfRefundingAnAmbiguousDelivery() {
        var quest = quest(); var economy = new Economy(); economy.fund(quest);
        var provider = new SupplyDeliveryBridgeTest.Provider();
        provider.response = Map.of("status", "BLOCKED", "receipt", "registered-receipt", "reason", "audit");
        var state = new SupplySettlement(new SupplyDeliveryBridge(() -> provider), economy.api,
            new BankCheckpointBridge(() -> economy.bank), SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.AUDIT, state.advance(quest).status());
        assertTrue(SupplySettlement.protectedFromCancellation(quest)); assertEquals(0, economy.transfers);
    }

    @Test void pendingReceiptCannotChangeIdentityOnARepeatedRequest() {
        var quest = quest(); var economy = new Economy(); economy.fund(quest);
        var provider = new SupplyDeliveryBridgeTest.Provider();
        provider.response = Map.of("status", "PENDING", "receipt", "first", "reason", "wait");
        var state = new SupplySettlement(new SupplyDeliveryBridge(() -> provider), economy.api,
            new BankCheckpointBridge(() -> economy.bank), SupplySettlementTest::saved);
        assertEquals(SupplySettlement.Status.PENDING, state.advance(quest).status());
        provider.response = Map.of("status", "DELIVERED", "receipt", "different", "reason", "done");
        assertEquals(SupplySettlement.Status.AUDIT, state.advance(quest).status()); assertEquals(0, economy.transfers);
    }

    @Test void reloadedDeliveredOrPayingCannotPayWhenProviderDowngradesTheReceipt() {
        for (var phase : List.of(SupplySettlement.Phase.DELIVERED, SupplySettlement.Phase.PAYING)) {
            for (String downgraded : List.of("PENDING", "BLOCKED", "UNAVAILABLE")) {
                var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
                var economy = new Economy(); economy.fund(quest); economy.reject = true;
                assertEquals(SupplySettlement.Status.PAYMENT, settlement(warehouse, economy, SupplySettlementTest::saved).advance(quest).status());
                quest.supplyPhase = phase;
                var reloaded = copy(quest);
                warehouse.replayStatus = downgraded; economy.reject = false;
                int transfers = economy.transfers, polls = warehouse.calls;
                var resumed = settlement(warehouse, economy, SupplySettlementTest::saved);
                assertNotEquals(SupplySettlement.Status.PAID, resumed.advance(reloaded).status(), phase + " / " + downgraded);
                assertEquals(transfers, economy.transfers); assertTrue(warehouse.calls > polls);
                assertEquals(100L, economy.balances.get(QuestService.escrowAccount(quest.uid)));
                assertTrue(SupplySettlement.protectedFromCancellation(reloaded));
                warehouse.replayStatus = "DELIVERED";
                assertEquals(SupplySettlement.Status.PAID, resumed.advance(reloaded).status());
                assertEquals(1, economy.paid); assertEquals(32, warehouse.warehouseItems);
            }
        }
    }

    @Test void reloadedPayoutRejectsAnotherReceiptEvenWhenProviderSaysDelivered() {
        var quest = quest(); var warehouse = new Warehouse(); warehouse.status = "DELIVERED";
        var economy = new Economy(); economy.fund(quest); economy.reject = true;
        settlement(warehouse, economy, SupplySettlementTest::saved).advance(quest);
        warehouse.replayReceipt = "another-receipt"; economy.reject = false;
        assertEquals(SupplySettlement.Status.AUDIT, settlement(warehouse, economy, SupplySettlementTest::saved).advance(copy(quest)).status());
        assertEquals(0, economy.paid); assertEquals(100L, economy.balances.get(QuestService.escrowAccount(quest.uid)));
    }
}
