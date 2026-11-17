// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Fixed money supply: cash backing is counted once, as CASH, rather than again as bank balances. */
public final class WorldMoney {
    public static final long CAP = 100_000_000L; // 1,000,000 zł, in copper.
    public static final UUID MANAGER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");
    public static final String RESERVE = "world:reserve";
    public static final String CASH = "world:cash";
    public static final String PAYMENTS = "world:payments";
    public static final String MERCHANT = "world:merchant";
    public static final String HOUSEHOLDS = "world:households";
    public static final Map<String,Long> CORRECTIONS = Map.of(
        "country:17b1bc20-8d55-4b16-8fab-30418b89d3ea",5_000_000L,
        "country:2a5125f6-c65d-422a-8e9c-ab848790e524",5_000_000L,
        "country:9d1fe36e-4852-4d18-9b2a-a89146f43e66",10_000_000L);

    private WorldMoney() { }

    /** Pure migration proposal. Caller must supply a complete, coherent physical-coin census. No disk writes. */
    public static Map<String,Long> migration(Map<String,Long> before,long physicalCash) {
        if(physicalCash<0)throw new IllegalArgumentException("Ujemna wartość monet");
        if(before.containsKey(RESERVE) || before.containsKey(CASH) || before.containsKey(PAYMENTS))
            throw new IllegalArgumentException("Konta Skarbu Świata już istnieją — nie wolno powtórzyć migracji");
        Map<String,Long> after=new HashMap<>(before);
        before.forEach((account,value)->{if(!Ledger.validAccount(account) || value==null || value<0)throw new IllegalArgumentException("Nieprawidłowe saldo");});
        CORRECTIONS.forEach((account,target)->{
            Long current=before.get(account);
            if(current==null || current<target)throw new IllegalArgumentException("Korekta nie może dopłacać ani tworzyć kont: "+account);
            after.put(account,target);
        });
        long allocated=Math.addExact(sum(after),physicalCash);
        if(allocated>CAP)throw new IllegalArgumentException("Zachowane salda i monety przekraczają milion złotych");
        if(before.containsKey(MERCHANT) || before.containsKey(HOUSEHOLDS))throw new IllegalArgumentException("Budżety świata już istnieją");
        after.put(CASH,physicalCash);after.put(PAYMENTS,0L);after.put(MERCHANT,0L);after.put(HOUSEHOLDS,0L);after.put(RESERVE,CAP-allocated);
        return Map.copyOf(after);
    }

    public static long sum(Map<String,Long> accounts) {
        long total=0;
        for(Long value:accounts.values()){
            if(value==null || value<0)throw new IllegalArgumentException("Nieprawidłowe saldo");
            total=Math.addExact(total,value);
        }
        return total;
    }

    public static void validate(Map<String,Long> accounts,long cap) {
        if(cap!=CAP || !accounts.containsKey(RESERVE) || !accounts.containsKey(CASH) || !accounts.containsKey(PAYMENTS)
            || sum(accounts)!=cap)throw new IllegalArgumentException("Niezgodna pula Skarbu Świata");
    }
}
