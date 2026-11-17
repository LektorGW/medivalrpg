// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.freight.FreightRules.Slot;
import static org.junit.jupiter.api.Assertions.*;

class SupplyRulesTest {
    @Test void waterConsumesActualBucketsAndReturnsEmptiesWithoutTouchingOtherItems() {
        var custom=new Slot("WATER_BUCKET",1,false,1);
        var before=List.of(new Slot("WATER_BUCKET",1,true,1),new Slot("WATER_BUCKET",1,true,1),custom,new Slot("BUCKET",15,true,16));
        var after=SupplyRules.consume(before,SupplyKind.ZURAW,2);
        assertEquals(2,after.stream().filter(s->s.material().equals("BUCKET")).mapToInt(Slot::amount).sum()-15);
        assertEquals(custom,after.get(2));assertEquals(1,before.get(0).amount());
        assertEquals(0,SupplyRules.available(after,SupplyKind.ZURAW));
    }
    @Test void allFourWaterModelsUseTheSameFiniteInventoryContract() {
        for(var kind:SupplyKind.values())if(!kind.coal) {
            assertEquals(250,kind.units());assertEquals("WATER_BUCKET",kind.material());
            assertEquals(16,SupplyRules.room(kind,500,0));
            assertEquals("BUCKET",SupplyRules.consume(List.of(new Slot("WATER_BUCKET",1,true,1)),kind,1).getFirst().material());
        }
    }
    @Test void customFuelAndCharcoalAreNeverAccepted() {
        var slots=List.of(new Slot("COAL",64,false,64),new Slot("CHARCOAL",64,true,64));
        assertEquals(0,SupplyRules.available(slots,SupplyKind.WEGIEL));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.consume(slots,SupplyKind.WEGIEL,1));
    }
    @Test void coalIsConservedAndShortageHasNoPartialSideEffects() {
        var before=List.of(new Slot("COAL",3,true,64),new Slot("COAL",4,true,64));
        assertEquals(2,SupplyRules.available(SupplyRules.consume(before,SupplyKind.WEGIEL,5),SupplyKind.WEGIEL));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.consume(before,SupplyKind.WEGIEL,8));
        assertEquals(7,SupplyRules.available(before,SupplyKind.WEGIEL));
    }
    @Test void capacityNeverRoundsUpOrOverfillsTender() {
        assertEquals(49,SupplyRules.room(SupplyKind.WEGIEL,1,4000));
        assertEquals(15,SupplyRules.room(SupplyKind.ZURAW,500,1));
        assertEquals(0,SupplyRules.room(SupplyKind.ZURAW,500,3999));
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,500.01})assertFalse(SupplyRules.valid(invalid,4000));
        assertFalse(SupplyRules.valid(0,4000.01));assertTrue(SupplyRules.needs(124.99,4000));assertFalse(SupplyRules.needs(125,1000));
    }
    @Test void tariffsAreCheckedAndZeroDoesNotBypassPhysicalConsumption() {
        assertEquals(30,SupplyRules.cost(SupplyKind.WEGIEL,3,1,1));assertEquals(3,SupplyRules.cost(SupplyKind.POMPA,1,1,1));
        assertEquals(5,SupplyRules.cost(SupplyKind.ZURAW,2,1,1));assertEquals(0,SupplyRules.cost(SupplyKind.ZURAW,2,0,0));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.cost(SupplyKind.ZURAW,65,1,1));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.cost(SupplyKind.WEGIEL,1,-1,0));
        assertThrows(ArithmeticException.class,()->SupplyRules.cost(SupplyKind.WEGIEL,64,Long.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.consume(List.of(Slot.empty()),SupplyKind.ZURAW,1));
    }
    @Test void unusualStackLimitsStillCannotDropReturnedBuckets() {
        var before=List.of(new Slot("WATER_BUCKET",2,true,2),new Slot("STONE",64,false,64));
        assertThrows(IllegalArgumentException.class,()->SupplyRules.consume(before,SupplyKind.ZURAW,1));
        assertEquals(2,before.getFirst().amount());
    }
    @Test void boundsAgreeWithBundledModelsAtAllFourRotations() throws Exception {
        try(var in=getClass().getResourceAsStream("/infrastructure-models/asset-index.json")) {
            assertNotNull(in);var models=JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("models");
            for(var kind:SupplyKind.values()) {
                JsonObject model=null;for(var element:models)if(element.getAsJsonObject().get("name").getAsString().equals(kind.model))model=element.getAsJsonObject();
                assertNotNull(model);var bound=model.getAsJsonArray("bounds");
                var lo=bound.get(0).getAsJsonArray();var hi=bound.get(1).getAsJsonArray();var zero=kind.bounds(0);
                assertEquals(lo.get(0).getAsDouble()/16,zero.minX(),1e-8);assertEquals(hi.get(2).getAsDouble()/16,zero.maxZ(),1e-8);
                assertEquals(hi.get(1).getAsDouble()/16,zero.height(),1e-8);
                for(int q=0;q<4;q++){var b=kind.bounds(q);assertEquals((zero.maxX()-zero.minX())*(zero.maxZ()-zero.minZ()),(b.maxX()-b.minX())*(b.maxZ()-b.minZ()),1e-8);}
            }
        }
        var zero=SupplyKind.ZURAW.bounds(0);var yaw=SupplyKind.ZURAW.bounds(1);assertEquals(-zero.maxZ(),yaw.minX());assertEquals(zero.maxX(),yaw.maxZ());
    }
}
