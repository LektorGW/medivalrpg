// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.*;

class StorageDiscoveryTest {
    @Test void lazySlicesVisitOnlyKnownBoundingBoxInTheSameOrderAsOneFullScan() {
        var box=new BlockBox("world",-19,45,-7,5,50,12); var cells=new ArrayList<BlockPos>();
        for(long start=0;start<StorageDiscovery.volume(box);start+=128)
            for(long i=start;i<Math.min(start+128,StorageDiscovery.volume(box));i++) cells.add(StorageDiscovery.cell(box,i));
        assertEquals(StorageDiscovery.volume(box),cells.size()); assertEquals(cells.size(),new HashSet<>(cells).size());
        assertEquals(new BlockPos("world",-19,45,-7),cells.getFirst());
        assertTrue(cells.stream().allMatch(p -> box.contains(p.world(),p.x(),p.y(),p.z())));
        assertThrows(IllegalArgumentException.class,() -> StorageDiscovery.cell(box,StorageDiscovery.volume(box)));
    }
    @Test void bothHalvesAndMultipleBuildingBindingsIdentifyOneDoubleChest() {
        var a=new BlockPos("world",15,60,0); var b=new BlockPos("world",16,60,0);
        var left=StorageDiscovery.canonical(List.of(a,b)); var right=StorageDiscovery.canonical(List.of(b,a));
        assertEquals(left,right); final Map<List<BlockPos>,Integer> stores=new HashMap<>();
        stores.putIfAbsent(left,54); stores.putIfAbsent(right,54); stores.putIfAbsent(StorageDiscovery.canonical(List.of(a,b)),54);
        assertEquals(54,stores.values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void anotherPluginsReceiptCannotBeAdoptedAsAProductionStore() {
        assertTrue(StorageDiscovery.exclusiveNamespace(List.of(),"ludnosc"));
        assertTrue(StorageDiscovery.exclusiveNamespace(List.of("ludnosc","ludnosc"),"ludnosc"));
        assertFalse(StorageDiscovery.exclusiveNamespace(List.of("ludnosc","kolej"),"ludnosc"));
        assertFalse(StorageDiscovery.exclusiveNamespace(List.of("szlaki"),"ludnosc"));
    }
    @Test void everyContainerOfAWarehouseHasItsOwnKeyAndAnOlderJarStillReadsThem() {
        String w="343be366-6435-4b36-bec8-aa88071faa90", mine="bf46e386-66c6-4538-b93a-59f7c5d25431";
        var keys=new ArrayList<String>(List.of(mine,"default"));
        assertEquals(w,StorageDiscovery.freeKey(keys,w)); keys.add(w);
        assertEquals(w+"#2",StorageDiscovery.freeKey(keys,w)); keys.add(w+"#2"); keys.add(w+"#3");
        assertEquals(w+"#4",StorageDiscovery.freeKey(keys,w));
        keys.remove(w+"#2"); assertEquals(w+"#2",StorageDiscovery.freeKey(keys,w), "zwolniony numer jest używany ponownie");
        keys.add(0,w+"#7");
        assertEquals(List.of(w,w+"#7",w+"#3"),StorageDiscovery.keysOf(keys,w), "główne powiązanie pierwsze");
        assertEquals(List.of(mine),StorageDiscovery.keysOf(keys,mine));
        assertEquals(w,StorageDiscovery.ownerKey(w+"#3")); assertEquals(w,StorageDiscovery.ownerKey(w));
        assertEquals("default",StorageDiscovery.ownerKey("default")); assertNull(StorageDiscovery.ownerKey(null));
        assertTrue(StorageDiscovery.extraKey(w+"#3")); assertFalse(StorageDiscovery.extraKey(w)); assertFalse(StorageDiscovery.extraKey("default"));
        // jar 4ead5867 resolves a key with UUID.fromString: an extra key is not a UUID there, so it is treated like the
        // unnamed country store ("default") and never as a broken record
        assertThrows(IllegalArgumentException.class,() -> UUID.fromString(w+"#3"));
        assertEquals(UUID.fromString(w),UUID.fromString(StorageDiscovery.ownerKey(w+"#3")));
    }
}
