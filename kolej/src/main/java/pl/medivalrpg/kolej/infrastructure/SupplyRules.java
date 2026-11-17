// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.*;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;
import pl.medivalrpg.kolej.steam.LocomotiveSpec;

/** Physical items only. Plain coal = 10 kg; one water bucket = 250 L and returns one empty bucket. */
public final class SupplyRules {
    private SupplyRules() {}
    public static int available(List<Slot> slots,SupplyKind kind) {
        return slots.stream().filter(s->s.plain() && s.material().equals(kind.material())).mapToInt(Slot::amount).sum();
    }
    public static boolean valid(double coal,double water) {
        var s=LocomotiveSpec.defaults();
        return Double.isFinite(coal) && Double.isFinite(water) && coal>=0 && water>=0 && coal<=s.coalCapacity() && water<=s.waterCapacity();
    }
    public static boolean needs(double coal,double water) {
        var s=LocomotiveSpec.defaults();return !valid(coal,water) || coal<s.coalCapacity()*.25 || water<s.waterCapacity()*.25;
    }
    /** Terminal top-up: worth a transfer when at least 3 coal (30 kg) or 2 buckets (500 l) fit. */
    public static boolean topUp(double coal,double water) {
        if(!valid(coal,water))return true;
        var s=LocomotiveSpec.defaults();return s.coalCapacity()-coal>=30 || s.waterCapacity()-water>=500;
    }
    /** Should this resource be transferred now? Low (&lt;25 %) always; with a terminal top-up also when it is not full. */
    public static boolean wants(SupplyKind kind,double coal,double water,boolean topUp) {
        var s=LocomotiveSpec.defaults();
        if(kind.coal)return coal<s.coalCapacity()*.25 || topUp && s.coalCapacity()-coal>=30;
        return water<s.waterCapacity()*.25 || topUp && s.waterCapacity()-water>=500;
    }
    public static int room(SupplyKind kind,double coal,double water) {
        if(!valid(coal,water))throw new IllegalArgumentException("Nieprawidłowe zapasy tendera — wymagana kontrola.");
        var spec=LocomotiveSpec.defaults();
        return (int)Math.floor(((kind.coal?spec.coalCapacity():spec.waterCapacity())-(kind.coal?coal:water))/kind.units());
    }
    public static long cost(SupplyKind kind,int quantity,long coalPrice,long waterPrice) {
        if(quantity<1 || quantity>64 || coalPrice<0 || waterPrice<0)throw new IllegalArgumentException("Partia 1–64; taryfy nie mogą być ujemne.");
        long amount=Math.multiplyExact((long)quantity,kind.units());
        return kind.coal?Math.multiplyExact(amount,coalPrice):Math.multiplyExact((amount+99)/100,waterPrice);
    }
    public static List<Slot> consume(List<Slot> before,SupplyKind kind,int quantity) {
        if(quantity<1 || quantity>64 || before.size()>54)throw new IllegalArgumentException("Partia 1–64 sztuki, kontener do 54 slotów.");
        var after=new ArrayList<>(before);int remaining=quantity;
        for(int i=0;i<after.size() && remaining>0;i++) {
            Slot s=after.get(i);
            if(!s.plain() || !s.material().equals(kind.material()))continue;
            int n=Math.min(s.amount(),remaining);remaining-=n;
            after.set(i,s.amount()==n?Slot.empty():new Slot(s.material(),s.amount()-n,true,s.limit()));
        }
        if(remaining>0)throw new IllegalArgumentException("Brak całej partii zwykłego węgla lub wiader wody w magazynie.");
        if(!kind.coal) {
            remaining=quantity;
            for(int i=0;i<after.size() && remaining>0;i++) {
                Slot s=after.get(i);
                if(s.amount()!=0 && (!s.plain() || !s.material().equals("BUCKET")))continue;
                int n=Math.min(remaining,s.amount()==0?16:Math.max(0,Math.min(16,s.limit())-s.amount()));
                if(n>0){after.set(i,new Slot("BUCKET",s.amount()+n,true,16));remaining-=n;}
            }
            if(remaining>0)throw new IllegalArgumentException("Brak miejsca na puste wiadra.");
        }
        return List.copyOf(after);
    }
}
