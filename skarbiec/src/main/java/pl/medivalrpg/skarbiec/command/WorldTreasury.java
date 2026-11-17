// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import io.papermc.paper.command.brigadier.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kyori.adventure.text.Component;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;
import pl.medivalrpg.skarbiec.money.Money;

/** Personal reserve management; no country role, OP status or console bypass. */
public final class WorldTreasury {
    private WorldTreasury(){ }
    public static Object invoke(SkarbiecPlugin plugin,UUID actor,String method,Map<String,String> args) {
        if(!WorldMoney.MANAGER.equals(actor))throw new IllegalArgumentException("Tylko WorldSteward zarządza Skarbem Świata.");
        var ledger=plugin.ledger();
        if(!ledger.closedSupply())throw new IllegalArgumentException("Zamknięty obieg nie został jeszcze aktywowany.");
        if(method.equals("world.get"))return Map.of("cap",WorldMoney.CAP,"reserve",ledger.balance(WorldMoney.RESERVE),
            "cash",ledger.balance(WorldMoney.CASH),"payments",ledger.balance(WorldMoney.PAYMENTS),"merchant",ledger.balance(WorldMoney.MERCHANT),"households",ledger.balance(WorldMoney.HOUSEHOLDS),"total",ledger.totalMoney(),"railway",ledger.balance("kolej:przewoznik"),"railwayConstruction",ledger.balances().entrySet().stream().filter(e->e.getKey().startsWith("kolej:budzet_")).mapToLong(Map.Entry::getValue).sum());
        if(method.equals("world.npcTreasuries"))return npcTreasuries(plugin,args.get("query"));
        if(method.equals("world.grants"))return grants(plugin,null,false);
        if(method.equals("world.grantsApply"))return grants(plugin,args.getOrDefault("key",""),"true".equals(args.get("retry")));
        if(method.equals("world.npcTransfer"))return npcTransfer(plugin,actor,args);
        long amount=Money.parse(args.getOrDefault("amount","")).filter(v->v>0).orElseThrow(()->new IllegalArgumentException("Podaj dodatnią kwotę."));
        var result=switch(method){
            case "world.allocate-rail-once" -> ledger.allocateRailBudgetOnce(actor,args.get("account"),amount,"WorldSteward: jednorazowy budżet budowy kolei");
            case "world.return-rail-budget" -> {
                String source=args.getOrDefault("account","");
                if(!source.matches("kolej:budzet_[a-f0-9]{16}"))throw new IllegalArgumentException("Nieprawidłowy budżet kolejowy.");
                yield ledger.returnWorldMoney(actor,source,amount,"WorldSteward: zwrot niewykorzystanego budżetu kolei");
            }
            case "world.allocate" -> ledger.allocateWorldMoney(actor,args.getOrDefault("account",WorldMoney.PAYMENTS),amount,"WorldSteward: przydział ze Skarbu Świata");
            // Firmy: a loan of the World Treasury to a company, after the owner's manual approval (escrow firma-kredyt:<id>)
            case "world.allocate-firm-loan" -> {
                String target=args.getOrDefault("account","");
                if(!target.matches("firma-kredyt:[0-9a-f]{10}"))throw new IllegalArgumentException("Nieprawidłowy depozyt kredytu firmy.");
                yield ledger.allocateWorldMoney(actor,target,amount,"WorldSteward: kredyt Skarbu Świata dla firmy ("+args.getOrDefault("firm","?")+")");
            }
            case "world.return-firm-loan" -> {
                String source=args.getOrDefault("account","");
                if(!source.matches("firma-kredyt:[0-9a-f]{10}"))throw new IllegalArgumentException("Nieprawidłowy depozyt kredytu firmy.");
                yield ledger.returnWorldMoney(actor,source,amount,"WorldSteward: zwrot spłat kredytu firmy do Skarbu Świata");
            }
            case "world.return" -> {
                String source=args.getOrDefault("account",WorldMoney.PAYMENTS);
                if(!java.util.Set.of(WorldMoney.PAYMENTS,WorldMoney.MERCHANT,WorldMoney.HOUSEHOLDS).contains(source))throw new IllegalArgumentException("Zwrot dotyczy budżetów świata.");
                yield ledger.returnWorldMoney(actor,source,amount,"WorldSteward: zwrot budżetu do Skarbu Świata");
            }
            default -> throw new IllegalArgumentException("Nieznana czynność Skarbu Świata.");
        };
        if(!result.ok())throw new IllegalArgumentException("Przelew nie został wykonany: "+result);
        return Map.of("ok",true);
    }
    // ------------------------------------------------------------------ one-time deployment grants

