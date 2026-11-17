// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.medivalrpg.ludnosc.model.BlockBox;

final class IslandWallRouteTest {
    @Test void distantIslandsGetSeparateWallsWithoutScanningTheSea() {
        final Set<Long> claims = new HashSet<>();
        for (int offset : new int[]{0,100_000}) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
            claims.add(LandPicker.key(offset/16+x,offset/16+z));
        final TerrainView view = new TerrainView() {
            public int height(int x,int z) {
                assertTrue(Math.abs(x)<100 && Math.abs(z)<100 || Math.abs(x-100_000)<100 && Math.abs(z-100_000)<100,
                    "never inspect the ocean between the colonies");
                return 64;
            }
            public boolean liquid(int x,int z){return false;}
            public boolean obstacle(int x,int z){return false;}
            public boolean own(int x,int z){return claims.contains(LandPicker.key(x>>4,z>>4));}
        };
        final var town=List.of(new BlockBox("w",0,64,0,8,70,8),
            new BlockBox("w",100_000,64,100_000,100_008,70,100_008));
        final var walls=WallRoute.rings(town,claims,4,view,(x,z)->false);
        assertTrue(walls.stream().anyMatch(p->p[0]<0));
        assertTrue(walls.stream().anyMatch(p->p[0]>100_008));
        assertTrue(walls.size()<300);
    }

    @Test void overlongOrOverflowingPolylinesAreRejectedBeforeRasterisation() {
        assertTrue(WallRoute.rasterise(List.of(new int[]{-29_999_000,0},new int[]{29_999_000,0}),true).isEmpty());
        assertTrue(WallRoute.rasterise(List.of(new int[]{Integer.MIN_VALUE,0},new int[]{Integer.MAX_VALUE,0}),false).isEmpty());
        assertFalse(WallRoute.rasterise(List.of(new int[]{0,0},new int[]{10,0}),false).isEmpty());
    }
}
