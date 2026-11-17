// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Public facts only. Raw provider log text and positions never enter the persistent outbox. */
public final class Chronicle {
    public enum Kind {
        WAR("⚔️ Wypowiedziano wojnę",3), PEACE("🕊️ Zawarto pokój / rozejm",3), ALLIANCE("🤝 Zawarto sojusz",2),
        ALLIANCE_END("💔 Zerwano sojusz",2), UNION("👑 Zawarto unię",3), UNION_END("👑 Rozwiązano unię",3),
        VASSAL("📜 Zawarto lenno / kapitulację",3), VASSAL_END("📜 Zakończono wasalstwo",3),
        SIEGE("🏰 Zakończono oblężenie",3), LAND("🗺️ Zmiana granic",1), CAPITAL("👑 Zmiana stolicy",3),
        CREATE("🌱 Powstał nowy kraj",2), FALL("🏚️ Upadek / rozwiązanie kraju",3),
        BATTLE("⚔️ Rozstrzygnięto bitwę",2), LANDING("⛵ Zdobyto przyczółek",2), REVOLT("🔥 Bunt na zdobytej ziemi",3), COALITION("🛡️ Powstała koalicja",2);
        final String title;final int importance;Kind(String title,int importance){this.title=title;this.importance=importance;}
    }
    public record Country(String id,String name,int color,boolean npc,String thumbnail) {}
    public record Fact(String id,long at,Kind kind,Country actor,Country other,String result,Integer losses) {
        public boolean npc(){return actor.npc()||(other!=null&&other.npc());}
    }
    public record Embed(String title,String description,int color,String thumbnail,int count) {}
    public static String safe(String text) {
        if(text==null)return "";
        return Policy.clean(text,160).replaceAll("[\\`*_~|<>]", "").replaceAll("https?://\\S+", "[link]")
            .replaceAll("(?i)(?:[xyz]\\s*[:=]?\\s*)?-?\\d+(?:[.,]\\d+)?(?:\\s*[,;:/]\\s*-?\\d+(?:[.,]\\d+)?)+", "[region]")
            .replaceAll("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d+", "[region]");
    }
    public static String thumbnail(String url) {
        try {URI u=URI.create(url);return "https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null?u.toASCIIString():null;}
        catch(Exception e){return null;}
    }
    public static String losses(int n){if(n<=0)return "brak strat";if(n<10)return "poniżej 10";return "około "+(Math.round(n/10.0)*10);}
    public static Embed format(List<Fact> facts) {
        if(facts.isEmpty())throw new IllegalArgumentException("Pusta kronika");
        Fact first=facts.getFirst();StringBuilder body=new StringBuilder();
        Map<String,Integer> rows=new LinkedHashMap<>();
        for(Fact f:facts){String line="**"+safe(f.actor.name())+(f.actor.npc()?" (NPC)":"")+"**"+(f.other==null?"":" — **"+safe(f.other.name())+(f.other.npc()?" (NPC)":"")+"**")+" — "+safe(f.result)+(f.losses==null?"":"; straty: "+losses(f.losses));rows.merge(line,1,Integer::sum);}
        int omitted=0;for(var row:rows.entrySet()){String line=row.getKey()+(row.getValue()>1?" ×"+row.getValue():"")+"\n";if(body.length()+line.length()<3400)body.append(line);else omitted+=row.getValue();}
        if(omitted>0)body.append("Pozostałe zdarzenia w grupie: ").append(omitted);
        return new Embed(facts.size()==1?first.kind.title:"📯 Kronika — "+facts.size()+" zdarzeń (5 min)",body.toString(),first.actor.color,thumbnail(first.actor.thumbnail),facts.size());
    }
    public static Embed daily(List<Fact> facts,String date) {
        long wars=facts.stream().filter(f->f.kind==Kind.WAR).count(),lands=facts.stream().filter(f->f.kind==Kind.LAND).count();
        StringBuilder text=new StringBuilder("Okres kończy się o 21:00 (Europe/Warsaw).\nNowe wojny: "+wars+" — zmiany ziem: "+lands+" — zdarzenia: "+facts.size()+"\n");
        var battles=facts.stream().filter(f->Set.of(Kind.BATTLE,Kind.SIEGE,Kind.LANDING).contains(f.kind)).sorted(Comparator.comparingInt((Fact f)->f.losses==null?-1:f.losses).reversed()).limit(3).toList();
        if(battles.isEmpty())text.append("Brak zarejestrowanych zakończonych bitew.");
        else for(Fact f:battles)text.append("• ").append(safe(f.actor.name())).append(": ").append(safe(f.result)).append(f.losses==null?" (straty nieznane)":"; "+losses(f.losses)).append("\n");
        return new Embed("📜 Kronika dnia — "+date,text.toString(),0xBC923C,null,facts.size());
    }
    public static ZonedDateTime cutoff(Instant now){ZonedDateTime t=now.atZone(ZoneId.of("Europe/Warsaw"));ZonedDateTime c=t.toLocalDate().atTime(21,0).atZone(t.getZone());return t.isBefore(c)?c.minusDays(1):c;}
    public static Fact coalition(Collection<Country> countries,long at,String text){
        for(Country a:countries)for(Country b:countries)if(!a.id.equals(b.id)&&text.equals(a.name+" dołącza do koalicji przeciw "+b.name))
            return new Fact("coalition:"+a.id+":"+b.id+":"+at,at,Kind.COALITION,a,b,"Pierwszy kraj dołączył do koalicji przeciw drugiemu.",null);
        return null;
    }
    public static Fact withOpponent(Fact fact,Collection<Country> countries,String raw){
        if(fact==null)return null;
        for(Country other:countries)if(!other.id.equals(fact.actor.id)&&
            (raw.contains(" od "+other.name+" (")||raw.contains(" przed "+other.name+" (")))
            return new Fact(fact.id,fact.at,fact.kind,fact.actor,other,fact.result,fact.losses);
        return fact;
    }
    /** Strict allowlist of terminal Armia log templates; never forward source text, troop counts or plans. */
    public static Fact log(Country country,long at,String raw) {
        Kind kind;String result;Integer lost=null;
        if(raw.startsWith("ZDOBYTO w tle chunk ")){kind=raw.contains("(przyczółek,")?Kind.LANDING:Kind.BATTLE;result=kind==Kind.LANDING?"Zakończona bitwa o przyczółek: zdobyto ziemię.":"Zwycięstwo w zakończonej bitwie; zdobyto ziemię.";Matcher m=Pattern.compile("straty (\\d+) / (\\d+)\\)").matcher(raw);if(m.find())try{lost=(int)Math.min(1000000L,Long.parseLong(m.group(1))+Long.parseLong(m.group(2)));}catch(NumberFormatException ignored){}}
        else if(raw.startsWith("Obroniono ")){kind=Kind.BATTLE;result="Zwycięstwo obrońców; odparto ofensywę.";}
        else if(raw.startsWith("BUNT na zdobytej ziemi ")){kind=Kind.REVOLT;result="Zakończony bunt zmienił właściciela ziemi.";}
        else return null;
        return new Fact("log:"+country.id+":"+at+":"+LinkStore.hash(raw),at,kind,country,null,result,lost);
    }
}
