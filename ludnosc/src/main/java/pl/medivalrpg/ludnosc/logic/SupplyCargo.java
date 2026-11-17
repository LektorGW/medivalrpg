// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/** Exact debit of eligible plain resource stacks. A refusal leaves every source slot unchanged. */
public final class SupplyCargo {
    private SupplyCargo() { }
    public static int[] debit(int[] amounts, boolean[] eligible, int requested) {
        if(amounts.length!=eligible.length || amounts.length>36 || requested<1 || requested>SupplyLedger.MAX_AMOUNT)
            throw new IllegalArgumentException("Nieprawidłowy ładunek.");
        int[] after=amounts.clone(); int left=requested;
        for(int i=0;i<after.length;i++) {
            if(after[i]<0 || eligible[i] && after[i]>64) throw new IllegalArgumentException("Nieprawidłowy stos.");
            if(!eligible[i]) continue;
            int taken=Math.min(left,after[i]); after[i]-=taken; left-=taken;
        }
        return left==0 ? after : null;
    }
}
