// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Rewards of NPC quests are always covered by real money of the NPC country (closed pool of 100 000 000 md): payout
 * only from the quest escrow, refunds back to the treasury, start-up coverage of legacy quests — and the sum of all
 * accounts never changes (no deposit/withdraw is ever called).
 */
class EscrowCoverageTest {

    private static final UUID COUNTRY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String TREASURY = SkarbiecApi.countryAccount(COUNTRY);
    private static final String WALLET = SkarbiecApi.playerAccount(PLAYER);

    /** Closed-pool bank: transfers only; the world reserve keeps the total at a fixed cap. */
    private static final class Bank {
        static final long CAP = 100_000_000L;
        final Map<String, Long> balances = new HashMap<>();
        int transfers;
        final SkarbiecApi api = (SkarbiecApi) Proxy.newProxyInstance(SkarbiecApi.class.getClassLoader(), new Class<?>[] {SkarbiecApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "balance" -> this.balances.getOrDefault((String) args[0], 0L);
                case "transfer" -> {
                    final String from = (String) args[0];
                    final String to = (String) args[1];
                    final long amount = (long) args[2];
                    final String reason = (String) args[3];
                    assertTrue(reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("zleceni"),
                        "readable reason with the quests category: " + reason);
                    final long have = this.balances.getOrDefault(from, 0L);
                    if (amount <= 0 || have < amount) {
                        yield TxResult.INSUFFICIENT_FUNDS;
                    }
                    this.balances.put(from, have - amount);
                    this.balances.merge(to, amount, Long::sum);
                    this.transfers++;
                    yield TxResult.OK;
                }
                default -> throw new AssertionError("money must never enter or leave the pool: " + method.getName());
            });

        Bank(final long treasury) {
            this.balances.put(TREASURY, treasury);
            this.balances.put("world:reserve", CAP - treasury);
        }

        long total() {
            return this.balances.values().stream().mapToLong(Long::longValue).sum();
        }

        long get(final String account) {
            return this.balances.getOrDefault(account, 0L);
        }

        /** What SupplyAcceptance does at acceptance: treasury → escrow of the stable UID. */
        QuestData.Active acceptFunded(final String uid, final long reward) {
            final QuestData.Active a = quest(uid, reward, true);
            assertEquals(TxResult.OK, this.api.transfer(TREASURY, QuestService.escrowAccount(uid), reward, "Depozyt zlecenia #x / " + uid));
            return a;
        }
    }

    private static QuestData.Active quest(final String uid, final long reward, final boolean escrow) {
        final QuestData.Active a = new QuestData.Active();
        a.uid = uid;
        a.offerId = "of" + uid;
        a.country = COUNTRY.toString();
        a.player = PLAYER.toString();
        a.reward = reward;
        a.escrow = escrow;
        return a;
    }

    @Test
    void turnInPaysOnceFromTheEscrowEvenAfterARestartBeforeTheCompletionWasSaved() {
        final Bank bank = new Bank(1_000);
        final QuestData.Active a = bank.acceptFunded("u1", 100);
        assertEquals(900, bank.get(TREASURY));
        assertTrue(EscrowCoverage.secured(a, bank.api), "the player sees a secured reward");
        assertEquals(100, EscrowCoverage.payout(a, bank.api, WALLET, "Nagroda za zlecenie #x"));
        // Crash before the completion is saved: the restored quest is still active and is turned in again.
        final QuestData.Active restored = new Gson().fromJson(new Gson().toJson(a), QuestData.Active.class);
        assertEquals(0, EscrowCoverage.payout(restored, bank.api, WALLET, "Nagroda za zlecenie #x"), "double turn-in pays nothing");
        assertEquals(100, bank.get(WALLET));
        assertEquals(900, bank.get(TREASURY), "the treasury never pays a second time");
        assertEquals(Bank.CAP, bank.total());
    }

    @Test
    void expiryAndAbandonReturnTheEscrowOnceAndKeepThePoolSum() {
        final Bank bank = new Bank(1_000);
        final QuestData.Active a = bank.acceptFunded("u2", 250);
        assertEquals(250, EscrowCoverage.refund(a, bank.api));
        assertEquals(0, EscrowCoverage.refund(a, bank.api), "idempotent");
        assertEquals(1_000, bank.get(TREASURY));
        assertEquals(0, bank.get(QuestService.escrowAccount("u2")));
        assertEquals(0, EscrowCoverage.payout(a, bank.api, WALLET, "Nagroda za zlecenie #x"), "refunded quest cannot be paid");
        assertEquals(Bank.CAP, bank.total());
    }

