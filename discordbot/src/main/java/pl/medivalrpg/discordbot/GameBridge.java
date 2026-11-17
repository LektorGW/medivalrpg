// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** All calls happen on the server thread. No foreign API classes are bundled or linked eagerly. */
final class GameBridge {
    record Country(String id, String name, int color, int chunks, Map<UUID,String> members) {}
    record Snapshot(List<Country> countries, List<String> online, Map<UUID,String> names, double tps,int maximum,Integer wars,String era) {
        Snapshot(List<Country> countries,List<String> online,Map<UUID,String> names,double tps){this(countries,online,names,tps,0,null,null);}
    }
    static Object service(String pluginName, String type) {
        Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
        if (p == null || !p.isEnabled()) return null;
        try {
            Class<?> c=Class.forName(type,true,p.getClass().getClassLoader());
            var registration=Bukkit.getServicesManager().getRegistration(c);
            return registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError e) { return null; }
    }
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        // Invoke public interface methods, even if the provider class is package private.
        for (Class<?> i : target.getClass().getInterfaces()) {
            for (var m:i.getMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length) return m.invoke(target,args);
        }
        for(var m:target.getClass().getMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length) return m.invoke(target,args);
        throw new NoSuchMethodException(name);
    }
    static boolean authenticated(Player p) {
        try {
            Object auth=service("Straznik","pl.medivalrpg.straznik.api.StraznikApi");
            if(auth!=null) return Boolean.TRUE.equals(call(auth,"isAuthenticated",p.getUniqueId()));
            Plugin login=Bukkit.getPluginManager().getPlugin("nLogin");
            if(login!=null && login.isEnabled()) {
                Class<?> c=Class.forName("com.nickuc.login.api.nLoginAPI",true,login.getClass().getClassLoader());
                Object api=c.getMethod("getApi").invoke(null);
                return Boolean.TRUE.equals(c.getMethod("isAuthenticated",String.class).invoke(api,p.getName()));
            }
            return false; // offline-mode: never trust a nickname without an authentication provider
        } catch(ReflectiveOperationException | RuntimeException | LinkageError e) { return false; }
    }
    @SuppressWarnings("unchecked")
    static Snapshot snapshot() {
        List<Country> countries=new ArrayList<>(); Map<UUID,String> names=new HashMap<>();
        Object api=service("Kraje","pl.medivalrpg.kraje.api.KrajeApi");
        Set<String> wars=new HashSet<>();boolean known=api!=null;
        if(api!=null) try {
            for(Object k:(Collection<?>)call(api,"getCountries")) {
                String countryId=call(k,"id").toString();for(Object enemy:(Set<?>)call(k,"enemies"))wars.add(countryId.compareTo(enemy.toString())<0?countryId+":"+enemy:enemy+":"+countryId);
                Map<UUID,String> members=new HashMap<>();
                ((Map<UUID,?>)call(k,"members")).forEach((id,rank)->members.put(id,switch(rank.toString()) {
                    case "RULER" -> "ruler"; case "ADVISOR" -> "advisor"; default -> "citizen";
                }));
                // Enum names verified against API; ruler field also supports union membership.
                UUID ruler=(UUID)call(k,"ruler");
                if(!Boolean.TRUE.equals(call(k,"npc"))) members.put(ruler,"ruler");
                for(UUID id:members.keySet()) names.put(id,Objects.toString(Bukkit.getOfflinePlayer(id).getName(),id.toString()));
                countries.add(new Country(call(k,"id").toString(),call(k,"name").toString(),
                    (int)call(call(k,"color"),"value"),(int)call(k,"chunkCount"),Map.copyOf(members)));
            }
        } catch(ReflectiveOperationException | RuntimeException e) { countries.clear();known=false; }
        List<String> online=new ArrayList<>();
        for(Player p:Bukkit.getOnlinePlayers()) if(authenticated(p)) { online.add(p.getName()); names.put(p.getUniqueId(),p.getName()); }
        String era=null;Object progress=service("Postep","pl.medivalrpg.postep.api.PostepApi");
        if(progress!=null&&!countries.isEmpty())try {
            Set<Integer> eras=new TreeSet<>();for(Country country:countries)eras.add((int)call(progress,"era",UUID.fromString(country.id())));
            if(eras.stream().allMatch(e->e==1||e==2))era=String.join(" / ",eras.stream().map(e->e==1?"Średniowiecze":"Renesans").toList());
        }catch(ReflectiveOperationException|RuntimeException ignored){/* unknown placeholder skips its line */}
        return new Snapshot(List.copyOf(countries),List.copyOf(online),Map.copyOf(names),Bukkit.getTPS()[0],Bukkit.getMaxPlayers(),known?wars.size():null,era);
    }
    static String balance(UUID id) {
        Object api=service("Skarbiec","pl.medivalrpg.skarbiec.api.SkarbiecApi");
        if(api!=null) try { return call(api,"format",call(api,"balance","player:"+id)).toString(); }
        catch(ReflectiveOperationException | RuntimeException e) { /* unavailable */ }
        return "Skarbiec jest niedostępny.";
    }
}
