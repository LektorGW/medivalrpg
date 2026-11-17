// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMoneyTest {
    private Map<String,Long> before(){
        Map<String,Long> values=new HashMap<>(WorldMoney.CORRECTIONS);
        values.replaceAll((k,v)->v+50_000_000_000L);
        values.put("player:existing",826551L);values.put("country:untouched",47177L);values.put("wladcy:quest",54321L);
        return values;
    }
    @Test void onlyThreeAuthorizedTreasuriesChangeAndCashIsBackedWithoutAPlayerTopup(){
        Map<String,Long> before=before(),after=WorldMoney.migration(before,12345);
        for(var e:before.entrySet())if(!WorldMoney.CORRECTIONS.containsKey(e.getKey()))assertEquals(e.getValue(),after.get(e.getKey()),e.getKey());
        WorldMoney.CORRECTIONS.forEach((k,v)->assertEquals(v,after.get(k)));
        assertEquals(12345L,after.get(WorldMoney.CASH));assertEquals(0L,after.get(WorldMoney.PAYMENTS));
        assertEquals(WorldMoney.CAP,WorldMoney.sum(after));WorldMoney.validate(after,WorldMoney.CAP);
        assertTrue(after.get(WorldMoney.RESERVE)>0);assertFalse(before.containsKey(WorldMoney.RESERVE));
    }
    @Test void migrationCannotRepeatOrHideExcessMoneyOrNegativeCash(){
        assertThrows(IllegalArgumentException.class,()->WorldMoney.migration(WorldMoney.migration(before(),0),0));
        assertThrows(IllegalArgumentException.class,()->WorldMoney.migration(before(),WorldMoney.CAP));
        assertThrows(IllegalArgumentException.class,()->WorldMoney.migration(before(),-1));
    }
    @Test void migrationNeverTopsUpEvenAnAuthorizedTreasury(){
        Map<String,Long> b=before();b.put(WorldMoney.CORRECTIONS.keySet().iterator().next(),0L);
        assertThrows(IllegalArgumentException.class,()->WorldMoney.migration(b,0));
    }
    @Test void overflowAndCorruptBalancesFailClosed(){
        assertThrows(ArithmeticException.class,()->WorldMoney.sum(Map.of("a",Long.MAX_VALUE,"b",1L)));
        assertThrows(IllegalArgumentException.class,()->WorldMoney.sum(Map.of("a",-1L)));
        assertThrows(IllegalArgumentException.class,()->WorldMoney.validate(before(),WorldMoney.CAP));
    }
}
