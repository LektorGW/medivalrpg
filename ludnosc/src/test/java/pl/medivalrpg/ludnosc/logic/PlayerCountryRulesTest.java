// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import pl.medivalrpg.ludnosc.model.*;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.planner.*;

class PlayerCountryRulesTest {
    @Test void playerCatalogKeepsOnlyFunctionalFamilies() {
        assertEquals(14, PlayerCountryRules.DEFAULT_BUILDINGS.size());
        for (String id : PlayerCountryRules.DEFAULT_BUILDINGS) {
            assertTrue(PlayerCountryRules.allowed(false,false,id,id,PlayerCountryRules.DEFAULT_BUILDINGS), id);
            assertTrue(PlayerCountryRules.allowed(false,false,id+"_wariant",id,PlayerCountryRules.DEFAULT_BUILDINGS), id);
        }
        for (String id : List.of("kamienica","palac","ratusz","mur","ogrod","wieza")) {
            assertFalse(PlayerCountryRules.allowed(false,false,id,id,PlayerCountryRules.DEFAULT_BUILDINGS));
            assertTrue(PlayerCountryRules.allowed(true,false,id,id,List.of()));
        }
    }
    @Test void configurableEmptyListAndPlayerCopies() {
        assertFalse(PlayerCountryRules.allowed(false,false,"chata","chata",List.of()));
        assertTrue(PlayerCountryRules.allowed(false,true,"kopia_gracza","kopia_gracza",List.of()));
        assertTrue(PlayerCountryRules.allowed(false,false,"wariant","chata",List.of("wariant")));
        assertFalse(PlayerCountryRules.allowed(false,false,"chata","chata",List.of("wariant")));
    }
    @Test void automaticPlanningAndHutsNeverRunForPlayerCountries() {
        assertFalse(PlayerCountryRules.automatic(false));
        assertTrue(PlayerCountryRules.automatic(true));
        assertEquals(HutSiting.Decision.DISABLED, HutSiting.decide(PlayerCountryRules.automatic(false),10,0,20,999999,100,true));
    }
    @Test void persistentRollingDailyLimitSurvivesReload() {
        long now=100_000_000L;
        assertTrue(PlayerCountryRules.hutDue(now,0,1));
        assertFalse(PlayerCountryRules.hutDue(now,now-86_399_999,1));
        assertTrue(PlayerCountryRules.hutDue(now,now-86_400_000,1));
        assertFalse(PlayerCountryRules.hutDue(now,now+1,1));
        assertFalse(PlayerCountryRules.hutDue(now,0,0));
        assertTrue(PlayerCountryRules.hutDue(now,now-43_200_000,2));
    }
    @Test void queuedCancellationRefundsOnceAndDoesNotTouchOtherOrders() {
        var data=new LudnoscData(); var country=UUID.randomUUID();
        var first=QueuedOrder.of(country,"chata","world",0,64,0,0,1000,"Władca");
        var next=QueuedOrder.of(country,"kopalnia","world",20,64,0,0,2000,"Doradca");
        data.enqueue(first); data.enqueue(next);
        long paid=3000, refund=0;
        if(data.removeQueued(first)) refund+=QueueRules.refund(first.cost,true,0.5);
        if(data.removeQueued(first)) refund+=QueueRules.refund(first.cost,true,0.5);
        assertEquals(1000,refund); assertEquals(2000,paid-refund);
        assertEquals(List.of(next),data.queue(country));
    }
    @Test void runningCancellationPreservesUnusedMaterialInExistingUnitsOnce() {
        var data=new LudnoscData(); var country=UUID.randomUUID();
        var site=new ConstructionSite(); site.id=UUID.randomUUID();site.countryId=country;site.cost=1000;
        site.materials().put(MaterialNeeds.WOOD,3); site.materials().put(MaterialNeeds.STONE,0);
        data.putSite(site);
        var state=data.state(country); state.resources().put(MaterialNeeds.WOOD,7L);
        assertSame(site,data.removeSite(site.id));
        CancellationRules.returnBuffer(site,state);
        assertEquals(500,QueueRules.refund(site.cost,false,0.5));
        CancellationRules.returnBuffer(site,state);
        assertEquals(10L,state.resources().get(MaterialNeeds.WOOD));
        assertTrue(site.materials().isEmpty()); assertNull(data.removeSite(site.id));
    }
    private static TerrainView terrain(boolean obstacles, boolean slope) {
        return new TerrainView() {
            public int height(int x,int z){return slope?64+Math.floorMod(x,4):64;}
            public boolean liquid(int x,int z){return false;}
            public boolean obstacle(int x,int z){return obstacles && x==0 && z==0;}
            public boolean own(int x,int z){return Math.abs(x)<=80 && Math.abs(z)<=80;}
        };
    }
    @Test void courteousSitingKeepsGapToPlayerBlocksAndRoads() {
        var view=HutSiting.courteous(terrain(true,false),Set.of(RoadPlanner.key(20,0)),6);
        assertFalse(view.own(6,6)); assertTrue(view.own(7,7));
        assertFalse(view.own(14,0)); assertTrue(view.own(13,0));
    }
    @Test void hutNeverRelaxesSlopeOrCrowdsAnExistingBuilding() {
        var settings=new SiteFinder.Settings(24,3,8,2);
        assertNull(HutSiting.find(terrain(false,true),new int[]{7,7},0,0,"inner",List.of(),Set.of(),Set.of(),settings,0).spot());
        var taken=List.of(new BlockBox("world",-6,60,-6,6,75,6));
        var found=HutSiting.find(terrain(false,false),new int[]{7,7},0,0,"inner",taken,Set.of(),Set.of(),settings,0);
        assertNotNull(found.spot()); assertEquals(0,found.level());
        var box=TownPlanner.footprint("world",new int[]{7,7},found.spot());
        assertTrue(box.minX()>14 || box.maxX()< -14 || box.minZ()>14 || box.maxZ()< -14);
    }
}
