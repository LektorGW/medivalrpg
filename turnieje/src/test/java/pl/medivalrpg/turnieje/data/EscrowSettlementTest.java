// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.turnieje.integration.Economy;

class EscrowSettlementTest {
    static class Bank implements Economy {
        Map<String, Long> balances = new HashMap<>(); Set<String> denied = new HashSet<>();
        int transfers, deposits;
        public boolean available() { return true; }
        public long balance(String a) { return balances.getOrDefault(a, 0L); }
        public boolean transfer(String from, String to, long amount, String reason) {
            if (denied.contains(to) || balance(from) < amount) return false;
            balances.put(from, balance(from) - amount); balances.put(to, balance(to) + amount); transfers++; return true;
        }
        public boolean deposit(String a, long amount, String reason) { balances.put(a, balance(a)+amount); deposits++; return true; }
        public boolean withdraw(String a, long amount, String reason) { throw new AssertionError("No burning money"); }
        public String format(long n) { return ""+n; }
        public Optional<Long> parse(String s) { return Optional.of(Long.parseLong(s)); }
    }
    EscrowLedger ledger() { return new EscrowLedger("session", "Turniej", "escrow"); }
    @Test void failedPrizeStaysOwedAndSuccessfulPrizeIsNeverReplayed() {
        var l = ledger(); l.settling = true; l.payments.put("winner", 60L); l.payments.put("runner", 40L);
        var bank = new Bank(); bank.balances.put("escrow",100L); bank.denied.add("runner");
        assertFalse(EscrowSettlement.settle(l, bank, () -> {}));
        assertEquals(Map.of("runner",40L), l.payments); assertEquals(60, bank.balance("winner"));
        l = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(l), EscrowLedger.class);
        bank.denied.clear(); assertTrue(EscrowSettlement.settle(l,bank,()->{}));
        assertTrue(EscrowSettlement.settle(l,bank,()->{}));
        assertEquals(60,bank.balance("winner")); assertEquals(40,bank.balance("runner")); assertEquals(2,bank.transfers);
    }
    @Test void mintIsPerformedOnlyOnceAcrossRetry() {
        var l=ledger(); l.minted=100; l.payments.put("winner",100L);
        var bank=new Bank(); bank.denied.add("winner");
        assertFalse(EscrowSettlement.settle(l,bank,()->{})); assertEquals(0,l.minted);
        bank.denied.clear(); assertTrue(EscrowSettlement.settle(l,bank,()->{})); assertEquals(1,bank.deposits);
    }
    @Test void partiallyFundedRefundDoesNotSilentlyForgiveTheRest() {
        var l=ledger(); l.sourceAccount="source"; l.funded=100;
        var bank=new Bank(); bank.balances.put("escrow",30L);
        assertFalse(EscrowSettlement.refund(l,bank,()->{})); assertEquals(100,l.funded); assertEquals(0,bank.balance("source"));
    }
    @Test void cancellationReturnsFeesAndTreasuryWithoutMintingAndWithoutReplay() {
        var l=ledger(); l.sourceAccount="source"; l.funded=100; l.minted=500;
        var id=UUID.randomUUID(); l.addFee(id,20);
        var bank=new Bank(); bank.balances.put("escrow",120L);
        assertTrue(EscrowSettlement.refund(l,bank,()->{})); assertTrue(EscrowSettlement.refund(l,bank,()->{}));
        assertEquals(20,bank.balance(bank.playerAccount(id))); assertEquals(100,bank.balance("source")); assertEquals(0,bank.deposits);
    }
    @Test void saveFailureStopsTheBatchBeforeAnotherPayment() {
        var l=ledger(); l.payments.put("a",50L); l.payments.put("b",50L);
        var bank=new Bank(); bank.balances.put("escrow",100L);
        assertThrows(IllegalStateException.class,()->EscrowSettlement.settle(l,bank,()->{throw new IllegalStateException("disk");}));
        assertEquals(1,bank.transfers); assertEquals(1,l.payments.size());
    }
    @Test void overflowIsRejected() {
        var l=ledger(); var id=UUID.randomUUID(); l.addFee(id,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->l.addFee(id,1));
        l.funded=1; assertThrows(ArithmeticException.class,l::held);
    }
}
