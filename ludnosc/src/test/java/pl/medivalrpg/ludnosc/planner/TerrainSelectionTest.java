// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.storage.DataCodec;

class TerrainSelectionTest {
    private static TerrainView view(final IntBinaryOperator height) {
        return new TerrainView() {
            public int height(int x,int z){return height.applyAsInt(x,z);}
            public boolean own(int x,int z){return x>=-5 && x<=20 && z>=-5 && z<=20;}
            public boolean obstacle(int x,int z){return false;}
            public boolean liquid(int x,int z){return false;}
        };
    }
    @Test void automaticHeightUsesMedianAndProducesBoundedEarthworks() {
        final var result=TerrainSelection.plan(view((x,z)->64+Math.floorMod(x,3)),0,0,2,2,null,(x,z)->false);
        assertEquals(65,result.targetY());
        assertFalse(result.columns().isEmpty());
        assertTrue(result.columns().stream().allMatch(c->Math.abs(c[2]-c[3])<=6));
        assertTrue(result.columns().stream().allMatch(c->c[6]==0),"manual works never clear arbitrary building leftovers");
    }
    @Test void protectedColumnsAndForeignApronsAreNeverModified() {
        final var result=TerrainSelection.plan(view((x,z)->64),0,0,12,12,66,(x,z)->x==6);
        assertTrue(result.protectedColumns()>0);
        for(final int[] c:result.columns()){
            assertNotEquals(6,c[0]); assertTrue(c[0]>=-5 && c[0]<=20 && c[1]>=-5 && c[1]<=20);
        }
    }
    @Test void unknownTerrainIsNotGuessedAsChosenFloor() {
        assertThrows(IllegalArgumentException.class,()->TerrainSelection.plan(view((x,z)->x==1?TerrainView.UNKNOWN:64),0,0,2,2,65,(x,z)->false));
    }
    @Test void ownershipAndSelectionSizeAreValidatedBeforePlanning() {
        assertThrows(IllegalArgumentException.class,()->TerrainSelection.plan(view((x,z)->64),0,0,21,2,65,(x,z)->false));
        assertFalse(TerrainSelection.valid(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0));
        assertFalse(TerrainSelection.valid(0,0,4095,0));
        assertTrue(TerrainSelection.valid(0,0,63,63));
        assertFalse(TerrainSelection.valid(0,0,64,63));
    }
    @Test void excessiveExcavationIsOmittedFromConfirmedWork() {
        final var result=TerrainSelection.plan(view((x,z)->64),0,0,4,4,10,(x,z)->false);
        assertTrue(result.columns().isEmpty()); assertTrue(result.excessiveColumns()>0);
    }
    @Test void postFireRecoverySurvivesSaveAndDoesNotDuplicateBuildings() {
        final var data=new LudnoscData(); final UUID country=UUID.randomUUID();
        data.state(country).fireRepairs.add("burned-house"); data.state(country).fireRepairs.add("burned-house");
        final var loaded=new LudnoscData(); DataCodec.apply(DataCodec.decode(DataCodec.encode(data)),loaded);
        assertEquals(java.util.Set.of("burned-house"),loaded.state(country).fireRepairs);
    }
}
