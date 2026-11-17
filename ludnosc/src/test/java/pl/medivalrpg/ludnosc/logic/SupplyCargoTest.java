// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SupplyCargoTest {
    @Test void onlyPlainMatchingStacksAreDebitedAndMetadataItemsRemain() {
        int[] source={64,10,64,5}; boolean[] eligible={false,true,false,true};
        assertArrayEquals(new int[]{64,0,64,2},SupplyCargo.debit(source,eligible,13));
        assertArrayEquals(new int[]{64,10,64,5},source);
    }
    @Test void shortageNeverPartiallyTakesGoods() {
        int[] source={64,3}; assertNull(SupplyCargo.debit(source,new boolean[]{false,true},4));
        assertArrayEquals(new int[]{64,3},source);
    }
    @Test void amountAndOrdinaryInventoryBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,() -> SupplyCargo.debit(new int[37],new boolean[37],1));
        assertThrows(IllegalArgumentException.class,() -> SupplyCargo.debit(new int[]{65},new boolean[]{true},1));
        assertThrows(IllegalArgumentException.class,() -> SupplyCargo.debit(new int[]{1},new boolean[]{true},4097));
    }
    @Test void randomizedDebitsConserveEveryEligibleStackAndLeaveOtherItemsUntouched() {
        Random random=new Random(20261028);
        for(int test=0;test<5000;test++) {
            int[] amounts=new int[36]; boolean[] eligible=new boolean[36]; int available=0;
            for(int i=0;i<36;i++) { amounts[i]=random.nextInt(65); eligible[i]=random.nextBoolean(); if(eligible[i]) available+=amounts[i]; }
            int[] before=amounts.clone(); int request=1+random.nextInt(4096); int[] after=SupplyCargo.debit(amounts,eligible,request);
            assertArrayEquals(before,amounts);
            if(available<request) assertNull(after);
            else { assertEquals(request,Arrays.stream(before).sum()-Arrays.stream(after).sum()); for(int i=0;i<36;i++) if(!eligible[i]) assertEquals(before[i],after[i]); }
        }
    }
}
