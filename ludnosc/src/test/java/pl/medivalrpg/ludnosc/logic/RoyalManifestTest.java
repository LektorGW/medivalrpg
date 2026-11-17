// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;
import pl.medivalrpg.ludnosc.template.Placement;

class RoyalManifestTest {
    private RoyalManifest manifest(){return new Gson().fromJson(new InputStreamReader(getClass().getResourceAsStream("/royal-rebuild-preview.json"),StandardCharsets.UTF_8),RoyalManifest.class);}
    @Test void reviewedRoyalManifestMatchesTheExactAuthorisedCountryAndHousing() throws Exception {
        final RoyalManifest m=manifest();assertDoesNotThrow(m::validate);assertEquals(184,m.buildings.size());
        int housing=0,wells=0;
        for(final var b:m.buildings) {
            if(b.type.equals("palac_krola_maciusa")){assertEquals(4818,b.box.minX);assertEquals(1012,b.box.maxZ);continue;}
            final var template=BuiltinTemplates.load(b.type).rotated(b.rotation);
            assertTrue(template.meta().era()<=2,b.type);
            final var at=Placement.centered(m.world,template,b.x,b.groundY,b.z);final var box=at.box();
            assertEquals(b.box.minX,box.minX(),b.id);assertEquals(b.box.minZ,box.minZ(),b.id);
            assertEquals(b.box.maxX,box.maxX(),b.id);assertEquals(b.box.maxZ,box.maxZ(),b.id);
            assertTrue(m.contains(box.minX(),box.minZ())&&m.contains(box.maxX(),box.maxZ()),b.id);
            if(template.meta().function().housing())housing+=template.bedCount();
            if(b.type.equals("studnia"))wells++;
        }
        assertEquals(726,housing);assertEquals(9,wells);assertEquals(10000,m.provisions.foodStock);assertEquals(100,m.provisions.farmerTarget);
    }
    @Test void onlyDesired289ChunksAreFlattenedNotTheOldEasternWilderness() {
        var desired=RoyalManifest.desiredChunks();assertEquals(289,desired.size());
        assertTrue(desired.contains(RoyalManifest.key(295,53)));assertTrue(desired.contains(RoyalManifest.key(311,69)));
        assertFalse(desired.contains(RoyalManifest.key(312,61)));assertFalse(desired.contains(RoyalManifest.key(319,69)));
        for(long key:desired){assertTrue((int)(key>>32)>=295&&(int)(key>>32)<=311);assertTrue((int)key>=53&&(int)key<=69);}
    }
    @Test void wrongCountryWorldRectangleHeightsAndCapitalNeverPassPreflight() {
        var m=manifest();m.countryId=UUID.randomUUID();assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.world="world_nether";assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.rect.minChunkX++;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.clearTopY=300;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.foundationMinY=-64;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.capital.chunkX=319;assertThrows(IllegalArgumentException.class,m::validate);
    }
    @Test void everyReviewedRoadColumnUsesPositiveAlignmentAndStaysOutsideActualNativeFootprints() {
        final var m=manifest();final Set<Long> cells=new HashSet<>();
        for(final var road:m.roads){assertEquals("positive",road.alignment);cells.addAll(RoyalManifest.roadColumns(road));}
        assertFalse(cells.isEmpty());
        for(final long key:cells){int x=(int)(key>>32),z=(int)key;assertTrue(m.contains(x,z));
            for(final var b:m.buildings)assertFalse(x>=b.box.minX&&x<=b.box.maxX&&z>=b.box.minZ&&z<=b.box.maxZ,"road shifted into "+b.id+" at "+x+" "+z);
        }
    }
    @Test void positiveRoadDoesNotShiftWestOrFillTheBoundingBoxOfABentPolyline() {
        var r=new RoyalManifest.Road();r.width=3;r.alignment="positive";
        r.points=java.util.List.of(new int[]{10,10},new int[]{20,10},new int[]{20,20});
        final var cells=RoyalManifest.roadColumns(r);
        assertTrue(cells.contains(RoyalManifest.key(10,10)));assertTrue(cells.contains(RoyalManifest.key(22,22)));
        assertFalse(cells.contains(RoyalManifest.key(9,10)));assertFalse(cells.contains(RoyalManifest.key(15,15)));
    }
    @Test void malformedTemplatesDuplicateBuildingsAndRoadsOutsideCountryFail() {
        var m=manifest();m.buildings.get(1).id=m.buildings.getFirst().id;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.buildings.getFirst().type="../../other";assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.roads.getFirst().alignment="guess";assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.roads.getFirst().points.getFirst()[0]=4991;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.walls.gates.get(1).side=m.walls.gates.getFirst().side;assertThrows(IllegalArgumentException.class,m::validate);
    }
    @Test void stockAndProfessionChangesStayBoundedAndOptional() {
        var m=manifest();m.provisions=null;assertDoesNotThrow(m::validate);
        m=manifest();m.provisions.foodStock=-1;assertThrows(IllegalArgumentException.class,m::validate);
        m=manifest();m.provisions.farmerTarget=100000;assertThrows(IllegalArgumentException.class,m::validate);
    }
    @Test void a230CitizenCrowdSpreadsAcrossPlatformAndSquareWithoutCrammingOneBlock() {
        final java.util.Map<Long,Integer> platform=new java.util.HashMap<>(),square=new java.util.HashMap<>();
        for(int n=0;n<230;n++) {
            final int[] h=RoyalManifest.holdingCell(n),s=RoyalManifest.publicCell(n);assertEquals(163,h[1]);assertEquals(91,s[1]);
            platform.merge(RoyalManifest.key(h[0],h[2]),1,Integer::sum);square.merge(RoyalManifest.key(s[0],s[2]),1,Integer::sum);
        }
        assertEquals(225,platform.size());assertEquals(81,square.size());
        assertTrue(platform.values().stream().allMatch(n->n<=2));assertTrue(square.values().stream().allMatch(n->n<=3));
    }
    @Test void provisionOnlyChangesUnemployedAdultsUntilTheRequestedTarget() {
        assertTrue(RoyalManifest.mayBecomeFarmer(true,"minecraft:none",99,100));
        assertFalse(RoyalManifest.mayBecomeFarmer(true,"minecraft:none",100,100));
        assertFalse(RoyalManifest.mayBecomeFarmer(false,"minecraft:none",0,100));
        assertFalse(RoyalManifest.mayBecomeFarmer(true,"minecraft:fisherman",0,100));
        assertFalse(RoyalManifest.mayBecomeFarmer(true,"minecraft:weaponsmith",0,100));
    }
    @Test void accessCannotModifyOtherCountriesOrTheCityInteriorOrUnreviewedHeights() {
        assertTrue(RoyalManifest.accessCoordinate(4720,54,984));assertTrue(RoyalManifest.accessCoordinate(4991,158,984));
        assertFalse(RoyalManifest.accessCoordinate(4719,90,984));assertFalse(RoyalManifest.accessCoordinate(4992,90,984));
        assertFalse(RoyalManifest.accessCoordinate(4856,90,984));assertFalse(RoyalManifest.accessCoordinate(4720,53,984));
        assertFalse(RoyalManifest.accessCoordinate(4720,159,984));
    }
    @Test void freeRoyalPalaceCannotBeOrderedOrRepairedForAnotherCountry() {
        assertTrue(RoyalManifest.templateAllowed(RoyalManifest.COUNTRY,"palac_krola_maciusa"));
        assertFalse(RoyalManifest.templateAllowed(UUID.randomUUID(),"palac_krola_maciusa"));
        assertFalse(RoyalManifest.templateAllowed(UUID.randomUUID(),"PALAC_KROLA_MACIUSA"));
        assertFalse(RoyalManifest.templateAllowed(null,"palac_krola_maciusa"));
        assertTrue(RoyalManifest.templateAllowed(UUID.randomUUID(),"palac"));
        assertTrue(RoyalManifest.templateAllowed(UUID.randomUUID(),"chata"));
    }
}
