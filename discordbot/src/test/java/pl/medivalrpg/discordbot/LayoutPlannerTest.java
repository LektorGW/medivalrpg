// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static pl.medivalrpg.discordbot.LayoutPlanner.*;
import java.util.*;
class LayoutPlannerTest {
    final List<Country> countries=List.of(new Country("pl","Polska",0xEE1122));
    State initial(){return new State(List.of(new Channel(ANNOUNCEMENTS,"announcements","text","old",0,"webhook-acl"),new Channel(COMMANDS,"Server-command","text",null,1,"webhook-acl"),new Channel("old","Important","category",null,0,"old")),List.of(new Role("white","Whitelisted",123,true),new Role("nonwhite","non-whitelisted",456,true),new Role("admin","👑Owner",0,true),new Role("bot","MedivalRPG",0,true)),Map.of(),List.of());}
    State extra(Channel... e){var s=initial();var c=new ArrayList<>(s.channels());c.addAll(List.of(e));return new State(c,s.roles(),s.ids(),List.of());}
    @Test void statsFirstAndSevenCategories(){var p=plan(initial(),countries);assertEquals("📊 Statistics",p.getFirst().value());assertEquals(7,p.stream().filter(o->o.kind()==Kind.CREATE_CATEGORY).count());}
    @Test void statsLockedVoices(){var p=plan(initial(),countries);assertEquals(3,p.stream().filter(o->o.kind()==Kind.CREATE_VOICE).count());assertEquals(3,p.stream().filter(o->o.kind()==Kind.PROFILE&&o.value().equals("stats")).count());}
    @Test void neverCreatesGuard(){assertFalse(plan(initial(),countries).stream().anyMatch(o->o.value().contains("raporty-serwera")));}
    @Test void adoptsRolesAboveBot(){var p=plan(initial(),countries);assertTrue(p.stream().anyMatch(o->o.kind()==Kind.BIND&&o.key().equals("player")&&o.target().equals("white")));assertTrue(p.stream().anyMatch(o->o.kind()==Kind.BIND&&o.key().equals("join")&&o.target().equals("nonwhite")));}
    @Test void neverChangesExistingRoles(){assertFalse(plan(initial(),countries).stream().anyMatch(o->o.kind()==Kind.ROLE_UPDATE||o.kind()==Kind.ROLE_ORDER));}
    @Test void onlyMissingRanksAndCountryCreated(){var p=plan(initial(),countries);assertEquals(4,p.stream().filter(o->o.kind()==Kind.CREATE_ROLE).count());assertFalse(p.stream().anyMatch(o->o.kind()==Kind.CREATE_ROLE&&Set.of("player","join").contains(o.key())));}
    @Test void missingLegacyRoleRefusesDuplicate(){var s=initial();assertThrows(IllegalStateException.class,()->plan(new State(s.channels(),List.of(),Map.of(),List.of()),countries));}
    @Test void ambiguousNameNeedsId(){var s=initial();var roles=new ArrayList<>(s.roles());roles.add(new Role("white2","Whitelisted",0,false));assertThrows(IllegalStateException.class,()->plan(new State(s.channels(),roles,Map.of(),List.of()),countries));assertDoesNotThrow(()->plan(new State(s.channels(),roles,Map.of("player","white"),List.of()),countries));}
    @Test void protectedChannelsOnlyMoveBindPosition(){for(var o:plan(initial(),countries))if(Set.of(ANNOUNCEMENTS,COMMANDS).contains(o.target()))assertTrue(Set.of(Kind.BIND,Kind.MOVE,Kind.POSITION).contains(o.kind()));}
    @Test void freshChannelsNeverAdoptOldGeneral(){var p=plan(extra(new Channel("general","general","text",null,0,"old")),countries);assertTrue(p.stream().anyMatch(o->o.kind()==Kind.CREATE_CHANNEL&&o.key().equals("channel:general")));assertFalse(p.stream().anyMatch(o->o.target().equals("general")));}
    @Test void applyPlanNeverDeletesOrArchivesOldChannels(){assertFalse(plan(extra(new Channel("extra","stary","text",null,0,"old")),countries).stream().anyMatch(o->o.target().equals("extra")));}
    @Test void serverInfoCreatedPublicAtEndOfInfo(){var p=plan(initial(),countries);assertTrue(p.stream().anyMatch(o->o.kind()==Kind.CREATE_CHANNEL&&o.key().equals("channel:serverinfo")&&o.target().equals("category:info")));assertTrue(p.stream().anyMatch(o->o.kind()==Kind.PROFILE&&o.key().equals("channel:serverinfo")&&o.value().equals("readonly")));assertTrue(p.stream().anyMatch(o->o.kind()==Kind.POSITION&&o.key().equals("channel:serverinfo")&&o.number()==5));}
    @Test void serverInfoLinks(){var i=ServerInfo.of("",ServerInfo.MAP_DEFAULT,ServerInfo.PANEL_DEFAULT);assertEquals(2,i.links().size());assertTrue(i.description().contains(ServerInfo.MAP_DEFAULT));assertFalse(i.description().contains("Adres"));
        var bad=ServerInfo.of("play.example.org","javascript:alert(1)","https://user:pass@example.com/");assertTrue(bad.links().isEmpty());assertTrue(bad.description().contains("play.example.org"));
        assertEquals(i.fingerprint(),ServerInfo.of(null,ServerInfo.MAP_DEFAULT,ServerInfo.PANEL_DEFAULT).fingerprint());assertNotEquals(i.fingerprint(),ServerInfo.of("","https://mapa.example/",ServerInfo.PANEL_DEFAULT).fingerprint());}
    @Test void existingPolishNamesRenamedToEnglish(){State fresh=simulate(initial(),plan(initial(),countries));Map<String,String> ids=fresh.ids();
        var channels=new ArrayList<Channel>();for(Channel c:fresh.channels())channels.add(c.id().equals(ids.get("channel:general"))?new Channel(c.id(),"ogolny",c.type(),c.parent(),c.position(),c.profile()):c.id().equals(ids.get("category:talk"))?new Channel(c.id(),"💬 Rozmowy",c.type(),c.parent(),c.position(),c.profile()):c);
        var p=plan(new State(channels,fresh.roles(),ids,List.of()),countries);
        assertTrue(p.stream().anyMatch(o->o.kind()==Kind.RENAME&&o.key().equals("channel:general")&&o.value().equals("💬-general")));assertTrue(p.stream().anyMatch(o->o.kind()==Kind.RENAME&&o.key().equals("category:talk")&&o.value().equals("💬 Chat")));
        assertEquals(2,p.size(),p.toString());assertTrue(plan(simulate(new State(channels,fresh.roles(),ids,List.of()),p),countries).isEmpty());}
    @Test void rulesSplitAndTitles(){var docs=new TreeMap<String,String>();docs.put("2-rp.md","# RP\n\n"+"a".repeat(3000)+"\n\n"+"b".repeat(3000));docs.put("1-ogolny.md","Bez tytułu\r\nlinia");docs.put("3-pusty.md","  ");
        var book=RulesBook.of(docs);assertEquals(3,book.pages().size());assertNull(book.pages().get(0).title());assertEquals("Bez tytułu\nlinia",book.pages().get(0).text());
        assertEquals("RP",book.pages().get(1).title());assertNull(book.pages().get(2).title());for(var page:book.pages())assertTrue(page.text().length()<=RulesBook.LIMIT);
        assertEquals(List.of("x".repeat(RulesBook.LIMIT),"x".repeat(100)),RulesBook.split("x".repeat(RulesBook.LIMIT+100)));
        assertEquals(book.fingerprint(),RulesBook.of(new TreeMap<>(docs)).fingerprint());docs.put("1-ogolny.md","zmiana");assertNotEquals(book.fingerprint(),RulesBook.of(docs).fingerprint());}
    @Test void bundledRulesFitDiscord()throws Exception{try(var in=getClass().getResourceAsStream("/regulamin/2-regulamin-rp.md")){var book=RulesBook.of(new TreeMap<>(Map.of("2.md",new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))));assertFalse(book.pages().isEmpty());assertTrue(book.pages().getFirst().title().contains("Regulamin"));for(var page:book.pages())assertTrue(page.text().length()<=RulesBook.LIMIT);}}
    @Test void verificationCreated(){assertTrue(plan(initial(),countries).stream().anyMatch(o->o.kind()==Kind.CREATE_CHANNEL&&o.key().equals("channel:verification")));}
    @Test void countryColorAndPrivateProfile(){var p=plan(initial(),countries);assertTrue(p.stream().anyMatch(o->o.kind()==Kind.CREATE_ROLE&&o.number()==0xEE1122));assertTrue(p.stream().anyMatch(o->o.kind()==Kind.PROFILE&&o.value().equals("country:pl")));}
    @Test void entireTicketCategoryProtected(){var p=CleanupPlanner.plan(extra(new Channel("tc","Ticket Tool","category",null,1,"old"),new Channel("t","ticket-1234","text","tc",0,"old"),new Channel("closed","closed-999","text","tc",1,"old"),new Channel("log","transkrypcje","text","tc",2,"old")));for(String id:List.of("tc","t","closed","log"))assertFalse(p.delete().stream().anyMatch(t->t.id().equals(id)));}
    @Test void occupiedVoiceAndParentProtected(){var p=CleanupPlanner.plan(extra(new Channel("vc","Muzyka","voice","old",2,"old",true)));assertFalse(p.delete().stream().anyMatch(t->Set.of("vc","old").contains(t.id())));}
    @Test void emptyVoiceAndTextDeletedBeforeCategory(){var p=CleanupPlanner.plan(extra(new Channel("vc","Muzyka","voice",null,2,"old",false),new Channel("txt","Welcome","text","old",2,"old")));assertTrue(p.delete().stream().anyMatch(t->t.id().equals("vc")));assertTrue(p.delete().stream().anyMatch(t->t.id().equals("txt")));assertEquals("category",p.delete().getLast().type());}
    @Test void newIdsProtected(){var s=extra(new Channel("fresh","nowy","text",null,0,"new"));assertFalse(CleanupPlanner.plan(new State(s.channels(),s.roles(),Map.of("channel:general","fresh"),List.of())).delete().stream().anyMatch(t->t.id().equals("fresh")));}
    @Test void fingerprintChanges(){assertNotEquals(CleanupPlanner.plan(initial()).fingerprint(),CleanupPlanner.plan(extra(new Channel("extra","stary","text",null,0,"old"))).fingerprint());}
    @Test void polishSlugs(){assertEquals("lodz-krolestwo",slug("Łódź Królestwo"));}
    @Test void boundedIntentFallback(){IntentFallback f=new IntentFallback();assertTrue(f.retryWithoutContent(4014,true));assertFalse(f.retryWithoutContent(4014,false));assertFalse(f.retryWithoutContent(4014,true));}
    @Test void freshPlanConvergesWithoutChangingLegacyRoles(){State state=initial();State done=simulate(state,plan(state,countries));assertTrue(plan(done,countries).isEmpty(),plan(done,countries).toString());assertTrue(done.roles().containsAll(state.roles()));}
    @Test void partialCreationCanResume(){State state=initial();var operations=plan(state,countries);for(int checkpoint:List.of(1,7,20,operations.size()/2)){State partial=simulate(state,operations.subList(0,checkpoint));State done=simulate(partial,plan(partial,countries));assertTrue(plan(done,countries).isEmpty(),"checkpoint "+checkpoint);}}
    State simulate(State before,List<Operation> operations){
        Map<String,Channel> channels=new LinkedHashMap<>();before.channels().forEach(c->channels.put(c.id(),c));Map<String,Role> roles=new LinkedHashMap<>();before.roles().forEach(r->roles.put(r.id(),r));Map<String,String> ids=new HashMap<>(before.ids());
        for(var op:operations){String id=op.target().isEmpty()?ids.get(op.key()):op.target();switch(op.kind()){
            case BIND->ids.put(op.key(),op.target());
            case CREATE_CATEGORY,CREATE_CHANNEL,CREATE_VOICE->{id="new:"+op.key();ids.put(op.key(),id);channels.put(id,new Channel(id,op.value(),op.kind()==Kind.CREATE_CATEGORY?"category":op.kind()==Kind.CREATE_VOICE?"voice":"text",ids.get(op.target()),99,"private"));}
            case CREATE_ROLE->{id="new:"+op.key();ids.put(op.key(),id);roles.put(id,new Role(id,op.value(),op.number(),false));}
            case RENAME,PROFILE,MOVE,POSITION->{Channel c=channels.get(id);channels.put(id,new Channel(id,op.kind()==Kind.RENAME?op.value():c.name(),c.type(),op.kind()==Kind.MOVE?ids.get(op.value()):c.parent(),op.kind()==Kind.POSITION?op.number():c.position(),op.kind()==Kind.PROFILE?op.value():c.profile()));}
            case ROLE_UPDATE,ROLE_ORDER->fail("Istniejące role są niemodyfikowalne");
        }}return new State(new ArrayList<>(channels.values()),new ArrayList<>(roles.values()),ids,List.of());
    }
}
