// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import com.google.gson.JsonParser;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;

/** Optional provider classes are resolved with their own class loaders. Main-thread reads only. */
final class ChronicleHooks implements Listener {
    private final DiscordBotPlugin plugin;
    private final Map<String,Chronicle.Country> known=new HashMap<>();
    private final Map<String,String> capitals=new HashMap<>();
    private final long started=System.currentTimeMillis();
    private long lastWarning;
    ChronicleHooks(DiscordBotPlugin plugin){this.plugin=plugin;}
    void register(){
        var kraje=Bukkit.getPluginManager().getPlugin("Kraje");
        if(kraje==null){plugin.getLogger().info("Kronika: Kraje niedostępne; oczekuję na fakty bez połączenia z Discordem.");return;}
        for(String name:List.of("CountryCreateEvent","CountryDisbandEvent","RelationChangeEvent","ChunkOwnerChangeEvent","SiegeStartEvent","SiegeEndEvent","UnionFormedEvent","UnionDissolvedEvent","VassalageStartEvent","VassalageEndEvent"))try{
            Class<? extends Event> type=Class.forName("pl.medivalrpg.kraje.api.event."+name,true,kraje.getClass().getClassLoader()).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(l,e)->{
                if(!type.isInstance(e))return;
                if(!Bukkit.isPrimaryThread()){plugin.getLogger().warning("Kronika: pominięto zdarzenie spoza głównego wątku.");return;}
                try{event(name,e);}catch(Exception ex){warn();}
            },plugin,true);
        }catch(ReflectiveOperationException|LinkageError e){plugin.getLogger().warning("Kronika: opcjonalny event niedostępny: "+name);}
        poll();
    }
    private void warn(){long now=System.currentTimeMillis();if(now-lastWarning>=60000){lastWarning=now;plugin.getLogger().warning("Kronika: niepełne dane dostawcy. Pominięto niezweryfikowany fakt; bez publikowania surowych logów.");}}
    private Object call(Object o,String method)throws ReflectiveOperationException{return GameBridge.call(o,method);}
    private Chronicle.Country country(Object id)throws Exception {
        if(id==null)return null;String key=id.toString();
        Object api=GameBridge.service("Kraje","pl.medivalrpg.kraje.api.KrajeApi");
        if(api!=null){var opt=(Optional<?>)GameBridge.call(api,"getCountry",UUID.fromString(key));if(opt.isPresent())return remember(opt.get());}
        return known.getOrDefault(key,new Chronicle.Country(key,"Nieznany kraj",0xBC923C,true,null));
    }
    private Chronicle.Country remember(Object info)throws Exception {
        String id=call(info,"id").toString();
        // The panel currently uses authenticated RPC/base64, not a public image endpoint.
        // Only an explicitly configured public HTTPS thumbnail may be exposed to Discord.
        String template=plugin.getConfig().getString("chronicle.flag-url-template","");
        String thumbnail=template.isBlank()?null:Chronicle.thumbnail(template.replace("{id}",id));
        var c=new Chronicle.Country(id,Chronicle.safe(call(info,"name").toString()),(int)GameBridge.call(call(info,"color"),"value"),(boolean)call(info,"npc"),thumbnail);
        known.put(id,c);return c;
    }
    private void submit(String id,Chronicle.Kind kind,Chronicle.Country a,Chronicle.Country b,String text,Integer losses,long at){
        if(a==null)return;var fact=new Chronicle.Fact(id,at,kind,a,b,text,losses);
        if(!plugin.enqueue(()->plugin.chronicleAccept(fact)))warn();
    }
    private void event(String name,Event e)throws Exception {
        if(!plugin.getConfig().getBoolean("chronicle.enabled",true))return;
        long now=System.currentTimeMillis();Chronicle.Country a,b=null;Chronicle.Kind kind;String result,key;
        switch(name){
            case "CountryCreateEvent","CountryDisbandEvent"->{
                Object id=call(e,"countryId");a=country(id);
                if(name.equals("CountryDisbandEvent"))a=new Chronicle.Country(id.toString(),Chronicle.safe(call(e,"name").toString()),a.color(),(boolean)call(e,"npc"),a.thumbnail());
                else a=new Chronicle.Country(a.id(),a.name(),a.color(),(boolean)call(e,"npc"),a.thumbnail());
                kind=name.equals("CountryCreateEvent")?Chronicle.Kind.CREATE:Chronicle.Kind.FALL;result=kind==Chronicle.Kind.CREATE?"Utworzono nowe państwo.":"Państwo zakończyło istnienie.";key=name+":"+id;
            }
            case "RelationChangeEvent"->{
                a=country(call(e,"countryA"));b=country(call(e,"countryB"));String old=call(e,"oldRelation").toString(),next=call(e,"newRelation").toString();
                if(next.equals(old))return;
                kind=next.equals("WAR")?Chronicle.Kind.WAR:next.equals("ALLIANCE")?Chronicle.Kind.ALLIANCE:old.equals("WAR")?Chronicle.Kind.PEACE:Chronicle.Kind.ALLIANCE_END;
                result=kind.title.substring(kind.title.indexOf(' ')+1)+".";
                key="relation:"+(a.id().compareTo(b.id())<0?a.id()+":"+b.id():b.id()+":"+a.id())+":"+old+":"+next+":"+(now/60000);
            }
            case "UnionFormedEvent","UnionDissolvedEvent"->{a=country(call(e,"home"));b=country(call(e,"partner"));kind=name.equals("UnionFormedEvent")?Chronicle.Kind.UNION:Chronicle.Kind.UNION_END;result=kind==Chronicle.Kind.UNION?"Zawarto unię personalną.":"Rozwiązano unię personalną.";key=name+":"+a.id()+":"+b.id()+":"+(now/60000);}
            case "VassalageStartEvent","VassalageEndEvent"->{a=country(call(e,"vassal"));b=country(call(e,"overlord"));kind=name.equals("VassalageStartEvent")?Chronicle.Kind.VASSAL:Chronicle.Kind.VASSAL_END;result=kind==Chronicle.Kind.VASSAL?"Pierwszy kraj został lennikiem drugiego (zaakceptowane warunki).":"Zakończono stosunek lenny.";key=name+":"+a.id()+":"+b.id()+":"+(now/60000);}
            case "SiegeStartEvent"->{
                // Publishing the start while fighting could expose an ongoing attack. Report retrospectively at end.
                return;
            }
            case "SiegeEndEvent"->{a=country(call(e,"attacker"));b=country(call(e,"defender"));kind=Chronicle.Kind.SIEGE;boolean won=(boolean)call(e,"conquered");result="Oblężenie regionu kraju "+b.name()+": "+(won?"zdobyte; zwycięzca: "+a.name():"niezdobyte / zakończone bez podboju")+". Rozpoczęcie i wynik publikowane dopiero po zakończeniu.";key="siege-end:"+call(e,"siegeId");}
            case "ChunkOwnerChangeEvent"->{
                Object old=call(e,"oldOwner"),next=call(e,"newOwner");if(old==null||next==null||old.equals(next))return;
                // No world/chunk/coordinate is read or stored in the outbox.
                a=country(next);b=country(old);kind=Chronicle.Kind.LAND;result="Pierwszy kraj przejął ziemię drugiego.";key="land:"+old+":"+next+":"+call(e,"world")+":"+call(e,"cx")+":"+call(e,"cz")+":"+(now/60000);key="land:"+LinkStore.hash(key);
            }
            default->{return;}
        }
        submit(key,kind,a,b,result,null,now);
    }
    void poll(){
        if(!plugin.getConfig().getBoolean("chronicle.enabled",true))return;
        Object api=GameBridge.service("Kraje","pl.medivalrpg.kraje.api.KrajeApi");if(api==null)return;
        Object army=GameBridge.service("Armia","pl.medivalrpg.armia.api.ArmiaApi");
        try {
            for(Object info:(Collection<?>)GameBridge.call(api,"getCountries")){
                var c=remember(info);
                String capital=Objects.toString(call(info,"capitalWorld"))+":"+call(info,"capitalX")+":"+call(info,"capitalZ");String previous=capitals.put(c.id(),capital);
                if(previous!=null&&!previous.equals(capital))submit("capital:"+c.id()+":"+LinkStore.hash(capital)+":"+(System.currentTimeMillis()/60000),Chronicle.Kind.CAPITAL,c,null,"Siedziba stolicy została zmieniona. Położenie pozostaje prywatne.",null,System.currentTimeMillis());
                if(army==null)continue;
                // Read all countries: player countries can also have Armia battle logs; defaults return an empty list.
                var entries=(List<?>)GameBridge.call(army,"npcLog",UUID.fromString(c.id()),100);
                for(Object entry:entries){long at=(long)call(entry,"time");if(at<started||at>System.currentTimeMillis())continue;
                    String raw=call(entry,"text").toString();
                    var fact=Chronicle.withOpponent(Chronicle.log(c,at,raw),known.values(),raw);if(fact!=null)plugin.enqueue(()->plugin.chronicleAccept(fact));
                }
            }
            var armyPlugin=Bukkit.getPluginManager().getPlugin("Armia");
            if(armyPlugin!=null&&armyPlugin.isEnabled())try{
                // Only completed history, never offensives/progress/forces from this panel bridge.
                var json=JsonParser.parseString(GameBridge.call(armyPlugin,"npcConquestJson").toString()).getAsJsonObject();
                if(json.has("history"))for(var value:json.getAsJsonArray("history")){
                    String line=value.getAsString();int space=line.indexOf(' ');if(space<1)continue;
                    long at=Long.parseLong(line.substring(0,space));if(at<started||at>System.currentTimeMillis())continue;
                    var fact=Chronicle.coalition(known.values(),at,line.substring(space+1));
                    if(fact!=null)plugin.enqueue(()->plugin.chronicleAccept(fact));
                }
            }catch(ReflectiveOperationException ignored){/* old Armia without this bridge */}
        }catch(Exception|LinkageError e){warn();}
    }
}
