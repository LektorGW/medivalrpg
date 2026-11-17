// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.data.RailData.Car;
import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.kolej.freight.FreightRules.*;

class FreightRulesTest {
    @Test void eachWagonHasTypedCapacityNotPassengerSeatsOrTankForCoal() {
        assertEquals(0,capacity(List.of(Car.PASAZERSKI,Car.SALONOWY,Car.HAMULCOWY,Car.CYSTERNA),"COAL"));
        assertEquals(27*64,capacity(List.of(Car.WEGLARKA),"COAL"));
        assertEquals(0,capacity(List.of(Car.WEGLARKA),"DIAMOND"));
        assertEquals(18*64,capacity(List.of(Car.PLATFORMA),"OAK_LOG"));
        assertEquals(0,capacity(List.of(Car.PLATFORMA),"WHEAT"));
        assertEquals(9,capacity(List.of(Car.CYSTERNA),"WATER_BUCKET"));
        assertEquals(0,capacity(List.of(Car.KRYTY),"WATER_BUCKET"));
        assertEquals(54*64,capacity(List.of(Car.KRYTY,Car.WEGLARKA),"COAL"));
    }
    @Test void loadingSkipsTaggedCoinsAndMetadataAndNeverPartiallyRemoves() {
        var before=List.of(new Slot("IRON_NUGGET",64,false,64),new Slot("IRON_NUGGET",12,true,64),new Slot("IRON_NUGGET",10,true,64));
        assertThrows(IllegalArgumentException.class,()->plan(before,"IRON_NUGGET",23,true));
        assertEquals(12,before.get(1).amount());
        var after=plan(before,"IRON_NUGGET",20,true);
        assertEquals(before.getFirst(),after.getFirst());assertEquals(0,after.get(1).amount());assertEquals(2,after.get(2).amount());
    }
    @Test void unloadingPreservesOpaqueSlotsAndHonorsPerItemStackSize() {
        var before=List.of(new Slot("COAL",63,false,64),new Slot("COAL",60,true,64),Slot.empty());
        var after=plan(before,"COAL",68,false);
        assertEquals(before.getFirst(),after.getFirst());assertEquals(64,after.get(1).amount());assertEquals(64,after.get(2).amount());
        assertThrows(IllegalArgumentException.class,()->plan(before,"COAL",69,false));
        assertThrows(IllegalArgumentException.class,()->plan(List.of(Slot.empty()),"WATER_BUCKET",2,false));
        assertEquals(1,plan(List.of(Slot.empty()),"WATER_BUCKET",1,false).getFirst().amount());
    }
    @Test void conservationAcrossRandomFragmentedInventories() {
        Random random=new Random(27);
        for(int trial=0;trial<500;trial++) {
            List<Slot> source=new ArrayList<>();int stock=0;
            for(int s=0;s<27;s++){int n=random.nextInt(65);source.add(new Slot("COAL",n,true,64));stock+=n;}
            if(stock==0)continue;int amount=1+random.nextInt(stock);
            var removed=plan(source,"COAL",amount,true);var target=plan(Collections.nCopies(27,Slot.empty()),"COAL",amount,false);
            assertEquals(stock-amount,removed.stream().mapToInt(Slot::amount).sum());
            assertEquals(stock,removed.stream().mapToInt(Slot::amount).sum()+target.stream().mapToInt(Slot::amount).sum());
        }
    }
    @Test void arbitraryToolsAndNonpositiveQuantitiesAreRejected() {
        assertFalse(supported("DIAMOND_SWORD"));assertFalse(supported("COMMAND_BLOCK"));
        assertThrows(IllegalArgumentException.class,()->plan(List.of(Slot.empty()),"COAL",0,false));
        assertThrows(IllegalArgumentException.class,()->plan(List.of(Slot.empty()),"COAL",-1,false));
    }
}
