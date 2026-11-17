// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.economy.BankruptcyPlanner;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.model.*;
import pl.medivalrpg.kraje.storage.CountryCodec;

class LandingTest {
    private static ChunkPos p(int x,int z) {return new ChunkPos("world",x,z);}
    private static SpawnPoint spawn(ChunkPos p) {return new SpawnPoint(p.world(),p.centerBlockX(),64,p.centerBlockZ(),0,0);}
    private static CountryManager manager() {
        CountryManager m=new CountryManager(Logger.getAnonymousLogger());m.loadAll(List.of(),0);m.setEventSink(e->{});return m;
    }
    private static Country country(CountryManager m,String name,int x) {
        return m.create(UUID.randomUUID(),name,name,1,p(x,0),spawn(p(x,0)),ClaimCause.PLAYER);
    }
    private static Country land(CountryManager m,Country a) {
        Country b=country(m,"Enemy",100);m.claim(b,"world",p(99,0).key(),ClaimCause.ADMIN);
        var result=ConquestPlan.plan(a.claimsMap(),b.claimsMap(),b.capital(),p(99,0),true,false,true);
        assertTrue(result.possible());assertTrue(result.toAttacker());
        m.transferChunk(b,a,"world",p(99,0).key(),ClaimCause.CONQUEST,true);return b;
    }

    @Test void winningLandingTransfersDirectlyAndPersistsAcrossRestart() {
        var m=manager();Country a=country(m,"Home",0);
        List<ChunkOwnerChangeEvent> changes=new ArrayList<>();
        Country b=country(m,"Enemy",100);m.claim(b,"world",p(99,0).key(),ClaimCause.ADMIN);
        m.setEventSink(e->{if(e instanceof ChunkOwnerChangeEvent c) {
            assertEquals(a.id(),m.ownerAt("world",p(99,0).key()));
            assertTrue(a.bridgeheads().contains(p(99,0)));changes.add(c);
        }});
        m.transferChunk(b,a,"world",p(99,0).key(),ClaimCause.CONQUEST,true);
        assertEquals(1,changes.size());assertEquals(b.id(),changes.getFirst().oldOwner());assertEquals(a.id(),changes.getFirst().newOwner());
        Country restored=CountryCodec.decode(CountryCodec.encode(a));
        assertEquals(Set.of(p(99,0)),restored.bridgeheads());
        var reboot=manager();reboot.loadAll(List.of(restored,CountryCodec.decode(CountryCodec.encode(b))),0);
        assertEquals(a.id(),reboot.ownerAt("world",p(99,0).key()));
        assertEquals(Set.of(p(99,0)),reboot.get(a.id()).bridgeheads());
    }

    @Test void oldFilesLoadWithoutInventedBridgeheads() {
        var m=manager();Country a=country(m,"Home",0);var json=CountryCodec.encode(a);json.remove("bridgeheads");
        assertTrue(CountryCodec.decode(json).bridgeheads().isEmpty());
    }

    @Test void landingDoesNotBypassCapitalLastOrSplitDefender() {
        var attacker=Map.of("world",Set.of(p(0,0).key()));
        var defender=Map.of("world",Set.of(p(98,0).key(),p(99,0).key(),p(100,0).key()));
        assertEquals(ConquestPlan.Problem.CAPITAL_PROTECTED,ConquestPlan.plan(attacker,defender,p(100,0),p(100,0),true,false,true).problem());
        assertEquals(ConquestPlan.Problem.SPLITS_DEFENDER,ConquestPlan.plan(attacker,defender,p(100,0),p(99,0),true,false,true).problem());
        var last=ConquestPlan.plan(attacker,Map.of("world",Set.of(p(100,0).key())),p(100,0),p(100,0),true,false,true);
        assertTrue(last.defenderFalls());assertTrue(last.toAttacker(),"last overseas capital must not be razed");
    }

    @Test void normalConquestAndExpansionOperateFromExistingOutpost() {
        var m=manager();Country a=country(m,"Home",0);Country b=land(m,a);
        assertTrue(ChunkGraph.isAdjacent(a.claimsIn("world"),p(98,0).key()));
        m.claim(a,"world",p(98,0).key(),ClaimCause.PLAYER);
        var next=ConquestPlan.plan(a.claimsMap(),b.claimsMap(),b.capital(),p(100,0),true,false);
        assertTrue(next.possible());assertTrue(next.toAttacker());assertTrue(next.defenderFalls());
        assertTrue(ChunkGraph.staysConnected(a.claimsIn("world"),p(98,0).key(),p(0,0).key()));
        assertEquals(Set.of(p(99,0)),a.bridgeheads());
    }

