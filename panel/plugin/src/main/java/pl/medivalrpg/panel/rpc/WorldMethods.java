// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import org.bukkit.Bukkit;

/** Authenticated personal treasury bridge, independent of country and panel administrator roles. */
final class WorldMethods {
    private static org.bukkit.plugin.Plugin plugin(){return Bukkit.getPluginManager().getPlugin("Skarbiec");}
    static boolean manager(UUID actor){
        var p=plugin();if(p==null || !p.isEnabled())return false;
        try{return Boolean.TRUE.equals(p.getClass().getMethod("worldMoneyManager",UUID.class).invoke(p,actor));}
        catch(ReflectiveOperationException e){return false;}
    }
    static void legacyAdjust(Call call){
        var p=plugin();if(p==null)return;
        try{if(Boolean.TRUE.equals(p.getClass().getMethod("closedSupply").invoke(p)) && !manager(call.actor.uuid()))throw new RpcException("W zamkniętym obiegu korekty wykonuje wyłącznie WorldSteward.");}
        catch(ReflectiveOperationException e){/* Older economy keeps its existing access rules. */}
    }
    static Object invoke(Call call){
        var p=plugin();if(p==null || !p.isEnabled())throw new RpcException("Skarbiec jest niedostępny.");
        return invoke(call,p);
    }

    /** Testable core of {@link #invoke(Call)}; {@code p} is the Skarbiec plugin or a stand-in with the same public methods. */
    static Object invoke(Call call,Object p){
        Map<String,String> args=new HashMap<>();
        if((call.method.startsWith("world.npc") || call.method.startsWith("world.grants")) && (call.actor==null || !managerOf(p,call.actor.uuid())))throw new RpcException("Tylko WorldSteward zarządza Skarbem Świata.");
        for(String key:List.of("account","amount","country","confirm","reason","direction","query","key","retry","count")){var value=call.params.element(key);if(value!=null){if(!value.isJsonPrimitive() || value.getAsString().length()>128)throw new RpcException("Nieprawidłowy parametr Skarbu Świata.");args.put(key,value.getAsString());}}
        try{return unwrap(p.getClass().getMethod("worldTreasury",UUID.class,String.class,Map.class).invoke(p,call.actor.uuid(),call.method,args));}
        catch(InvocationTargetException e){throw refusal(e.getCause(),"Nie udało się wykonać operacji Skarbu Świata.");}
        catch(ReflectiveOperationException e){throw new RpcException("Zainstalowany Skarbiec nie obsługuje Skarbu Świata.");}
    }

    /** Asynchronous answers (durable NPC transfers) keep their Polish refusal messages. */
    static Object unwrap(Object result){
        if(result instanceof java.util.concurrent.CompletableFuture<?> future)return future.handle((value,error)->{
            if(error==null)return value;
            Throwable cause=error instanceof java.util.concurrent.CompletionException ce && ce.getCause()!=null?ce.getCause():error;
            throw refusal(cause,"Nie udało się wykonać operacji Skarbu Świata.");
        });
        return result;
    }

    /** Owner statistics; read parameters are whitelisted, everything is decided again by Skarbiec. */
    static final java.util.List<String> STATS_PARAMS=java.util.List.of("from","to","account","category","type","query","minAmount","offset","limit","days","player","kind");
    static final String STATS_DENIED="Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.";

    /**
     * {@code world.stats.*}: two independent server-side checks on EVERY call — the Panel asks Skarbiec whether this UUID
     * is the personal manager, then Skarbiec checks the UUID again inside {@code worldStats}. Panel admin, OP, NPC manager
     * and country ranks never grant access. Heavy reads come back as a future completed on the main thread.
     */
    static Object stats(Call call){
        var p=plugin();if(p==null || !p.isEnabled())throw new RpcException("Skarbiec jest niedostępny.");
        return stats(call,p);
    }

    /** Testable core: {@code p} is the Skarbiec plugin (or a stand-in with the same two public methods). */
    static Object stats(Call call,Object p){
        if(call.actor==null || !managerOf(p,call.actor.uuid()))throw new RpcException(STATS_DENIED);
        Map<String,String> args=statsArgs(call.params);
        final Object result;
        try{result=p.getClass().getMethod("worldStats",UUID.class,String.class,Map.class).invoke(p,call.actor.uuid(),call.method,args);}
        catch(InvocationTargetException e){throw refusal(e.getCause(),"Nie udało się odczytać statystyk Skarbu Świata.");}
        catch(ReflectiveOperationException e){throw new RpcException("Zainstalowany Skarbiec nie obsługuje statystyk Skarbu Świata — zaktualizuj Skarbiec.");}
        if(result instanceof java.util.concurrent.CompletableFuture<?> future)return future.handle((value,error)->{
            if(error==null)return value;
            Throwable cause=error instanceof java.util.concurrent.CompletionException ce && ce.getCause()!=null?ce.getCause():error;
            throw refusal(cause,"Nie udało się odczytać statystyk Skarbu Świata.");
        });
        return result;
    }

    /** Skarbiec's refusal (IllegalArgumentException) keeps its Polish message; no message or any other error gives {@code fallback}. */
    static RpcException refusal(Throwable cause,String fallback){
        return new RpcException(cause instanceof IllegalArgumentException && cause.getMessage()!=null && !cause.getMessage().isBlank()?cause.getMessage():fallback);
    }

    static boolean managerOf(Object p,UUID actor){
        try{return Boolean.TRUE.equals(p.getClass().getMethod("worldMoneyManager",UUID.class).invoke(p,actor));}
        catch(ReflectiveOperationException e){return false;}
    }

    static Map<String,String> statsArgs(Params params){
        Map<String,String> args=new HashMap<>();
        for(String key:STATS_PARAMS){
            var value=params.element(key);
            if(value==null || value.isJsonNull())continue;
            if(!value.isJsonPrimitive() || value.getAsString().length()>128)throw new RpcException("Nieprawidłowy parametr statystyk: "+key+".");
            args.put(key,value.getAsString());
        }
        return args;
    }
}
