// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IslandKingdomMapTest {
    @Test void sparseMapMatchesDenseMapForExistingCompactCountries() {
        Set<Long> claims=new HashSet<>();
        for(int x=-100;x<=100;x++) for(int z=-100;z<=100;z++) if((x+z)%3!=0) claims.add(KingdomPlanner.key(x,z));
        KingdomPlanner.Cell cell=(x,z)->!claims.contains(KingdomPlanner.key(x,z))?' ':x==2 && z==3?'S':x==7?'~':'.';
        assertArrayEquals(KingdomPlanner.map(-100,-100,100,100,cell),KingdomPlanner.map(-100,-100,100,100,claims,cell));
    }
    @Test void distantIslandsOnlyEvaluateActualChunksAndKeepBothSymbols() {
        Set<Long> claims=Set.of(KingdomPlanner.key(-1874999,-1874999),KingdomPlanner.key(1874999,1874999));
        AtomicInteger calls=new AtomicInteger();
        String[] map=KingdomPlanner.map(-1874999,-1874999,1874999,1874999,claims,(x,z)->{calls.incrementAndGet();return x<0?'S':'o';});
        assertEquals(2,calls.get());
        assertTrue(map.length<=24 && map[0].length()<=48);
        assertTrue(String.join("",map).contains("S"));
        assertTrue(String.join("",map).contains("o"));
    }
}
