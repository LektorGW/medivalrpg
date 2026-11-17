// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NavalRulesTest {
    @Test void distantCoastCannotImprisonRidersAndExternalTeleportsReleaseTheirSeats() {
        assertEquals(NavalRules.ExitRoute.SHORE,NavalRules.exitRoute(true));
        assertEquals(NavalRules.ExitRoute.WATER,NavalRules.exitRoute(false));
        assertTrue(NavalRules.releaseForTeleport(true,false,false,false));
        assertTrue(NavalRules.releaseForTeleport(false,true,false,false));
        assertFalse(NavalRules.releaseForTeleport(true,true,true,false));
        assertFalse(NavalRules.releaseForTeleport(true,true,false,true));
        assertFalse(NavalRules.releaseForTeleport(false,false,false,false));
    }
    @Test void eraCapacityAndBuildLimitsKeepTheFleetMedieval() {
        final var transport=NavalRules.type("transportowiec");
        final var caravel=NavalRules.type("karawela");
        assertNull(NavalRules.type("pancernik"));
        assertEquals(16,transport.soldiers()); assertEquals(4,transport.seats());
        assertEquals(32,caravel.soldiers()); assertEquals(6,caravel.seats());
        assertTrue(NavalRules.canBuild(transport,1,23,2));
        assertFalse(NavalRules.canBuild(caravel,1,0,0));
        assertTrue(NavalRules.canBuild(caravel,2,0,0));
        assertFalse(NavalRules.canBuild(transport,2,24,0));
        assertFalse(NavalRules.canBuild(transport,2,0,3));
        assertFalse(NavalRules.canBuild(null,2,0,0));
        assertFalse(NavalRules.canBuild(transport,2,-1,0));
        assertFalse(NavalRules.canBuild(transport,2,0,-1));
    }

    @Test void landingCannotUseAShipAtTheDockOrStaleRemoteEvidence() {
        final long now=500_000;
        assertTrue(NavalRules.transportProof(true,true,true,48*48,24,true,now-120_000,now));
        assertFalse(NavalRules.transportProof(false,true,true,0,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,false,true,0,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,false,0,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,48*48+1,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,0,23.9,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,0,24,false,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,0,24,true,now-120_001,now));
        assertFalse(NavalRules.transportProof(true,true,true,0,24,true,now+1,now));
        assertFalse(NavalRules.transportProof(true,true,true,Double.NaN,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,-1,24,true,now,now));
        assertFalse(NavalRules.transportProof(true,true,true,0,Double.POSITIVE_INFINITY,true,now,now));
    }

    @Test void disembarkAndCombatRespectCountryRelations() {
        final UUID own=UUID.randomUUID(),other=UUID.randomUUID();
        assertTrue(NavalRules.canLandOn(own,null,false));
        assertTrue(NavalRules.canLandOn(own,own,false));
        assertFalse(NavalRules.canLandOn(own,other,false));
        assertTrue(NavalRules.canLandOn(own,other,true));
        assertFalse(NavalRules.canLandOn(null,null,true));
        assertTrue(NavalRules.enemy(own,other,true));
        assertFalse(NavalRules.enemy(own,other,false));
        assertFalse(NavalRules.enemy(own,own,true));
        assertFalse(NavalRules.enemy(own,null,true));
    }

    @Test void navalSalvosRequireAnEnemyWithinRangeAndRespectReloadAndFlightCap() {
        assertTrue(NavalRules.volley(true,true,48*48,0,10_000,23));
        assertFalse(NavalRules.volley(false,true,100,0,10_000,0));
        assertFalse(NavalRules.volley(true,false,100,0,10_000,0));
        assertFalse(NavalRules.volley(true,true,15,0,10_000,0));
        assertFalse(NavalRules.volley(true,true,48*48+1,0,10_000,0));
        assertFalse(NavalRules.volley(true,true,100,1,10_000,0));
        assertFalse(NavalRules.volley(true,true,100,0,10_000,24));
        assertFalse(NavalRules.volley(true,true,100,0,10_000,-1));
    }

    @Test void shorelineDistanceUsesTheChunkEdgeIncludingNegativeCoordinates() {
        assertEquals(0,NavalRules.distanceToChunkSq(15,8,0,0));
        assertEquals(4,NavalRules.distanceToChunkSq(18,8,0,0));
        assertEquals(4,NavalRules.distanceToChunkSq(-18,-8,-1,-1));
        assertEquals(48*48,NavalRules.distanceToChunkSq(64,8,0,0));
    }
}
