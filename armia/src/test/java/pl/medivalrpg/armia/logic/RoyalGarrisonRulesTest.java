// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.model.SoldierRecord;

class RoyalGarrisonRulesTest {
    @Test void retiredBrainsStayRetiredAfterReleaseAndReloadWithBoundedHistory() {
        final SoldierRecord record=new SoldierRecord(RoyalGarrisonRules.COUNTRY,"rycerz",null,4);
        final UUID old=UUID.randomUUID(),current=UUID.randomUUID();
        record.health=17.5;record.royalEvacuated=true;record.retireRoyalBrain(old);
        record.royalEvacuated=false;record.brain=current;
        final SoldierRecord loaded=new Gson().fromJson(new Gson().toJson(record),SoldierRecord.class);
        assertTrue(loaded.retiredRoyalBrain(old));assertFalse(loaded.retiredRoyalBrain(current));
        assertFalse(loaded.suspended());assertEquals(current,loaded.brain);assertEquals(17.5,loaded.health);
        loaded.retireRoyalBrain(old);assertEquals(1,loaded.royalRetiredBrains.size());
        UUID last=null;for(int i=0;i<32;i++) {last=UUID.randomUUID();loaded.retireRoyalBrain(last);}
        assertEquals(16,loaded.royalRetiredBrains.size());assertTrue(loaded.retiredRoyalBrain(last));
    }
    private static final Map<String,Integer> ERAS=Map.of("lucznik",1,"kusznik",1,"rycerz",1,"katapulta",1,"muszkieter",2,"artyleria",3);
    private RoyalGarrisonRules.Post post(final String id,final String type,final int x) {return new RoyalGarrisonRules.Post(id,type,x,70,0,4,0);}
    private RoyalGarrisonRules.Candidate soldier(final int id,final String type,final boolean cargo,final String binding) {
        return new RoyalGarrisonRules.Candidate(new UUID(0,id),RoyalGarrisonRules.COUNTRY,type,ERAS.get(type),type.equals("rycerz"),
            type.equals("katapulta") || type.equals("artyleria"),cargo,false,binding);
    }
    @Test void theActualRoyalArmyShapeReusesThirtyTwoAndPreservesTenCatapultsAcrossReplay() {
        final List<RoyalGarrisonRules.Candidate> army=new ArrayList<>();final List<RoyalGarrisonRules.Post> posts=new ArrayList<>();
        for(int i=0;i<10;i++) army.add(soldier(i+1,"katapulta",false,null));
        for(int i=0;i<32;i++) {
            final String type=i<10?"kusznik":i<20?"lucznik":"rycerz";
            army.add(soldier(i+11,type,false,null));posts.add(post("gwardia_"+i,type,i));
        }
        final var request=new RoyalGarrisonRules.Request(RoyalGarrisonRules.COUNTRY,"world",List.copyOf(posts));
        final var first=RoyalGarrisonRules.plan(request,army,258,42,1000,60);
        assertEquals(0,first.creates());assertEquals(32,first.assignments().size());
        final Map<UUID,String> bindings=new HashMap<>();
        first.assignments().forEach(a->{assertTrue(a.soldier().getLeastSignificantBits()>10);bindings.put(a.soldier(),a.post().id());});
        final List<RoyalGarrisonRules.Candidate> reloaded=army.stream().map(c->new RoyalGarrisonRules.Candidate(c.id(),c.country(),c.type(),c.era(),
            c.mounted(),c.artillery(),c.embarked(),c.mercenary(),bindings.get(c.id()))).collect(java.util.stream.Collectors.toList());
        Collections.reverse(reloaded);
        assertEquals(first,RoyalGarrisonRules.plan(request,reloaded,258,42,1000,60));
    }
    @Test void cargoForeignMercenaryAndModernSoldiersAreNeverMovedOrUsedToEvadeCaps() {
        final var cargo=soldier(1,"lucznik",true,"palac");
        final var foreign=new RoyalGarrisonRules.Candidate(new UUID(0,2),UUID.randomUUID(),"lucznik",1,false,false,false,false,null);
        final var mercenary=new RoyalGarrisonRules.Candidate(new UUID(0,3),RoyalGarrisonRules.COUNTRY,"lucznik",1,false,false,false,true,null);
        final var modern=soldier(4,"artyleria",false,null);
        final var request=new RoyalGarrisonRules.Request(RoyalGarrisonRules.COUNTRY,"world",List.of(post("palac","lucznik",1)));
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.plan(request,List.of(cargo,foreign,mercenary,modern),1000,60,5000,500));
        final var plan=RoyalGarrisonRules.plan(request,List.of(cargo,foreign,mercenary,modern),999,59,5000,500);
        assertEquals(1,plan.creates());assertNull(plan.assignments().getFirst().soldier());
    }
    @Test void footFallbackPrecedesMountedAndExistingBindingsWinWithoutCreatingDuplicates() {
        final var request=new RoyalGarrisonRules.Request(RoyalGarrisonRules.COUNTRY,"world",List.of(post("brama","muszkieter",1)));
        final var foot=soldier(2,"lucznik",false,null);final var knight=soldier(1,"rycerz",false,null);
        assertEquals(foot.id(),RoyalGarrisonRules.plan(request,List.of(knight,foot),1000,60,1000,60).assignments().getFirst().soldier());
        final var bound=soldier(1,"rycerz",false,"brama");
        assertEquals(bound.id(),RoyalGarrisonRules.plan(request,List.of(bound,foot),1000,60,1000,60).assignments().getFirst().soldier());
    }
    @Test void strictJsonRejectsOtherCountriesWorldsModernTypesDuplicateIdsAndNonFiniteCoordinates() {
        final String valid="{\"countryId\":\""+RoyalGarrisonRules.COUNTRY+"\",\"posts\":[{\"id\":\"brama\",\"type\":\"lucznik\",\"x\":1.5,\"y\":70,\"z\":2.5,\"radius\":4}]}";
        assertEquals("world",RoyalGarrisonRules.parse(valid,"world",ERAS).world());
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(valid.replace(RoyalGarrisonRules.COUNTRY.toString(),UUID.randomUUID().toString()),"world",ERAS));
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(valid.replace("\"posts\"","\"world\":\"nether\",\"posts\""),"world",ERAS));
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(valid.replace("lucznik","artyleria"),"world",ERAS));
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(valid.replace("1.5","1e999"),"world",ERAS));
        final String duplicate=valid.replace("}]}","},{\"id\":\"brama\",\"type\":\"lucznik\",\"x\":4,\"y\":70,\"z\":3}]}");
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(duplicate,"world",ERAS));
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.parse(valid.replace("\"radius\":4","\"radius\":32"),"world",ERAS));
    }
    @Test void stableRoyalPostBindingSurvivesTheExistingSoldierJsonWithoutAffectingLegacySoldiers() {
        final Gson gson=new Gson();final SoldierRecord legacy=gson.fromJson("{\"type\":\"lucznik\"}",SoldierRecord.class);
        legacy.normalize();assertNull(legacy.royalPost);assertNull(legacy.transportShip);
        legacy.royalPost="palac_zachod";
        assertEquals("palac_zachod",gson.fromJson(gson.toJson(legacy),SoldierRecord.class).royalPost);
    }
    @Test void artilleryHasSeparateStablePostsAndRoyalHoldSurvivesWithoutMasqueradingAsNavalCargo() {
        final var request=new RoyalGarrisonRules.Request(RoyalGarrisonRules.COUNTRY,"world",List.of(post("palac","lucznik",1)),
            List.of(post("siege_01","katapulta",4)));
        final var army=List.of(soldier(1,"lucznik",false,null),soldier(2,"katapulta",false,null));
        assertEquals(new UUID(0,1),RoyalGarrisonRules.plan(request,army,2,2,1000,60).assignments().getFirst().soldier());
        assertEquals(new UUID(0,2),RoyalGarrisonRules.siegePlan(request,army,2,2,1000,60).assignments().getFirst().soldier());
        final var guards=RoyalGarrisonRules.plan(request,army,2,2,1000,60).assignments();
        assertThrows(IllegalArgumentException.class,()->RoyalGarrisonRules.requireCoverage(army.stream().map(RoyalGarrisonRules.Candidate::id).toList(),guards));
        final List<RoyalGarrisonRules.Assignment> complete=new ArrayList<>(guards);
        complete.addAll(RoyalGarrisonRules.siegePlan(request,army,2,2,1000,60).assignments());
        assertDoesNotThrow(()->RoyalGarrisonRules.requireCoverage(army.stream().map(RoyalGarrisonRules.Candidate::id).toList(),complete));
        final SoldierRecord held=new SoldierRecord(RoyalGarrisonRules.COUNTRY,"katapulta",null,8);held.health=17;held.royalEvacuated=true;
        final Gson gson=new Gson();final SoldierRecord reloaded=gson.fromJson(gson.toJson(held),SoldierRecord.class);
        assertTrue(reloaded.suspended());assertTrue(reloaded.royalEvacuated);assertNull(reloaded.transportShip);assertEquals(17,reloaded.health);
        reloaded.royalEvacuated=false;assertFalse(reloaded.suspended());
        reloaded.transportShip=UUID.randomUUID();assertTrue(reloaded.suspended());
    }
}