    @Test void losingRootMovesItOnlyInsideSurvivingOutpostAndLastLossFreesSlot() {
        var m=manager();Country a=country(m,"Home",0);Country b=land(m,a);
        m.claim(a,"world",p(98,0).key(),ClaimCause.PLAYER);
        m.transferChunk(a,b,"world",p(99,0).key(),ClaimCause.CONQUEST);
        assertEquals(Set.of(p(98,0)),a.bridgeheads());
        m.unclaim(a,"world",p(98,0).key(),ClaimCause.PLAYER);assertTrue(a.bridgeheads().isEmpty());
        assertEquals(Set.of(p(0,0).key()),a.claimsIn("world"));
    }

    @Test void connectedOutpostsDeduplicateAndMainlandConnectionFreesSlot() {
        var m=manager();Country a=country(m,"Home",0);land(m,a);
        a.mutableBridgeheads().add(p(97,0));m.claim(a,"world",p(97,0).key(),ClaimCause.ADMIN);
        m.claim(a,"world",p(98,0).key(),ClaimCause.PLAYER);assertEquals(1,a.bridgeheads().size());
        List<Long> bridge=new ArrayList<>();for(int x=1;x<97;x++)bridge.add(p(x,0).key());
        m.claimAll(a,"world",bridge,ClaimCause.ADMIN);assertTrue(a.bridgeheads().isEmpty());
    }

    @Test void threeOutpostsBlockNewIslandsButAllowExpansionAndReleaseSlots() {
        var m=manager();Country a=country(m,"Home",0);land(m,a);
        for(int x:new int[]{150,200}) {
            m.claim(a,"world",p(x,0).key(),ClaimCause.ADMIN);a.mutableBridgeheads().add(p(x,0));
        }
        assertFalse(pl.medivalrpg.kraje.geometry.Bridgeheads.canEstablish(a,p(300,0),3));
        assertTrue(pl.medivalrpg.kraje.geometry.Bridgeheads.canEstablish(a,p(98,0),3));
        m.unclaim(a,"world",p(200,0).key(),ClaimCause.PLAYER);
        assertTrue(pl.medivalrpg.kraje.geometry.Bridgeheads.canEstablish(a,p(300,0),3));
    }

    @Test void defenderWithOverseasOutpostCanLoseEdgesButNotBridges() {
        var def=Map.of("world",Set.of(p(0,0).key(),p(98,0).key(),p(99,0).key(),p(100,0).key()));
        var att=Map.of("world",Set.of(p(101,0).key()));
        assertTrue(ConquestPlan.plan(att,def,p(0,0),p(100,0),true,false).possible());
        assertEquals(ConquestPlan.Problem.SPLITS_DEFENDER,ConquestPlan.plan(att,def,p(0,0),p(99,0),false,false).problem());
        assertEquals(ConquestPlan.Problem.CAPITAL_PROTECTED,ConquestPlan.plan(att,def,p(0,0),p(0,0),false,false,true).problem());
    }

    @Test void bankruptcyDoesNotSplitRemoteRingAtItsFarthestBridge() {
        // A U with an outer spur: Euclidean-farthest vertex can be an articulation point.
        var remote=new java.util.HashSet<Long>(Set.of(p(0,0).key(),p(10,0).key(),p(11,0).key(),p(12,0).key(),p(12,1).key(),p(12,-1).key()));
        for(var lost:BankruptcyPlanner.chunksToLose(Map.of("world",remote),p(0,0),20)) {
            int before=ChunkGraph.componentCount(remote);remote.remove(lost.key());assertTrue(ChunkGraph.componentCount(remote)<=before);
        }
        assertEquals(Set.of(p(0,0).key()),remote);
    }

    @Test void shoreNeedsDryTargetAndAdjacentReachableWater() {
        assertTrue(LandingCoast.coastal(0,0,(x,z)->new LandingCoast.Column(63,x<16 ? LandingCoast.Surface.DRY:LandingCoast.Surface.WATER)));
        assertFalse(LandingCoast.coastal(0,0,(x,z)->new LandingCoast.Column(x<16?80:63,x<16 ? LandingCoast.Surface.DRY:LandingCoast.Surface.WATER)));
        assertFalse(LandingCoast.coastal(0,0,(x,z)->new LandingCoast.Column(63,LandingCoast.Surface.WATER)),"open sea is no foothold");
        assertFalse(LandingCoast.coastal(0,0,(x,z)->new LandingCoast.Column(63,x<16 ? LandingCoast.Surface.DRY:LandingCoast.Surface.UNKNOWN)),"unloaded water never counts");
    }

    @Test void coastalScanHasBoundedUniqueColumnReadsAndHandlesNegativeChunks() {
        var seen=new java.util.HashSet<Long>();
        assertFalse(LandingCoast.coastal(-1,-1,(x,z)->{
            assertTrue(x>=-17&&x<=0&&z>=-17&&z<=0);
            assertTrue(seen.add(pl.medivalrpg.kraje.util.ChunkKeys.key(x,z)),"cached exactly once");
            return new LandingCoast.Column(63,LandingCoast.Surface.DRY);
        }));assertTrue(seen.size()<=320);assertTrue(seen.size()>=256);
    }
}
