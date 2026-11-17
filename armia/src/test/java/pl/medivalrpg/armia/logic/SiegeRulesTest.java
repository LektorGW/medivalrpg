// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiegeRulesTest {
    private final UUID attacker=UUID.randomUUID(), defender=UUID.randomUUID(), neutral=UUID.randomUUID();

    @Test void terrainRequiresCurrentEnemySiegeOwnedByExactlyThatDefender() {
        assertTrue(SiegeRules.enemySiege(attacker,defender,attacker,defender,true,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,attacker,attacker,attacker,true,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,defender,attacker,defender,false,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,neutral,attacker,defender,true,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,defender,neutral,defender,true,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,defender,defender,attacker,true,200,100));
        assertFalse(SiegeRules.enemySiege(attacker,defender,attacker,defender,true,100,100));
        assertFalse(SiegeRules.enemySiege(attacker,null,attacker,defender,true,200,100));
        assertFalse(SiegeRules.enemySiege(null,defender,attacker,defender,true,200,100));
    }

    @Test void impactNeverLeaksAcrossPositiveOrNegativeChunkBorders() {
        assertTrue(SiegeRules.sameImpactChunk(15,15,0,0));
        assertFalse(SiegeRules.sameImpactChunk(15,15,16,15));
        assertFalse(SiegeRules.sameImpactChunk(15,15,15,16));
        assertTrue(SiegeRules.sameImpactChunk(-1,-1,-16,-16));
        assertFalse(SiegeRules.sameImpactChunk(-1,-1,0,-1));
        assertFalse(SiegeRules.sameImpactChunk(-16,-16,-17,-16));
    }

    @Test void reloadCapacityRangeAndDetachedStatePreventShots() {
        assertTrue(SiegeRules.ready(160,160,63,64,true,1600,40));
        assertFalse(SiegeRules.ready(159,160,0,64,true,100,40));
        assertFalse(SiegeRules.ready(160,160,64,64,true,100,40));
        assertFalse(SiegeRules.ready(160,160,0,64,false,100,40));
        assertFalse(SiegeRules.ready(160,160,0,64,true,1601,40));
        assertFalse(SiegeRules.ready(160,160,0,64,true,0,40));
        assertFalse(SiegeRules.ready(160,160,0,64,true,Double.NaN,40));
        assertFalse(SiegeRules.ready(160,160,0,64,true,100,Double.POSITIVE_INFINITY));
    }

    @Test void flightReturnsToEndpointAndMedievalCannonRemainsDistinctFromOrdinaryUnits() {
        assertEquals(0,SiegeRules.arc(0,10)); assertEquals(0,SiegeRules.arc(1,10));
        assertEquals(10,SiegeRules.arc(0.5,10));
        assertFalse(SiegeRules.engine("katapulta").cannon());
        assertEquals(160,SiegeRules.engine("katapulta").reloadTicks());
        assertEquals(5,SiegeRules.engine("katapulta").shotCost());
        assertTrue(SiegeRules.engine("armata").cannon());
        assertEquals(SiegeRules.engine("armata"),SiegeRules.engine("artyleria"));
        assertNull(SiegeRules.engine("muszkieter"));
        assertNull(SiegeRules.engine(null));
    }

    @Test void incompatibleAndOldAmmoSelectionsUseSafeHistoricalDefaults() {
        assertEquals(SiegeRules.Ammo.STONE,SiegeRules.ammunition("katapulta",null));
        assertEquals(SiegeRules.Ammo.BALL,SiegeRules.ammunition("armata",null));
        assertEquals(SiegeRules.Ammo.BALL,SiegeRules.ammunition("artyleria","nieznany"));
        assertEquals(SiegeRules.Ammo.STONE,SiegeRules.ammunition("katapulta","kartacz"));
        assertEquals(SiegeRules.Ammo.GRAPESHOT,SiegeRules.ammunition("armata","kartacz"));
        assertNull(SiegeRules.ammunition("piechota","kula"));
        for (final var ammo:SiegeRules.Ammo.values()) {
            assertEquals(ammo,SiegeRules.parseAmmo(ammo.id()));
            assertEquals(ammo==SiegeRules.Ammo.STONE,SiegeRules.accepts("katapulta",ammo));
            assertEquals(ammo!=SiegeRules.Ammo.STONE,SiegeRules.accepts("armata",ammo));
            assertFalse(SiegeRules.accepts("piechota",ammo));
        }
    }

    @Test void grapeshotCapacityCountsPelletsAndNeverHasTerrainDamage() {
        final var ammo=SiegeRules.Ammo.GRAPESHOT;
        assertEquals(5,ammo.pellets()); assertEquals(15,ammo.cost()); assertEquals(16,ammo.range());
        assertFalse(ammo.breaksBlocks());
        assertTrue(SiegeRules.fitsVolley(59,64,ammo));
        assertFalse(SiegeRules.fitsVolley(60,64,ammo));
        assertTrue(SiegeRules.fitsVolley(63,64,SiegeRules.Ammo.BALL));
        assertFalse(SiegeRules.fitsVolley(64,64,SiegeRules.Ammo.STONE));
        assertFalse(SiegeRules.fitsVolley(0,64,null));
    }

    @Test void grapeshotNeverTargetsOwnNeutralOrCountrylessAndHitsAtMostFiveDistinctVictims() {
        assertTrue(SiegeRules.enemyCountry(attacker,defender,true));
        assertFalse(SiegeRules.enemyCountry(attacker,attacker,true));
        assertFalse(SiegeRules.enemyCountry(attacker,defender,false));
        assertFalse(SiegeRules.enemyCountry(attacker,null,true));
        final java.util.Set<UUID> hit=new java.util.HashSet<>();
        assertTrue(SiegeRules.registerVictim(hit,defender));
        assertFalse(SiegeRules.registerVictim(hit,defender));
        assertFalse(SiegeRules.registerVictim(hit,null));
        for (int i=0;i<4;i++) assertTrue(SiegeRules.registerVictim(hit,UUID.randomUUID()));
        assertFalse(SiegeRules.registerVictim(hit,UUID.randomUUID()));
        assertEquals(5,hit.size());
    }
}