    /**
     * Status of the deployment grants, or (with {@code key}) pays one ({@code "wszystkie"} = every NEW grant).
     * Ledger evidence is read in the background; the decision and the move happen on the main thread; the answer
     * waits for the durable journal and only then the grant is marked DONE.
     */
    static Object grants(SkarbiecPlugin plugin,String key,boolean retry) {
        var store=plugin.grants();
        if(store==null)throw new IllegalArgumentException("Rejestr zasileń wdrożeniowych jest niedostępny (bank/grants.json).");
        if(key!=null && !key.equals("wszystkie") && pl.medivalrpg.skarbiec.ledger.WorldGrants.byKey(key)==null)throw new IllegalArgumentException("Nieznane zasilenie: "+key);
        var answer=new java.util.concurrent.CompletableFuture<Object>();
        plugin.files().execute(()->{
            pl.medivalrpg.skarbiec.ledger.WorldGrants.Evidence ev;
            try{ev=store.evidence();}
            catch(java.io.IOException e){runMain(plugin,()->answer.completeExceptionally(new IllegalArgumentException("Nie udało się odczytać księgi: "+e.getMessage())));return;}
            runMain(plugin,()->{
                try{
                    if(key==null){answer.complete(grantStatus(plugin,store,ev));return;}
                    var applied=new java.util.ArrayList<String>();
                    for(var g:pl.medivalrpg.skarbiec.ledger.WorldGrants.DEPLOYMENT){
                        if(!key.equals("wszystkie") && !g.key().equals(key))continue;
                        if(key.equals("wszystkie") && !"NEW".equals(store.effective(g,ev)))continue;
                        store.apply(plugin.ledger(),WorldMoney.MANAGER.equals(plugin.grantActor())?plugin.grantActor():null,g,ev,retry);
                        applied.add(g.key());
                    }
                    if(applied.isEmpty())throw new IllegalArgumentException("Nie ma zasileń do wykonania.");
                    plugin.bankDurable().whenComplete((ok,error)->runMain(plugin,()->{
                        if(error!=null){answer.completeExceptionally(new IllegalArgumentException("Zasilenie zapisano w księdze, ale potwierdzenie zapisu na dysk nie powiodło się: "+error.getMessage()));return;}
                        try{
                            for(String k:applied)store.markDone(k);
                            var out=new java.util.LinkedHashMap<String,Object>(grantStatus(plugin,store,ev));
                            out.put("ok",true);out.put("applied",applied);out.put("durable",true);
                            out.put("message","Wykonano zasilenia: "+String.join(", ",applied)+". Zapis na dysku potwierdzony.");
                            answer.complete(out);
                        }catch(java.io.IOException e){answer.completeExceptionally(new IllegalArgumentException("Zasilenie wykonane, ale nie zapisano znacznika: "+e.getMessage()));}
                    }));
                }catch(IllegalArgumentException e){answer.completeExceptionally(e);}
                catch(java.io.IOException e){answer.completeExceptionally(new IllegalArgumentException("Nie udało się zapisać znacznika zasilenia: "+e.getMessage()));}
            });
        });
        return answer;
    }

    /** Config {@code world-economy.grants.auto-on-start: true}: every NEW grant once; UNCERTAIN ones are never retried. */
    public static void autoGrants(SkarbiecPlugin plugin) {
        Object r=grants(plugin,"wszystkie",false);
        if(r instanceof java.util.concurrent.CompletableFuture<?> f)f.whenComplete((v,e)->{
            if(e!=null)plugin.getLogger().info("Zasilenia wdrożeniowe: "+(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));
            else if(v instanceof Map<?,?> res)plugin.getLogger().info(String.valueOf(res.get("message")));
        });
    }

    private static void runMain(SkarbiecPlugin plugin,Runnable r) {
        try{org.bukkit.Bukkit.getScheduler().runTask(plugin,r);}catch(RuntimeException e){/* plugin disabled meanwhile */}
    }

