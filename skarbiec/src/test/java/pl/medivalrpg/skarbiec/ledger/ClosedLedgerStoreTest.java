// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClosedLedgerStoreTest {
    @TempDir Path directory;
    @Test void spentRailBudgetMarkerSurvivesReplayAndCannotRefillAfterRestart() throws Exception {
        String account="kolej:budzet_0123456789abcdef";
        var first=new LedgerStore(directory,Logger.getLogger("rail-budget-test"),ZoneOffset.UTC,true,30);
        var ledger=new Ledger(first);ledger.restore(Map.of(WorldMoney.CASH,0L,WorldMoney.PAYMENTS,0L,WorldMoney.RESERVE,WorldMoney.CAP),0);
        ledger.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);first.start();ledger.requestSnapshot(true);
        assertTrue(ledger.allocateRailBudgetOnce(WorldMoney.MANAGER,account,500,"approved rail budget").ok());
        assertTrue(ledger.transfer(account,"kolej:przewoznik",500,"fund physical job").ok());
        first.durable().get(5,java.util.concurrent.TimeUnit.SECONDS);first.close();
        var second=new LedgerStore(directory,Logger.getLogger("rail-budget-test"),ZoneOffset.UTC,true,30);
        var restored=new Ledger(Ledger.Sink.NONE);second.load(restored);restored.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);
        assertTrue(restored.balances().containsKey(account));assertEquals(0,restored.balance(account));
        assertTrue(restored.allocateRailBudgetOnce(WorldMoney.MANAGER,account,1000,"retry larger amount").ok());
        assertEquals(WorldMoney.CAP-500,restored.balance(WorldMoney.RESERVE));assertEquals(WorldMoney.CAP,restored.totalMoney());second.close();
    }
    @Test void unsnapshottedCashAndBudgetTransfersReplayWithoutMinting() throws Exception {
        LedgerStore first=new LedgerStore(directory,Logger.getLogger("closed-money-test"),ZoneOffset.UTC,true,30);
        Ledger ledger=new Ledger(first);
        ledger.restore(Map.of("player:old",1000L,WorldMoney.CASH,100L,WorldMoney.PAYMENTS,0L,WorldMoney.RESERVE,WorldMoney.CAP-1100),0);
        ledger.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);first.start();ledger.requestSnapshot(true);
        ledger.allocateWorldMoney(WorldMoney.MANAGER,WorldMoney.MERCHANT,500,"Budżet kupców");
        ledger.issueCash(WorldMoney.MERCHANT,120,"Skup w monetach");
        ledger.redeemCash("player:old",20,"Wpłata monet do banku");
        ledger.withdraw("player:old",50,"Budowa");
        Map<String,Long> expected=ledger.balances();first.close();
        LedgerStore second=new LedgerStore(directory,Logger.getLogger("closed-money-test"),ZoneOffset.UTC,true,30);
        Ledger restored=new Ledger(Ledger.Sink.NONE);var loaded=second.load(restored);
        assertTrue(loaded.replayed()>0);assertEquals(expected,restored.balances());
        restored.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);
        assertEquals(WorldMoney.CAP,restored.totalMoney());assertEquals(200,restored.balance(WorldMoney.CASH));
        second.close();
    }
    @Test void completeOfflineMigrationJournalRecoversBeforeSnapshotReplacement() throws Exception {
        LedgerStore original=new LedgerStore(directory,Logger.getLogger("migration-test"),ZoneOffset.UTC,true,30);
        Ledger legacy=new Ledger(original);var before=new java.util.HashMap<String,Long>();
        WorldMoney.CORRECTIONS.forEach((key,value)->before.put(key,value+50_000_000_000L));before.put("player:untouched",1000L);
        legacy.restore(before,10);original.start();legacy.requestSnapshot(true);original.close();
        var after=WorldMoney.migration(before,12345);
        var changed=new java.util.ArrayList<String>(WorldMoney.CORRECTIONS.keySet());
        changed.addAll(java.util.List.of(WorldMoney.CASH,WorldMoney.PAYMENTS,WorldMoney.MERCHANT,WorldMoney.HOUSEHOLDS,WorldMoney.RESERVE));
        StringBuilder journal=new StringBuilder();long sequence=10;
        for(String key:changed)journal.append(new TxRecord(++sequence,0,TxRecord.Type.SET,null,key,after.get(key),null,after.get(key),"Approved offline migration").encode()).append('\n');
        Files.writeString(directory.resolve("journal.log"),journal);
        // Crash here leaves the OLD bank snapshot plus the complete, atomically uploaded migration journal.
        LedgerStore recovering=new LedgerStore(directory,Logger.getLogger("migration-test"),ZoneOffset.UTC,true,30);
        Ledger restored=new Ledger(Ledger.Sink.NONE);var loaded=recovering.load(restored);
        assertEquals(8,loaded.replayed());assertEquals(after,restored.balances());assertEquals(1000,restored.balance("player:untouched"));
        restored.enableClosedSupply(WorldMoney.CAP,WorldMoney.MANAGER);assertEquals(WorldMoney.CAP,restored.totalMoney());
        Ledger second=new Ledger(Ledger.Sink.NONE);recovering.load(second);assertEquals(after,second.balances());recovering.close();
    }
}
