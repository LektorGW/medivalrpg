// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleTest {
    @TempDir Path temp;
    final Chronicle.Country player=new Chronicle.Country("a","Królestwo Polski",0xCC0000,false,"https://example.org/flag.png");
    final Chronicle.Country npc=new Chronicle.Country("b","Czechy",0x0000CC,true,null);
    Chronicle.Fact fact(String id,long at){return new Chronicle.Fact(id,at,Chronicle.Kind.WAR,player,npc,"Wypowiedziano wojnę.",null);}
    ChronicleJournal journal()throws Exception{Class.forName("org.sqlite.JDBC");return new ChronicleJournal("jdbc:sqlite:"+temp.resolve("links.db"));}
    @Test void colorAndThumbnailComeFromActor(){var e=Chronicle.format(List.of(fact("1",0)));assertEquals(0xCC0000,e.color());assertEquals("https://example.org/flag.png",e.thumbnail());}
    @Test void bothPlayerAndNpcAreNamed(){String s=Chronicle.format(List.of(fact("1",0))).description();assertTrue(s.contains("Polski"));assertTrue(s.contains("Czechy (NPC)"));}
    @Test void hostileMentionsAndPositionsAreRedacted(){String s=Chronicle.safe("@everyone <@123456789012345678> baza -123, 456 x=999 y: 90");assertFalse(s.contains("@everyone"));assertFalse(s.contains("123,"));assertFalse(s.contains("999"));}
    @Test void unsafeThumbnailIsOmitted(){for(String url:List.of("http://example.org/f.png","file:///secret","https://u:p@example.org/f.png","javascript:x"))assertNull(Chronicle.thumbnail(url));}
    @Test void absentThumbnailIsSafe(){assertNull(Chronicle.thumbnail(null));}
    @Test void tokenizedThumbnailIsRejected(){assertNull(Chronicle.thumbnail("https://example.org/flag?token=synthetic"));}
    @Test void completedBridgeheadIsReportedWithoutLiveTroops(){var f=Chronicle.log(npc,0,"ZDOBYTO w tle chunk world:1,2 od Polska (przyczółek, łup 1 zł, straty 3 / 7)");assertEquals(Chronicle.Kind.LANDING,f.kind());assertFalse(f.result().contains("1,2"));assertEquals(10,f.losses());}
    @Test void coalitionRequiresAnExactCompletedHistoryEntry(){var f=Chronicle.coalition(List.of(player,npc),0,"Królestwo Polski dołącza do koalicji przeciw Czechy");assertNotNull(f);assertEquals(Chronicle.Kind.COALITION,f.kind());assertNull(Chronicle.coalition(List.of(player,npc),0,"Hegemonia: Czechy — inne kraje tworzą koalicję"));}
    @Test void emptyGroupRejected(){assertThrows(IllegalArgumentException.class,()->Chronicle.format(List.of()));}
    @Test void groupingCollapsesIdenticalRows(){var e=Chronicle.format(List.of(fact("1",0),fact("2",1)));assertEquals(2,e.count());assertTrue(e.description().contains("×2"));}
    @Test void embedCannotOverflowDiscord(){var facts=new ArrayList<Chronicle.Fact>();for(int i=0;i<500;i++)facts.add(new Chronicle.Fact("f"+i,i,Chronicle.Kind.LAND,player,npc,"Ziemia "+i+" "+"a".repeat(200),null));assertTrue(Chronicle.format(facts).description().length()<4096);}
    @Test void lossesAreApproximate(){assertEquals("poniżej 10",Chronicle.losses(7));assertEquals("około 30",Chronicle.losses(27));assertEquals("brak strat",Chronicle.losses(0));}
    @Test void conquestTemplateIsTerminalAndPrivate(){var f=Chronicle.log(npc,10,"ZDOBYTO w tle chunk world:123,456 od Polska (zdobyta, łup 12 zł, straty 17 / 8)");assertNotNull(f);assertEquals(25,f.losses());assertFalse(f.result().contains("123"));assertFalse(f.id().contains("123,456"));assertEquals(Chronicle.Kind.BATTLE,f.kind());}
    @Test void defendedTemplateIsAccepted(){assertNotNull(Chronicle.log(npc,10,"Obroniono world:123,456 przed Polska (odparci)"));}
    @Test void opponentComesOnlyFromKnownCountryNames(){String raw="ZDOBYTO w tle chunk world:1,2 od Królestwo Polski (zdobyta, straty 1 / 1)";var f=Chronicle.withOpponent(Chronicle.log(npc,0,raw),List.of(player,npc),raw);assertEquals(player,f.other());assertFalse(Chronicle.format(List.of(f)).description().contains("1,2"));}
    @Test void revoltTemplateIsAcceptedWithoutCoordinates(){var f=Chronicle.log(npc,10,"BUNT na zdobytej ziemi 23,45 — przechodzi do: Polska");assertEquals(Chronicle.Kind.REVOLT,f.kind());assertFalse(f.result().contains("23"));}
    @Test void liveOrdersAndRoundsNeverLeaveTheProvider(){for(String line:List.of("DESANT na Polska (1,2): 40 żołnierzy walczy o przyczółek","Bitwa o world:1,2 (runda 3): siły 40 vs 20, straty 2 / 1","ALARM: wrogi statek kraju Polska przy wybrzeżu","Ofensywa w tle na Polska","Koniec ofensywy w tle: wraca 20 żołnierzy","propozycja KAPITULACJI","przygotowano taran"))assertNull(Chronicle.log(npc,10,line),line);}
    @Test void restartDeduplicationIsDurable()throws Exception{try(var j=journal()){assertTrue(j.add(fact("same",0)));}try(var j=journal()){assertFalse(j.add(fact("same",0)));assertEquals(1,j.pendingCount());}}
    @Test void fiveMinuteWindowWaits()throws Exception{try(var j=journal()){j.add(fact("a",100));assertTrue(j.batch(300099).isEmpty());assertEquals(1,j.batch(300100).size());}}
    @Test void oneBucketExcludesFollowingWindow()throws Exception{try(var j=journal()){j.add(fact("a",100));j.add(fact("b",300100));assertEquals(List.of("a"),j.batch(700000).stream().map(Chronicle.Fact::id).toList());}}
    @Test void reservedMessageIsNotResentOnRestart()throws Exception{try(var j=journal()){j.add(fact("a",0));assertTrue(j.reserve(j.batch(300000),null,300000));}try(var j=journal()){assertTrue(j.batch(600000).isEmpty());}}
    @Test void twentyAttemptsPerRollingHourIncludingDaily()throws Exception{try(var j=journal()){for(int i=0;i<20;i++){j.report(i,Chronicle.daily(List.of(),"test"));assertTrue(j.reserve(List.of(),(long)i,1000+i));}j.add(fact("a",0));assertFalse(j.reserve(j.batch(300000),null,300000));assertTrue(j.reserve(j.batch(3601000),null,3601000));}}
    @Test void reportsAreExactlyOncePerCutoff()throws Exception{try(var j=journal()){assertTrue(j.report(100,Chronicle.daily(List.of(),"test")));assertFalse(j.report(100,Chronicle.daily(List.of(),"test")));assertTrue(j.reserve(List.of(),100L,0));assertNull(j.pendingReport());}}
    @Test void alreadyReservedFactCannotConsumeAnotherRateSlot()throws Exception{try(var j=journal()){var f=fact("a",0);j.add(f);assertTrue(j.reserve(List.of(f),null,300000));assertFalse(j.reserve(List.of(f),null,300001));}}
    @Test void dailyUsesHalfOpenInterval()throws Exception{try(var j=journal()){j.add(fact("a",100));j.add(fact("b",200));assertEquals(List.of("a"),j.between(100,200).stream().map(Chronicle.Fact::id).toList());}}
    @Test void reportsCountWarsAndBordersSeparately(){var land=new Chronicle.Fact("l",0,Chronicle.Kind.LAND,player,npc,"Ziemia",null);String text=Chronicle.daily(List.of(fact("a",0),land),"2026-10-30").description();assertTrue(text.contains("Nowe wojny: 1"));assertTrue(text.contains("zmiany ziem: 1"));}
    @Test void biggestBattlesAreSortedByKnownLosses(){var big=new Chronicle.Fact("big",0,Chronicle.Kind.BATTLE,player,npc,"Duża bitwa",123);var small=new Chronicle.Fact("small",0,Chronicle.Kind.BATTLE,player,npc,"Mała bitwa",12);String text=Chronicle.daily(List.of(small,big),"test").description();assertTrue(text.indexOf("Duża")<text.indexOf("Mała"));assertTrue(text.contains("około 120"));}
    @Test void beforeNinePmUsesPreviousCalendarDay(){assertEquals("2026-10-29",Chronicle.cutoff(Instant.parse("2026-10-30T19:59:59Z")).toLocalDate().toString());}
    @Test void ninePmWarsawIsTwentyUtcInWinter(){assertEquals("2026-10-30",Chronicle.cutoff(Instant.parse("2026-10-30T20:00:00Z")).toLocalDate().toString());}
    @Test void summerAndDstHaveCalendarWindows(){var c=Chronicle.cutoff(Instant.parse("2026-10-25T20:00:00Z"));assertEquals(25,Duration.between(c.minusDays(1),c).toHours());assertEquals(21,c.getHour());}
    @Test void pruneNeverLosesPendingFacts()throws Exception{try(var j=journal()){j.add(fact("a",0));j.prune(30L*86400000);assertEquals(1,j.pendingCount());}}
    @Test void newChronicleChannelIsPlannedWithPublicReadOnlyProfile(){var initial=new LayoutPlanner.State(List.of(new LayoutPlanner.Channel(LayoutPlanner.ANNOUNCEMENTS,"announcements","text",null,0,"preserve"),new LayoutPlanner.Channel(LayoutPlanner.COMMANDS,"Server-command","text",null,1,"preserve")),List.of(new LayoutPlanner.Role("w","Whitelisted",0,false),new LayoutPlanner.Role("n","non-whitelisted",0,false)),Map.of(),List.of());var ops=LayoutPlanner.plan(initial,List.of());assertTrue(ops.stream().anyMatch(o->o.key().equals("channel:chronicle")&&o.kind()==LayoutPlanner.Kind.CREATE_CHANNEL&&o.target().equals("category:server")));assertTrue(ops.stream().anyMatch(o->o.key().equals("channel:chronicle")&&o.kind()==LayoutPlanner.Kind.PROFILE&&o.value().equals("chronicle")));}
}
