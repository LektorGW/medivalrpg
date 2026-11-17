// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ResourceSnapshotsTest {
    @Test void publishedInventoryDoesNotBecomeAnotherLegacyDeposit() {
        var snapshots=new ResourceSnapshots(); UUID country=UUID.randomUUID();
        var legacy=new HashMap<>(Map.of("kamien",70L)); var physical=new HashMap<>(Map.of("kamien",3L,"rudy",2L));
        snapshots.publish(country,legacy,physical);
        assertEquals(73L,snapshots.read(country).get("kamien")); assertEquals(70L,legacy.get("kamien"));
        snapshots.publish(country,legacy,physical); assertEquals(73L,snapshots.read(country).get("kamien"));
        physical.put("kamien",1L); snapshots.publish(country,legacy,physical);
        assertEquals(71L,snapshots.read(country).get("kamien")); assertEquals(70L,legacy.get("kamien"));
    }
    @Test void consumersCannotModifySnapshotsAndLaterPublicationDoesNotChangeEarlierReads() {
        var snapshots=new ResourceSnapshots(); UUID country=UUID.randomUUID(); var mutable=new HashMap<>(Map.of("rudy",2L));
        snapshots.publish(country,Map.of(),mutable); var first=snapshots.read(country);
        mutable.put("rudy",9L); assertEquals(2L,first.get("rudy"));
        assertThrows(UnsupportedOperationException.class,() -> first.put("rudy",99L));
        snapshots.publish(country,Map.of(),mutable); assertEquals(9L,snapshots.read(country).get("rudy")); assertEquals(2L,first.get("rudy"));
    }
    @Test void concurrentReadOnlyThreadsSeeWholeSnapshotsWithoutAnyBukkitRuntime() throws Exception {
        var snapshots=new ResourceSnapshots(); UUID country=UUID.randomUUID(); snapshots.publish(country,Map.of(),Map.of("a",0L,"b",0L));
        try(var pool=Executors.newFixedThreadPool(4)) {
            final var tasks=new ArrayList<Callable<Void>>();
            tasks.add(() -> { for(long n=0;n<5000;n++) snapshots.publish(country,Map.of(),Map.of("a",n,"b",n)); return null; });
            for(int i=0;i<3;i++) tasks.add(() -> { for(int n=0;n<10000;n++) { var read=snapshots.read(country); assertEquals(read.get("a"),read.get("b")); } return null; });
            for(var result:pool.invokeAll(tasks)) result.get();
        }
    }
    @Test void unknownCountryReadIsAnImmutableEmptySnapshot() {
        var snapshots=new ResourceSnapshots(); assertEquals(Map.of(),snapshots.read(UUID.randomUUID())); assertEquals(Map.of(),snapshots.read(null));
    }
}
