// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Optional Kolej bridge; Kolej checks its personal manager UUID on every invocation, including reads. */
final class RailwayMethods {
    static boolean manager(UUID uuid) {
        var plugin=Bukkit.getPluginManager().getPlugin("Kolej");
        if(plugin==null || !plugin.isEnabled())return false;
        try{return Boolean.TRUE.equals(plugin.getClass().getMethod("railwayManager",UUID.class).invoke(plugin,uuid));}
        catch(ReflectiveOperationException e){return false;}
    }
    static Object invoke(Call call) {
        var plugin=Bukkit.getPluginManager().getPlugin("Kolej");
        if(plugin==null || !plugin.isEnabled())throw new RpcException("Kolej jest obecnie niedostępna.");
        Map<String,String> args=new LinkedHashMap<>();
        for(String key:java.util.List.of("id","stops","fare","interval","active","train","line","kind","from","to","rotation","token")) {
            var value=call.params.element(key);
            if(value!=null){if(!value.isJsonPrimitive() || value.getAsString().length()>2048)throw new RpcException("Nieprawidłowy parametr dyspozytorni.");args.put(key,value.getAsString());}
        }
        try {
            return plugin.getClass().getMethod("railwayPanel",UUID.class,String.class,Map.class)
                .invoke(plugin,call.actor.uuid(),call.method,args);
        }catch(InvocationTargetException e){
            throw WorldMethods.refusal(e.getCause(),"Dyspozytornia nie mogła wykonać operacji.");
        }catch(ReflectiveOperationException e){throw new RpcException("Zainstalowana Kolej nie obsługuje jeszcze dyspozytorni WWW.");}
    }
}