    static Map<String,Object> grantStatus(SkarbiecPlugin plugin,pl.medivalrpg.skarbiec.ledger.WorldGrants store,pl.medivalrpg.skarbiec.ledger.WorldGrants.Evidence ev) throws java.io.IOException {
        var list=new java.util.ArrayList<Map<String,Object>>();
        for(var g:pl.medivalrpg.skarbiec.ledger.WorldGrants.DEPLOYMENT){
            var m=new java.util.LinkedHashMap<String,Object>();
            var allocs=ev.allocations().getOrDefault(g.account(),java.util.List.of());
            m.put("key",g.key());m.put("label",g.label());m.put("account",g.account());m.put("amount",g.amount());
            m.put("state",store.effective(g,ev));m.put("balance",plugin.ledger().balance(g.account()));
            m.put("fromReserveTotal",allocs.stream().mapToLong(pl.medivalrpg.skarbiec.ledger.TxRecord::amount).sum());
            m.put("fromReserveCount",allocs.size());
            m.put("lastFromReserve",allocs.isEmpty()?null:allocs.get(allocs.size()-1).time());
            list.add(m);
        }
        var out=new java.util.LinkedHashMap<String,Object>();
        out.put("reserve",plugin.ledger().balance(WorldMoney.RESERVE));
        out.put("autoOnStart",plugin.cfg().grantsAutoOnStart);
        out.put("grants",list);
        return out;
    }

    // ------------------------------------------------------------------ NPC country treasuries

    /** NPC countries with their treasury balance, sorted by name; {@code query} filters by name (case-insensitive). */
    static Map<String,Object> npcTreasuries(SkarbiecPlugin plugin,String query) {
        if(query!=null && query.length()>32)throw new IllegalArgumentException("Wyszukiwany tekst może mieć najwyżej 32 znaki.");
        String q=query==null?"":query.strip().toLowerCase(java.util.Locale.ROOT);
        var ledger=plugin.ledger();
        java.util.List<Map<String,Object>> list=new java.util.ArrayList<>();
        plugin.territory().countries().stream().filter(NpcTreasuryTransfer.Country::npc)
            .filter(c->q.isEmpty() || c.name().toLowerCase(java.util.Locale.ROOT).contains(q))
            .sorted(java.util.Comparator.comparing(NpcTreasuryTransfer.Country::name,String.CASE_INSENSITIVE_ORDER))
            .forEach(c->{Map<String,Object> m=new java.util.LinkedHashMap<>();m.put("id",c.id().toString());m.put("name",c.name());m.put("color",c.color());
                m.put("npc",true);m.put("account",c.account());m.put("balance",ledger.balance(c.account()));list.add(m);});
        var out=new java.util.LinkedHashMap<String,Object>();
        out.put("reserve",ledger.balance(WorldMoney.RESERVE));
        out.put("countries",list);
        var bulk=plugin.npcBulk();
        out.put("bulk",bulk==null?java.util.List.of():bulk.recent(10).stream().map(pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers::describe).toList());
        out.put("bulkAvailable",bulk!=null);
        return out;
    }

    /** Every NPC country as legs of a bulk transfer (sorted by name, stable order). */
    static java.util.List<pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers.Leg> npcLegs(SkarbiecPlugin plugin) {
        return plugin.territory().countries().stream().filter(NpcTreasuryTransfer.Country::npc)
            .sorted(java.util.Comparator.comparing(NpcTreasuryTransfer.Country::name,String.CASE_INSENSITIVE_ORDER).thenComparing(c->c.id().toString()))
            .map(c->new pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers.Leg(c.id().toString(),c.name(),c.account())).toList();
    }

