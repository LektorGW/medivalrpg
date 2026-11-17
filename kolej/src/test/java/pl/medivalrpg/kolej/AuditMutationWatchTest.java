// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.AuditMutationWatch.SpatialIndex;
import pl.medivalrpg.kolej.AuditMutationWatch.Volume;
import static org.junit.jupiter.api.Assertions.*;

class AuditMutationWatchTest {
    private final UUID world=UUID.randomUUID();
    @Test void everySupportRailAndHeadroomBlockInThreeByThreeFootprintInvalidates() {
        var index=new SpatialIndex(world,List.of(new Volume(10,65,20,5,7)));
        for(int x=9;x<=11;x++)for(int z=19;z<=21;z++)for(int y=64;y<=70;y++)assertEquals(Set.of(7),index.block(world,x,y,z),x+"/"+y+"/"+z);
    }
    @Test void outsideHorizontalFootprintAndVerticalRangeNeverInvalidates() {
        var index=new SpatialIndex(world,List.of(new Volume(10,65,20,4,7)));
        for(int[] p:List.of(new int[]{8,65,20},new int[]{12,65,20},new int[]{10,65,18},new int[]{10,65,22},new int[]{10,63,20},new int[]{10,70,20}))assertTrue(index.block(world,p[0],p[1],p[2]).isEmpty());
    }
    @Test void otherWorldAndUnknownWorldCannotInvalidateMatchingCoordinatesOrChunks() {
        var index=new SpatialIndex(world,List.of(new Volume(10,65,20,4,7)));UUID other=UUID.randomUUID();
        assertTrue(index.block(other,10,65,20).isEmpty());assertTrue(index.chunk(other,0,1).isEmpty());
        assertTrue(index.block(null,10,65,20).isEmpty());assertTrue(index.chunk(null,0,1).isEmpty());
    }
    @Test void overlappingChecksReturnEveryAffectedIndexAndDeduplicateRepeatedVolume() {
        var first=new Volume(10,65,20,4,7);var second=new Volume(11,65,20,5,8);
        var index=new SpatialIndex(world,List.of(first,first,second,new Volume(10,65,20,5,7)));
        assertEquals(Set.of(7,8),index.block(world,10,65,20));assertEquals(Set.of(7,8),index.chunk(world,0,1));
        assertEquals(Set.of(7,8),index.block(world,10,70,20));assertTrue(index.block(world,10,71,20).isEmpty());
    }
    @Test void verticallySeparatedTracksInSameColumnOnlyInvalidateAtMatchingHeight() {
        var index=new SpatialIndex(world,List.of(new Volume(10,65,20,4,1),new Volume(10,80,20,4,2)));
        assertEquals(Set.of(1),index.block(world,10,64,20));assertEquals(Set.of(2),index.block(world,10,84,20));
        assertTrue(index.block(world,10,75,20).isEmpty());assertEquals(Set.of(1,2),index.chunk(world,0,1));
    }
    @Test void chunkReloadInvalidatesAllFootprintChunksAcrossPositiveBoundary() {
        var index=new SpatialIndex(world,List.of(new Volume(16,65,16,4,3)));
        for(int cx=0;cx<=1;cx++)for(int cz=0;cz<=1;cz++)assertEquals(Set.of(3),index.chunk(world,cx,cz));
        assertTrue(index.chunk(world,-1,0).isEmpty());assertTrue(index.chunk(world,2,1).isEmpty());
    }
    @Test void negativeChunkCoordinatesUseFloorRatherThanTruncation() {
        var index=new SpatialIndex(world,List.of(new Volume(-16,65,-16,4,3)));
        for(int cx=-2;cx<=-1;cx++)for(int cz=-2;cz<=-1;cz++)assertEquals(Set.of(3),index.chunk(world,cx,cz));
        assertTrue(index.chunk(world,0,-1).isEmpty());assertEquals(Set.of(3),index.block(world,-17,65,-17));assertTrue(index.block(world,-14,65,-16).isEmpty());
    }
    @Test void repeatedLogicalIndexAtDifferentCoordinatesIsNotLost() {
        var index=new SpatialIndex(world,List.of(new Volume(-1000,65,1000,4,42),new Volume(1000,90,-1000,5,42)));
        assertEquals(Set.of(42),index.block(world,-1000,65,1000));assertEquals(Set.of(42),index.block(world,1000,90,-1000));assertTrue(index.block(world,0,65,0).isEmpty());
    }
    @Test void constructionDetachesFromInputAndLookupResultsCannotBeMutated() {
        var source=new ArrayList<>(List.of(new Volume(10,65,20,4,1)));var index=new SpatialIndex(world,source);source.clear();source.add(new Volume(1000,65,1000,4,2));
        assertEquals(Set.of(1),index.block(world,10,65,20));assertTrue(index.block(world,1000,65,1000).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->index.chunk(world,0,1).add(999));assertThrows(UnsupportedOperationException.class,()->index.block(world,10,65,20).clear());
    }
    @Test void emptyAuditHasNoAffectedColumnsOrChunks() {
        var index=new SpatialIndex(world,List.of());assertTrue(index.block(world,0,65,0).isEmpty());assertTrue(index.chunk(world,0,0).isEmpty());
    }
    @Test void invalidVolumesOverflowAndUnboundedInputAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Volume(0,65,0,-1,0));assertThrows(IllegalArgumentException.class,()->new Volume(0,65,0,65,0));
        assertThrows(IllegalArgumentException.class,()->new Volume(0,65,0,4,-1));assertThrows(IllegalArgumentException.class,()->new Volume(Integer.MAX_VALUE,65,0,4,0));
        assertThrows(IllegalArgumentException.class,()->new Volume(0,65,Integer.MIN_VALUE,4,0));assertThrows(IllegalArgumentException.class,()->new Volume(0,Integer.MIN_VALUE,0,4,0));
        assertThrows(IllegalArgumentException.class,()->new Volume(0,Integer.MAX_VALUE,0,4,0));
        var volume=new Volume(0,65,0,4,0);assertThrows(IllegalArgumentException.class,()->new SpatialIndex(world,Collections.nCopies(AuditMutationWatch.MAX_VOLUMES+1,volume)));
    }
}
