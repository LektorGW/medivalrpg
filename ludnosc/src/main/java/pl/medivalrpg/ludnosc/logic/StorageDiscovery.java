// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Comparator;
import java.util.List;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;

/** Deterministic bounded building scan and canonical identity of shared inventories. */
public final class StorageDiscovery {
    private StorageDiscovery() { }
    public static long volume(BlockBox b) { return (long)b.sizeX()*b.sizeZ()*(b.maxY()-b.minY()+1L); }
    public static BlockPos cell(BlockBox b,long i) {
        if(i<0 || i>=volume(b)) throw new IllegalArgumentException("cursor");
        long width=b.sizeX(), depth=b.sizeZ();
        return new BlockPos(b.world(),b.minX()+(int)(i%width),b.minY()+(int)(i/(width*depth)),b.minZ()+(int)(i/width%depth));
    }
    public static List<BlockPos> canonical(List<BlockPos> parts) {
        return parts.stream().distinct().sorted(Comparator.comparing(BlockPos::world).thenComparingInt(BlockPos::x)
            .thenComparingInt(BlockPos::y).thenComparingInt(BlockPos::z)).toList();
    }
    /**
     * Binding keys of a country: a building id, {@code "default"}, or {@code "<warehouse id>#<n>"} (n from 2) for the
     * further containers of a warehouse. An older jar reads an extra key as an unnamed country store.
     */
    public static String ownerKey(String key) {
        if(key==null) return null;
        int i=key.indexOf('#');
        return i<0 ? key : key.substring(0,i);
    }
    /** True for the key of a further container of a warehouse. */
    public static boolean extraKey(String key) { return key!=null && key.indexOf('#')>0; }
    /** Keys of the bindings that belong to the building, the main one first (insertion order otherwise). */
    public static List<String> keysOf(java.util.Collection<String> keys,String building) {
        final java.util.ArrayList<String> out=new java.util.ArrayList<>();
        for(String k:keys) if(building.equals(ownerKey(k))) { if(k.equals(building)) out.addFirst(k); else out.add(k); }
        return out;
    }
    /** The key for one more container of the building: its id when free, else the lowest free {@code id#n}. */
    public static String freeKey(java.util.Collection<String> keys,String building) {
        if(!keys.contains(building)) return building;
        for(int n=2;;n++) { String k=building+"#"+n; if(!keys.contains(k)) return k; }
    }
    public static boolean exclusiveNamespace(java.util.Collection<String> namespaces,String own) {
        return namespaces.stream().allMatch(own::equals);
    }
}