    /**
     * {@code world.npcTransfer} with {@code direction=toAllNpc}: the same amount from the reserve to EVERY NPC country as
     * one atomic, idempotent operation ({@code key}). {@code amount} = per country, {@code confirm} = the whole sum in md,
     * {@code count} = number of countries the owner saw. Answer after the bank journal is durable; then the operation is
     * marked DONE. A PENDING operation of an earlier session is decided from the ledger logs (read in the background).
     */
    static Object npcBulkTransfer(SkarbiecPlugin plugin,UUID actor,Map<String,String> args) {
        var bulk=plugin.npcBulk();
        if(bulk==null)throw new IllegalArgumentException("Rejestr przelewów zbiorczych jest niedostępny (bank/npc-bulk.json).");
        long amount=Money.parse(args.getOrDefault("amount","")).filter(v->v>0).orElseThrow(()->new IllegalArgumentException("Podaj dodatnią kwotę."));
        long confirm;int count;
        try{confirm=Long.parseLong(args.getOrDefault("confirm","").strip());}
        catch(NumberFormatException e){throw new IllegalArgumentException("Potwierdzona suma nie zgadza się z kwotą przelewu.");}
        try{count=Integer.parseInt(args.getOrDefault("count","").strip());}
        catch(NumberFormatException e){throw new IllegalArgumentException("Podaj liczbę państw NPC z podglądu.");}
        String key=args.getOrDefault("key","").strip();
        boolean retry="true".equals(args.get("retry"));
        var answer=new java.util.concurrent.CompletableFuture<Object>();
        java.util.function.Consumer<java.util.Set<String>> run=tagged->{
            final Map<String,Object> result;
            try{result=bulk.execute(plugin.ledger(),actor,key,npcLegs(plugin),amount,confirm,count,args.get("reason"),retry,tagged);}
            catch(IllegalArgumentException e){answer.completeExceptionally(e);return;}
            catch(java.io.IOException e){answer.completeExceptionally(new IllegalArgumentException("Nie udało się zapisać rejestru operacji: "+e.getMessage()));return;}
            plugin.bankDurable().whenComplete((ok,error)->runMain(plugin,()->{
                if(error!=null){answer.completeExceptionally(new IllegalArgumentException("Przelew zapisano w księdze, ale potwierdzenie zapisu na dysk nie powiodło się: "+error.getMessage()));return;}
                try{bulk.markDone(key);}
                catch(java.io.IOException e){answer.completeExceptionally(new IllegalArgumentException("Przelew wykonany i trwały, ale nie zapisano znacznika operacji: "+e.getMessage()));return;}
                var m=new java.util.LinkedHashMap<String,Object>(result);m.put("state","DONE");m.put("durable",true);answer.complete(m);
            }));
        };
        if(bulk.needsEvidence(key)){
            plugin.files().execute(()->{
                java.util.Set<String> tagged;
                try{tagged=bulk.evidence(key);}
                catch(java.io.IOException e){runMain(plugin,()->answer.completeExceptionally(new IllegalArgumentException("Nie udało się odczytać księgi: "+e.getMessage())));return;}
                runMain(plugin,()->run.accept(tagged));
            });
        }else{
            run.accept(null);
        }
        return answer;
    }

    /** Country by id (panel) or by name (command); null when unknown. */
    static NpcTreasuryTransfer.Country country(SkarbiecPlugin plugin,String key) {
        if(key==null || key.isBlank())return null;
        String k=key.strip();
        for(var c:plugin.territory().countries()){
            if(c.id().toString().equalsIgnoreCase(k) || c.name().equalsIgnoreCase(k) || c.name().replace('_',' ').equalsIgnoreCase(k))return c;
        }
        return null;
    }

    /**
     * Owner-only, confirmed, reasoned move between the reserve and an NPC treasury. The answer comes after the bank
     * journal has been forced to disk ({@code LedgerStore.durable}), completed on the main thread.
     */
    static Object npcTransfer(SkarbiecPlugin plugin,UUID actor,Map<String,String> args) {
        if(TO_ALL_NPC.equals(args.get("direction")))return npcBulkTransfer(plugin,actor,args);
        long amount=Money.parse(args.getOrDefault("amount","")).filter(v->v>0).orElseThrow(()->new IllegalArgumentException("Podaj dodatnią kwotę."));
        long confirm;
        try{confirm=Long.parseLong(args.getOrDefault("confirm","").strip());}
        catch(NumberFormatException e){throw new IllegalArgumentException("Potwierdzona kwota nie zgadza się z kwotą przelewu.");}
        var result=NpcTreasuryTransfer.execute(plugin.ledger(),actor,country(plugin,args.get("country")),amount,confirm,args.get("reason"),args.get("direction"));
        var answer=new java.util.concurrent.CompletableFuture<Object>();
        plugin.bankDurable().whenComplete((ok,error)->{
            try{
                org.bukkit.Bukkit.getScheduler().runTask(plugin,()->{
                    if(error!=null)answer.completeExceptionally(new IllegalArgumentException("Przelew zapisano w księdze, ale potwierdzenie zapisu na dysk nie powiodło się: "+error.getMessage()));
                    else{var m=new java.util.LinkedHashMap<String,Object>(result);m.put("durable",true);answer.complete(m);}
                });
            }catch(RuntimeException e){answer.completeExceptionally(new IllegalArgumentException("Skarbiec się wyłącza."));}
        });
        return answer;
    }