    @Test
    void legacyQuestWithoutEscrowIsCoveredAtStartWhenTheTreasuryCanPay() {
        final Bank bank = new Bank(500);
        final QuestData.Active legacy = quest("u3", 200, false);
        final EscrowCoverage.Summary s = EscrowCoverage.reconcile(List.of(legacy), bank.api);
        assertEquals(1, s.funded());
        assertTrue(legacy.escrow);
        assertFalse(legacy.uncovered);
        assertEquals(300, bank.get(TREASURY));
        assertEquals(200, bank.get(QuestService.escrowAccount("u3")));
        final int transfers = bank.transfers;
        assertEquals(1, EscrowCoverage.reconcile(List.of(legacy), bank.api).secured(), "second start: already secured");
        assertEquals(transfers, bank.transfers, "no second transfer");
        assertEquals(Bank.CAP, bank.total());
    }

    @Test
    void legacyQuestOfAPoorCountryIsMarkedUncoveredAndPaysOnlyRealMoney() {
        final Bank bank = new Bank(50);
        final QuestData.Active legacy = quest("u4", 200, false);
        final EscrowCoverage.Summary s = EscrowCoverage.reconcile(List.of(legacy), bank.api);
        assertEquals(1, s.uncovered());
        assertTrue(legacy.uncovered);
        assertFalse(EscrowCoverage.secured(legacy, bank.api));
        assertEquals(0, EscrowCoverage.payout(legacy, bank.api, WALLET, "Nagroda za zlecenie #x"), "no reward from thin air");
        bank.balances.put(TREASURY, 300L);
        bank.balances.put("world:reserve", Bank.CAP - 300L);
        assertEquals(200, EscrowCoverage.payout(legacy, bank.api, WALLET, "Nagroda za zlecenie #x"), "treasury can pay now");
        assertEquals(Bank.CAP, bank.total());
    }

    @Test
    void restartAfterTheCoverageTransferButBeforeTheFlagWasSavedDoesNotFundTwice() {
        final Bank bank = new Bank(500);
        bank.api.transfer(TREASURY, QuestService.escrowAccount("u5"), 200, "Wladcy: dociągnięcie depozytu zlecenia #x");
        final QuestData.Active legacy = quest("u5", 200, false); // flag lost in the crash
        final int transfers = bank.transfers;
        assertEquals(1, EscrowCoverage.reconcile(List.of(legacy), bank.api).funded());
        assertEquals(transfers, bank.transfers);
        assertTrue(legacy.escrow);
        assertEquals(300, bank.get(TREASURY));
    }

    @Test
    void escrowQuestWithAMissingDepositIsAuditedNeverRefundedFromTheTreasury() {
        final Bank bank = new Bank(1_000);
        final QuestData.Active paidBefore = quest("u6", 100, true); // escrow already emptied (paid or refunded)
        final EscrowCoverage.Summary s = EscrowCoverage.reconcile(List.of(paidBefore), bank.api);
        assertEquals(1, s.audit());
        assertTrue(paidBefore.uncovered);
        assertEquals(EscrowCoverage.State.AUDIT, EscrowCoverage.state(paidBefore, bank.api));
        assertEquals(0, EscrowCoverage.payout(paidBefore, bank.api, WALLET, "Nagroda za zlecenie #x"));
        assertEquals(1_000, bank.get(TREASURY));
        assertEquals(0, bank.transfers);
    }

    @Test
    void noFundsMeansNoQuestAndTheReserveForWagesIsKept() {
        final QuestConfig cfg = QuestGeneratorTest.cfg(Map.of());
        // 1 500 md in the treasury, army wages 400/day, 20 chunks × 2 md upkeep: reserve 2 × 440 = 880 → 620 spendable.
        final long spendable = QuestRules.spendable(1_500, 400, 40, cfg.reserveDaysOfWages, cfg.reserveMin);
        assertEquals(620, spendable);
        assertEquals(18, QuestRules.quote(200, RepTier.OBCY, cfg, spendable, 0).reward(), "3% of 620");
        assertFalse(QuestRules.quote(200, RepTier.OBCY, cfg, spendable, 0).affordable(), "below reward.min: the offer is locked");
        assertFalse(QuestRules.quote(200, RepTier.OBCY, cfg, QuestRules.spendable(800, 400, 40, 2, 500), 0).affordable(),
            "everything is reserved for wages and upkeep");
    }

    @Test
    void supplyAndPendingQuestsAreLeftToTheirOwnCoordinators() {
        final Bank bank = new Bank(1_000);
        final QuestData.Active supply = quest("u7", 100, true);
        supply.supplyDelivery = true;
        final QuestData.Active pending = quest("u8", 100, true);
        pending.acceptancePhase = SupplyAcceptance.Phase.FUNDING;
        final EscrowCoverage.Summary s = EscrowCoverage.reconcile(List.of(supply, pending), bank.api);
        assertEquals(new EscrowCoverage.Summary(0, 0, 0, 0), s);
        assertEquals(EscrowCoverage.State.SUPPLY, EscrowCoverage.state(supply, bank.api));
        assertEquals(EscrowCoverage.State.PENDING_ACCEPTANCE, EscrowCoverage.state(pending, bank.api));
        assertEquals(0, bank.transfers);
    }
}
