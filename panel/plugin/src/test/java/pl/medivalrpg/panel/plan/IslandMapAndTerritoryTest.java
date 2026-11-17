// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.map.MapImage;

class IslandMapAndTerritoryTest {
    static long k(int x,int z) {return ChunkGeometry.key(x,z);}

    @Test void sparseImagePreservesBothFarIslandsWithinPixelBudget() {
        MapImage.Column c=MapImage.reduce(new MapImage.ColumnSource() {
            public int top(int x,int z) {return 64;}
            public int color(int x,int y,int z) {return 0x22AA44;}
            public boolean water(int x,int y,int z) {return false;}
        },-64,320);
        var columns=Map.of(k(-1874999,-1874999),c,k(1874999,1874999),c);
        var raster=MapImage.composeBounded(columns,-29999984,-29999984,59999984,59999984,128,1);
        assertTrue(raster.width()<=128 && raster.height()<=128);
        assertTrue(raster.pixels().length<=128*128);
        assertNotEquals(0,raster.pixels()[0]);
        assertNotEquals(0,raster.pixels()[raster.pixels().length-1]);
        var thin=MapImage.composeBounded(Map.of(k(0,0),c,k(10000,0),c),0,0,160016,16,128,1);
        assertEquals(1,thin.height());
        assertNotEquals(0,thin.pixels()[thin.pixels().length-1]);
        var compact=MapImage.composeBounded(Map.of(k(0,0),c),0,0,16,16,128,1);
        assertArrayEquals(MapImage.compose(Map.of(k(0,0),c),0,0,16,16),compact.pixels());
    }

    @Test void additionsGrowEitherExistingIslandButNeverStartNewIsland() {
        Set<Long> own=Set.of(k(0,0),k(1,0),k(100,100),k(101,100));
        var d=TerritoryPlanner.decide(own,k(0,0),List.of(k(102,100),k(103,100),k(500,500)),List.of(),q->null,q->null,50,5);
        assertEquals(List.of(k(102,100),k(103,100)),d.claim());
        assertEquals("NOT_ADJACENT",d.skipped().getFirst().code());
    }

    @Test void removalAllowsLeavesOnEitherIslandButPreservesEveryOriginalIsland() {
        Set<Long> own=Set.of(k(0,0),k(1,0),k(2,0),k(100,100),k(101,100),k(102,100));
        var d=TerritoryPlanner.decide(own,k(0,0),List.of(),List.of(k(101,100),k(2,0),k(102,100),k(0,0)),q->null,q->null,50,5);
        assertEquals(List.of(k(2,0),k(102,100)),d.release());
        assertTrue(d.skipped().stream().anyMatch(s->s.cx()==101 && s.code().equals("DISCONNECTS")));
        assertTrue(d.skipped().stream().anyMatch(s->s.code().equals("CAPITAL")));
    }

    @Test void disappearingIslandDoesNotMaskSplitElsewhere() {
        Set<Long> own=Set.of(k(0,0),k(1,0),k(2,0),k(100,100));
        var d=TerritoryPlanner.decide(own,k(0,0),List.of(),List.of(k(100,100),k(1,0)),q->null,q->null,50,5);
        assertEquals(List.of(k(100,100)),d.release());
        assertEquals("DISCONNECTS",d.skipped().getFirst().code());
    }
}