    /** {@code world.npcTransfer} direction of the equal transfer to every NPC country. */
    public static final String TO_ALL_NPC="toAllNpc";

    private record Pending(String country,String amount,String reason,String direction,long until,String key,long total,int count) { }
    private static final Map<UUID,Pending> PENDING=new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code /skarbswiata wszystkimnpc <kwota> <powód>}: preview of the equal transfer to every NPC country. */
    private static int prepareAll(SkarbiecPlugin plugin,Player player,String amount,String reason) {
        try{
            long md=Money.parse(amount).filter(v->v>0).orElseThrow(()->new IllegalArgumentException("Podaj dodatnią kwotę."));
            var legs=npcLegs(plugin);
            if(legs.isEmpty())throw new IllegalArgumentException("Brak państw NPC — nie ma komu przelać.");
            long total;
            try{total=Math.multiplyExact(md,(long)legs.size());}catch(ArithmeticException e){throw new IllegalArgumentException("Za duża kwota.");}
            long reserve=plugin.ledger().balance(WorldMoney.RESERVE);
            String key="npcbulk-"+UUID.randomUUID().toString().replace("-","").substring(0,16);
            PENDING.put(player.getUniqueId(),new Pending(null,amount,reason,TO_ALL_NPC,System.currentTimeMillis()+60_000,key,total,legs.size()));
            String names=legs.stream().limit(12).map(pl.medivalrpg.skarbiec.ledger.NpcBulkTransfers.Leg::name).collect(java.util.stream.Collectors.joining(", "))
                +(legs.size()>12?" i "+(legs.size()-12)+" innych":"");
            player.sendMessage(Component.text("Przelew do WSZYSTKICH państw NPC ("+legs.size()+"): po "+Money.format(md)+" = razem "+Money.format(total)
                +" ("+total+" md). Powód: "+reason+"."));
            player.sendMessage(Component.text("Państwa: "+names+"."));
            player.sendMessage(Component.text("Rezerwa: "+Money.format(reserve)+" → "+Money.format(reserve-total)+(reserve<total?" — ZA MAŁO ŚRODKÓW, przelew zostanie odrzucony.":".")));
            player.sendMessage(Component.text("[Potwierdź w ciągu 60 s]",net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/skarbswiata potwierdz"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Przelej razem "+Money.format(total)+" do "+legs.size()+" państw NPC"))));
            return 1;
        }catch(IllegalArgumentException e){player.sendMessage(Component.text(e.getMessage()));return 0;}
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestNpc(SkarbiecPlugin plugin,
            com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        String typed=b.getRemaining().replace("\"","").toLowerCase(java.util.Locale.ROOT);
        for(var c:plugin.territory().countries()){
            if(!c.npc() || !c.name().toLowerCase(java.util.Locale.ROOT).startsWith(typed))continue;
            String name=c.name().matches("[0-9A-Za-z_.+-]+")?c.name():"\""+c.name().replace("\"","")+"\"";
            b.suggest(name,new com.mojang.brigadier.LiteralMessage("Skarbiec: "+Money.format(plugin.ledger().balance(c.account()))));
        }
        return b.buildFuture();
    }

    private static int prepare(SkarbiecPlugin plugin,Player player,String country,String amount,String reason,String direction) {
        try{
            var c=country(plugin,country);
            if(c==null || !c.npc())throw new IllegalArgumentException("Nie ma takiego państwa NPC.");
            long md=Money.parse(amount).filter(v->v>0).orElseThrow(()->new IllegalArgumentException("Podaj dodatnią kwotę."));
            PENDING.put(player.getUniqueId(),new Pending(c.id().toString(),amount,reason,direction,System.currentTimeMillis()+60_000,null,0,0));
            boolean to=NpcTreasuryTransfer.TO_COUNTRY.equals(direction);
            player.sendMessage(Component.text((to?"Przelew z rezerwy do skarbca ":"Zwrot ze skarbca ")+c.name()+(to?"":" do rezerwy")+": "+Money.format(md)
                +" ("+md+" md). Powód: "+reason+". Rezerwa: "+Money.format(plugin.ledger().balance(WorldMoney.RESERVE))+", skarbiec kraju: "
                +Money.format(plugin.ledger().balance(c.account()))+"."));
            player.sendMessage(Component.text("[Potwierdź w ciągu 60 s]",net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/skarbswiata potwierdz"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Wykonaj przelew "+Money.format(md)))));
            return 1;
        }catch(IllegalArgumentException e){player.sendMessage(Component.text(e.getMessage()));return 0;}
    }

    private static int grantsCommand(SkarbiecPlugin plugin,Player player,String key,boolean retry) {
        try{
            Object r=invoke(plugin,player.getUniqueId(),key==null?"world.grants":"world.grantsApply",
                key==null?Map.of():retry?Map.of("key",key,"retry","true"):Map.of("key",key));
            if(r instanceof java.util.concurrent.CompletableFuture<?> f)f.whenComplete((v,e)->{
                if(!player.isOnline())return;
                if(e!=null){player.sendMessage(Component.text(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));return;}
                if(!(v instanceof Map<?,?> res))return;
                if(res.get("message")!=null)player.sendMessage(Component.text(String.valueOf(res.get("message"))));
                player.sendMessage(Component.text("Rezerwa: "+Money.format(((Number)res.get("reserve")).longValue())+(Boolean.TRUE.equals(res.get("autoOnStart"))?" (automatycznie przy starcie)":"")));
                for(Object o:(java.util.List<?>)res.get("grants")){
                    Map<?,?> g=(Map<?,?>)o;
                    player.sendMessage(Component.text(" • "+g.get("label")+": "+Money.format(((Number)g.get("amount")).longValue())+" → "+g.get("account")
                        +" — "+g.get("state")+", saldo teraz "+Money.format(((Number)g.get("balance")).longValue())
                        +", przydziały z rezerwy: "+g.get("fromReserveCount")+" ("+Money.format(((Number)g.get("fromReserveTotal")).longValue())+")"));
                }
                if(key==null)player.sendMessage(Component.text("Wykonanie: /skarbswiata wdrozenie wykonaj <klucz|wszystkie>"));
            });
            return 1;
        }catch(IllegalArgumentException e){player.sendMessage(Component.text(e.getMessage()));return 0;}
    }

    private static int confirm(SkarbiecPlugin plugin,Player player) {
        Pending p=PENDING.remove(player.getUniqueId());
        if(p==null || p.until()<System.currentTimeMillis()){player.sendMessage(Component.text("Brak przelewu do potwierdzenia (albo minęło 60 s)."));return 0;}
        try{
            long md=Money.parse(p.amount()).orElse(0L);
            Object r=TO_ALL_NPC.equals(p.direction())
                ?invoke(plugin,player.getUniqueId(),"world.npcTransfer",Map.of("amount",p.amount(),"confirm",Long.toString(p.total()),"count",Integer.toString(p.count()),
                    "reason",p.reason(),"direction",TO_ALL_NPC,"key",p.key()))
                :invoke(plugin,player.getUniqueId(),"world.npcTransfer",Map.of("country",p.country(),"amount",p.amount(),"confirm",Long.toString(md),
                    "reason",p.reason(),"direction",p.direction()));
            if(r instanceof java.util.concurrent.CompletableFuture<?> f)f.whenComplete((v,e)->{
                if(!player.isOnline())return;
                if(e!=null)player.sendMessage(Component.text(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));
                else if(v instanceof Map<?,?> res)player.sendMessage(Component.text(String.valueOf(res.get("message"))+" Zapis na dysku potwierdzony."));
            });
            return 1;
        }catch(IllegalArgumentException e){player.sendMessage(Component.text(e.getMessage()));return 0;}
    }

    public static void register(SkarbiecPlugin plugin,Commands commands) {
        commands.register(Commands.literal("skarbswiata")
            .requires(s->s.getSender() instanceof Player p && WorldMoney.MANAGER.equals(p.getUniqueId()))
            .executes(c->execute(plugin,(Player)c.getSource().getSender(),"info"))
            .then(Commands.literal("kraj").then(Commands.argument("kraj",StringArgumentType.string()).suggests((c,b)->suggestNpc(plugin,b))
                .then(Commands.argument("kwota",StringArgumentType.word()).then(Commands.argument("powod",StringArgumentType.greedyString())
                    .executes(c->prepare(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"kraj"),StringArgumentType.getString(c,"kwota"),
                        StringArgumentType.getString(c,"powod"),NpcTreasuryTransfer.TO_COUNTRY))))))
            .then(Commands.literal("zwrotkraju").then(Commands.argument("kraj",StringArgumentType.string()).suggests((c,b)->suggestNpc(plugin,b))
                .then(Commands.argument("kwota",StringArgumentType.word()).then(Commands.argument("powod",StringArgumentType.greedyString())
                    .executes(c->prepare(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"kraj"),StringArgumentType.getString(c,"kwota"),
                        StringArgumentType.getString(c,"powod"),NpcTreasuryTransfer.TO_RESERVE))))))
            .then(Commands.literal("wszystkimnpc").then(Commands.argument("kwota",StringArgumentType.word()).then(Commands.argument("powod",StringArgumentType.greedyString())
                .executes(c->prepareAll(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"kwota"),StringArgumentType.getString(c,"powod"))))))
            .then(Commands.literal("potwierdz").executes(c->confirm(plugin,(Player)c.getSource().getSender())))
            .then(Commands.literal("wdrozenie").executes(c->grantsCommand(plugin,(Player)c.getSource().getSender(),null,false))
                .then(Commands.literal("wykonaj").then(Commands.argument("klucz",StringArgumentType.word())
                    .suggests((c,b)->{b.suggest("wszystkie");pl.medivalrpg.skarbiec.ledger.WorldGrants.DEPLOYMENT.forEach(g->b.suggest(g.key()));return b.buildFuture();})
                    .executes(c->grantsCommand(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"klucz"),false))))
                .then(Commands.literal("ponow").then(Commands.argument("klucz",StringArgumentType.word())
                    .suggests((c,b)->{pl.medivalrpg.skarbiec.ledger.WorldGrants.DEPLOYMENT.forEach(g->b.suggest(g.key()));return b.buildFuture();})
                    .executes(c->grantsCommand(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"klucz"),true)))))
            .then(Commands.argument("polecenie",StringArgumentType.greedyString())
                .executes(c->execute(plugin,(Player)c.getSource().getSender(),StringArgumentType.getString(c,"polecenie"))))
            .build(),"Skarb Świata: /skarbswiata info | budzet <kwota> | przydziel <konto> <kwota> | zwroc <kwota> | spis | kraj <kraj NPC> <kwota> <powód> | zwrotkraju <kraj NPC> <kwota> <powód> | wszystkimnpc <kwota> <powód> | potwierdz");
    }
    private static int execute(SkarbiecPlugin plugin,Player player,String input) {
        try {
            String[] parts=input.trim().split("\\s+",3);
            switch(parts[0]){
                case "info" -> {
                    invoke(plugin,player.getUniqueId(),"world.get",Map.of());var ledger=plugin.ledger();
                    player.sendMessage(Component.text("Skarb Świata: pula "+Money.format(WorldMoney.CAP)+", rezerwa "+Money.format(ledger.balance(WorldMoney.RESERVE))
                        +", budżet operacyjny "+Money.format(ledger.balance(WorldMoney.PAYMENTS))+", fizyczne monety "+Money.format(ledger.balance(WorldMoney.CASH))));
                }
                case "budzet","zwroc" -> {
                    if(parts.length<2)throw new IllegalArgumentException("Podaj kwotę, np. /skarbswiata budzet 100zl");
                    String amount=parts[1]+(parts.length>2?" "+parts[2]:"");
                    String method="world.return";if(parts[0].equals("budzet"))method="world.allocate";
                    invoke(plugin,player.getUniqueId(),method,Map.of("amount",amount));
                    player.sendMessage(Component.text("Zapisano przelew Skarbu Świata."));
                }
                case "przydziel" -> {
                    if(parts.length<3)throw new IllegalArgumentException("Użycie: /skarbswiata przydziel <konto> <kwota>");
                    invoke(plugin,player.getUniqueId(),"world.allocate",Map.of("account",parts[1],"amount",parts[2]));
                    player.sendMessage(Component.text("Zapisano przydział Skarbu Świata."));
                }
                case "spis" -> {
                    Object r=plugin.worldStats(player.getUniqueId(),"world.stats.census",Map.of());
                    player.sendMessage(Component.text(r instanceof Map<?,?> res?String.valueOf(res.get("message")):"Spis monet uruchomiony."));
                }
                default -> throw new IllegalArgumentException("/skarbswiata info | budzet <kwota> | przydziel <konto> <kwota> | zwroc <kwota> | spis");
            }
            return 1;
        }catch(IllegalArgumentException e){player.sendMessage(Component.text(e.getMessage()));return 0;}
    }
}
