// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;
import static org.junit.jupiter.api.Assertions.*;

class ClosedLedgerTest {
    private Ledger ledger(){
        Ledger ledger=new Ledger(Ledger.Sink.NONE);
        ledger.restore(Map.of("player:old",1000L,WorldMoney.CASH,100L,WorldMoney.PAYMENTS,0L,WorldMoney.RESERVE,WorldMoney.CAP-1100),7);
        ledger.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);return ledger;
    }
    @Test void automaticRailBudgetCannotRefillAnEmptiedOrRefundedAccount() {
        var l=ledger();String account="kolej:budzet_0123456789abcdef";
        long reserve=l.balance(WorldMoney.RESERVE);
        assertEquals(TxResult.OK,l.allocateRailBudgetOnce(WorldMoney.MANAGER,account,500,"approved once"));
        assertEquals(TxResult.OK,l.transfer(account,"kolej:przewoznik",500,"use budget"));
        for(int i=0;i<10;i++)assertEquals(TxResult.OK,l.allocateRailBudgetOnce(WorldMoney.MANAGER,account,500,"retry after crash"));
        assertEquals(reserve-500,l.balance(WorldMoney.RESERVE));assertEquals(0,l.balance(account));assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void concurrentRailBudgetRequestsReserveOnlyOneApprovedAllocation() throws Exception {
        var l=ledger();var pool=Executors.newFixedThreadPool(4);
        try {var calls=new ArrayList<Callable<TxResult>>();for(int i=0;i<32;i++)calls.add(()->l.allocateRailBudgetOnce(WorldMoney.MANAGER,"kolej:budzet_0123456789abcdef",500,"parallel"));
            for(var result:pool.invokeAll(calls))assertEquals(TxResult.OK,result.get());
        }finally{pool.shutdownNow();}
        assertEquals(500,l.balance("kolej:budzet_0123456789abcdef"));assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void railBudgetNeverCreatesOwnerRightsOrAcceptsUnboundedAccounts() {
        var l=ledger();long before=l.balance(WorldMoney.RESERVE);
        assertEquals(TxResult.INVALID_ACCOUNT,l.allocateRailBudgetOnce(UUID.randomUUID(),"kolej:budzet_0123456789abcdef",500,"OP"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.allocateRailBudgetOnce(WorldMoney.MANAGER,"player:new",500,"wrong target"));
        assertEquals(before,l.balance(WorldMoney.RESERVE));
    }
    @Test void enablingNeverChangesExistingBalances(){
        Ledger l=ledger();assertEquals(1000,l.balance("player:old"));assertEquals(7,l.seq());assertEquals(WorldMoney.CAP,l.totalMoney());
        assertThrows(IllegalStateException.class,()->l.restore(Map.of(),0));
    }
    @Test void unfundedLegacyPayoutCannotMintAndOwnerMustExplicitlyFundIt(){
        Ledger l=ledger();assertEquals(TxResult.INSUFFICIENT_FUNDS,l.deposit("player:new",100,"Kupiec"));
        assertEquals(TxResult.OK,l.allocateWorldMoney(WorldMoney.MANAGER,WorldMoney.PAYMENTS,100,"Budżet"));
        assertEquals(TxResult.OK,l.deposit("player:new",100,"Kupiec"));
        assertEquals(0,l.balance(WorldMoney.PAYMENTS));assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void feesReturnToCirculationAndAdminSetIsOnlyAFundedTransfer(){
        Ledger l=ledger();assertEquals(TxResult.OK,l.withdraw("player:old",500,"Budowa"));
        assertEquals(TxResult.OK,l.set("player:new",500,"Korekta"));
        assertEquals(TxResult.INSUFFICIENT_FUNDS,l.set("player:new",501,"Nie wolno drukować"));
        assertEquals(500,l.balance("player:new"));assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void onlyConfirmedUuidCanMoveReserveAndGenericApiCannotBypassIt(){
        Ledger l=ledger();
        assertEquals(TxResult.INVALID_ACCOUNT,l.allocateWorldMoney(UUID.randomUUID(),"player:new",1,"OP"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.allocateWorldMoney(null,"player:new",1,"console"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.transfer(WorldMoney.RESERVE,"player:new",1,"bypass"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.set(WorldMoney.RESERVE,0,"bypass"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.withdraw(WorldMoney.CASH,1,"bypass"));
        assertEquals(TxResult.INVALID_ACCOUNT,l.deposit(WorldMoney.RESERVE,1,"bypass"));
        assertEquals(TxResult.OK,l.returnWorldMoney(WorldMoney.MANAGER,"player:old",100,"Powrót"));
        assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void cashConversionsAreBackedAndInsufficientCashCannotBeRedeemed(){
        Ledger l=ledger();assertEquals(TxResult.OK,l.issueCash("player:old",50,"Bank"));
        assertEquals(150,l.balance(WorldMoney.CASH));assertEquals(950,l.balance("player:old"));
        assertEquals(TxResult.OK,l.redeemCash("player:old",150,"Bank"));
        assertEquals(TxResult.INSUFFICIENT_FUNDS,l.redeemCash("player:old",1,"Bez pokrycia"));
        assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void npcTaxesRequireTheirOwnHouseholdBudgetAndCannotDrainMerchantFunds(){
        Ledger l=ledger();l.allocateWorldMoney(WorldMoney.MANAGER,WorldMoney.MERCHANT,1000,"Kupcy");
        assertEquals(TxResult.INSUFFICIENT_FUNDS,l.deposit("country:test",5,"Ludnosc: podatek od obywateli NPC i kopalnie"));
        assertEquals(1000,l.balance(WorldMoney.MERCHANT));
        l.allocateWorldMoney(WorldMoney.MANAGER,WorldMoney.HOUSEHOLDS,10,"Obywatele");
        assertEquals(TxResult.OK,l.deposit("country:test",5,"Ludnosc: podatek od obywateli NPC i kopalnie"));
        assertEquals(5,l.balance(WorldMoney.HOUSEHOLDS));assertEquals(WorldMoney.CAP,l.totalMoney());
    }
    @Test void concurrentPayoutsNeverOverdrawBudgetOrChangeSupply() throws Exception {
        Ledger l=ledger();l.allocateWorldMoney(WorldMoney.MANAGER,WorldMoney.PAYMENTS,1000,"Budżet");
        try(var pool=Executors.newFixedThreadPool(8)){
            List<Future<?>> tasks=new ArrayList<>();
            for(int t=0;t<8;t++){int n=t;tasks.add(pool.submit(()->{for(int i=0;i<1000;i++)l.deposit("player:p"+n,1,"Skup");}));}
            for(var task:tasks)task.get();
        }
        assertEquals(0,l.balance(WorldMoney.PAYMENTS));assertEquals(2000,l.balances().entrySet().stream().filter(e->e.getKey().startsWith("player:")).mapToLong(Map.Entry::getValue).sum());
        assertEquals(WorldMoney.CAP,l.totalMoney());
    }
}
