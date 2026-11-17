// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class PolicyTest {
    Map<String,String> ids=Map.of("player","1000000000000000846","ruler","1000000000000000847","advisor","1000000000000000848",
        "citizen","1000000000000000849","country:one","1000000000000000850","country:two","1000000000000000851");
    @Test void unlinkedGetsNoRoles() {assertTrue(Policy.wanted(false,"one","ruler",ids).isEmpty());}
    @Test void noCountryGetsOnlyPlayer() {assertEquals(Set.of(ids.get("player")),Policy.wanted(true,null,null,ids));}
    @Test void rulerMapping() {assertEquals(Set.of(ids.get("player"),ids.get("country:one"),ids.get("ruler")),Policy.wanted(true,"one","ruler",ids));}
    @Test void advisorMapping() {assertTrue(Policy.wanted(true,"one","advisor",ids).contains(ids.get("advisor")));}
    @Test void citizenMapping() {assertFalse(Policy.wanted(true,"one","citizen",ids).contains(ids.get("ruler")));}
    @Test void countryTransferRemovesOnlyManagedRoles() {
        // A fake Discord member has a moderator role that this bot must never remove.
        Set<String> fakeMember=new HashSet<>(Policy.wanted(true,"one","ruler",ids));fakeMember.add("unmanaged-moderator");
        Set<String> desired=Policy.wanted(true,"two","citizen",ids);
        Set<String> removed=Policy.removals(fakeMember,new HashSet<>(ids.values()),desired);
        assertEquals(Set.of(ids.get("country:one"),ids.get("ruler")),removed);
        fakeMember.removeAll(removed);fakeMember.addAll(desired);
        assertTrue(fakeMember.contains("unmanaged-moderator"));assertTrue(fakeMember.contains(ids.get("country:two")));
    }
    @Test void missingMappingsAreSafe() {assertTrue(Policy.wanted(true,"one","ruler",Map.of("player","garbage")).isEmpty());}
    @Test void mentionsAndMinecraftMarkupAreNeutralized() {
        String s=Policy.clean("@everyone @here <@123> <@&123> <red> §k\nhello",1000);
        assertFalse(s.contains("@"));assertFalse(s.contains("<"));assertFalse(s.contains("§"));assertFalse(s.contains("\n"));
    }
    @Test void lengthBounded() {assertEquals(300,Policy.clean("x".repeat(5000),300).length());}
    @Test void markdownCannotHideContent() {assertFalse(Policy.clean("```**_abc_**```",100).contains("`"));}
    @Test void rateLimitAndRecovery() {
        AtomicLong time=new AtomicLong();RateGate gate=new RateGate(time::get);
        assertTrue(gate.allow("a",2,60000));assertTrue(gate.allow("a",2,60000));assertFalse(gate.allow("a",2,60000));
        assertTrue(gate.allow("b",2,60000));time.set(60000);assertTrue(gate.allow("a",2,60000));
    }
    @Test void privateReportCopiesOnlyNumbers() {
        String result=GuardSummary.extract("TPS: średnio **19,8** · token=secret\nObywatele: **955**\nŻołnierze: **20 / 1000**\nERROR password=secret @everyone https://discord.com/api/webhooks/secret");
        assertTrue(result.contains("955"));assertTrue(result.contains("20 / 1000"));assertFalse(result.contains("secret"));assertFalse(result.contains("http"));
    }
}
