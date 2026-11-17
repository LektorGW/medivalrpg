// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.*;
import pl.medivalrpg.kolej.data.RailData.Car;

/** Pure, conservative commodity and slot rules. Quantities are items, not stacks. */
public final class FreightRules {
    private FreightRules() {}
    private static final Set<String> BULK = Set.of("COAL", "CHARCOAL", "RAW_IRON", "RAW_COPPER", "RAW_GOLD", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "COAL_ORE", "COBBLESTONE", "STONE", "GRAVEL", "SAND", "RED_SAND", "DIRT", "CLAY_BALL", "FLINT");
    private static final Set<String> RAW = Set.of("IRON_INGOT", "COPPER_INGOT", "GOLD_INGOT", "IRON_NUGGET", "GOLD_NUGGET", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "QUARTZ", "WHEAT", "CARROT", "POTATO", "BEETROOT", "SUGAR_CANE", "BAMBOO", "STICK", "LEATHER", "STRING", "FEATHER", "WHITE_WOOL", "WATER_BUCKET");
    private static final Set<String> TIMBER = Set.of("OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "PALE_OAK_LOG", "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS", "MANGROVE_PLANKS", "CHERRY_PLANKS", "PALE_OAK_PLANKS");
    public static boolean supported(String material) { return BULK.contains(material) || RAW.contains(material) || TIMBER.contains(material); }
    public static int stackSize(String material) { if (!supported(material)) throw new IllegalArgumentException("Obsługiwane są tylko standardowe surowce."); return material.equals("WATER_BUCKET") ? 1 : 64; }
    public static boolean accepts(Car car, String material) {
        if (!supported(material)) return false;
        return switch (car) {
            case KRYTY -> !material.equals("WATER_BUCKET");
            case WEGLARKA -> BULK.contains(material);
            case PLATFORMA -> TIMBER.contains(material) || Set.of("STONE", "COBBLESTONE", "IRON_INGOT", "COPPER_INGOT").contains(material);
            case CYSTERNA -> material.equals("WATER_BUCKET");
            default -> false;
        };
    }
    public static int slots(Car car) { return switch(car) { case KRYTY, WEGLARKA -> 27; case PLATFORMA -> 18; case CYSTERNA -> 9; default -> 0; }; }
    public static int capacity(List<Car> cars, String material) {
        long slots = cars.stream().filter(c -> accepts(c, material)).mapToLong(FreightRules::slots).sum();
        return (int)Math.min(Integer.MAX_VALUE, slots * stackSize(material));
    }
    /** Opaque/non-plain stacks occupy space but are never merged, removed or recreated. */
    public record Slot(String material, int amount, boolean plain, int limit) {
        public Slot { if (amount < 0 || limit < 1 || amount > limit) throw new IllegalArgumentException("Nieprawidłowy stos."); }
        public static Slot empty() { return new Slot("AIR", 0, true, 64); }
    }
    public static List<Slot> plan(List<Slot> before, String material, int quantity, boolean loading) {
        if (quantity <= 0) throw new IllegalArgumentException("Ilość musi być dodatnia.");
        int max=stackSize(material), remaining=quantity;
        List<Slot> after=new ArrayList<>(before);
        for (int i=0;i<after.size() && remaining>0;i++) {
            Slot s=after.get(i);
            if (loading) {
                if (!s.plain || !s.material.equals(material) || s.amount==0) continue;
                int n=Math.min(remaining,s.amount); after.set(i,n==s.amount?Slot.empty():new Slot(material,s.amount-n,true,max)); remaining-=n;
            } else {
                if (s.amount!=0 && (!s.plain || !s.material.equals(material))) continue;
                int room=s.amount==0?max:Math.min(max,s.limit)-s.amount;
                int n=Math.min(remaining,Math.max(0,room));
                if(n>0) {after.set(i,new Slot(material,s.amount+n,true,max));remaining-=n;}
            }
        }
        if(remaining!=0) throw new IllegalArgumentException(loading?"Magazyn nie ma całej partii zwykłego surowca.":"Magazyn docelowy nie mieści całej partii.");
        return List.copyOf(after);
    }
}
