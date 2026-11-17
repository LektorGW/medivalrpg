// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.infrastructure.SupplyKind;
import pl.medivalrpg.kolej.launch.LaunchPlan;
import pl.medivalrpg.kolej.network.TrackSegment;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * {@code /kolej uruchom <plan> [potwierdz]}: launches an approved line after the PHYSICAL acceptance of its track.
 * Order: load the needed chunks (no generation) → full check (nothing changes) → durable intent → real payments from
 * {@code kolej:przewoznik} (each paid item is created in the same tick) → durable save → line, timetable, fare and
 * boards → registration of the real water/coal containers. Every step is idempotent; a restart while PAYING blocks
 * the plan until the manager reconciles it with the bank log ({@code rozlicz}). Never marks anything DONE by itself.
 */
final class LineLaunch {
    private final KolejPlugin plugin;
    private final RailwayProjects projects;
    private final java.util.function.Supplier<NetworkCommission> network;
    private Run run;

    private static final class Run {
        final LaunchPlan plan;final UUID sender;final boolean confirm;final boolean auditedPaid;
        String stage="LOAD";final List<Chunk> tickets=new ArrayList<>();final List<CompletableFuture<Chunk>> loading=new ArrayList<>();
        List<Long> keys;int chunkIndex;CompletableFuture<Void> saving;String pendingFixture;long started=System.currentTimeMillis();
        Run(LaunchPlan plan,UUID sender,boolean confirm,boolean auditedPaid){this.plan=plan;this.sender=sender;this.confirm=confirm;this.auditedPaid=auditedPaid;}
    }

    LineLaunch(KolejPlugin plugin,RailwayProjects projects,java.util.function.Supplier<NetworkCommission> network) {
        this.plugin=plugin;this.projects=projects;this.network=network;
        // A restart in the middle of payments is never guessed: block until the manager reconciles it.
        boolean changed=false;
        for(var e:plugin.data().launches.entrySet())if("PAYING".equals(e.getValue().phase)){
            e.getValue().phase="BLOCKED";e.getValue().reason="Restart podczas płatności (zamiar "+e.getValue().intent+"). Sprawdź /skarbiec log kolej:przewoznik i wpisy [uruchomienie:"+e.getValue().intent+"], potem /kolej uruchom "+e.getKey()+" rozlicz zaplacone|niezaplacone.";changed=true;
            plugin.getLogger().severe("Uruchomienie "+e.getKey()+": "+e.getValue().reason);
        }
        if(changed)plugin.save();
    }

    Map<String,LaunchPlan> plans() {
        Path file=plugin.getDataFolder().toPath().resolve("uruchomienie.yml");
        if(!Files.exists(file))plugin.saveResource("uruchomienie.yml",false);
        return LaunchPlan.parseAll(YamlConfiguration.loadConfiguration(file.toFile()),plugin.getConfig().getInt("firma.max-wagonow",6));
    }

    LaunchPlan.Prices prices() {
        var c=plugin.getConfig();
        return new LaunchPlan.Prices(c.getLong("koszty.pociag",20000),c.getLong("koszty.wagon",2000),c.getLong("koszty.zatrudnienie",500),c.getLong("firma.dzienny-zold",100),c.getLong("firma.dzienny-zold-palacza",60));
    }

    void command(CommandSender sender,String[] a) {
        Map<String,LaunchPlan> plans=plans();
        if(a.length==1){
            if(plans.isEmpty())plugin.message(sender,"Brak planów w plugins/Kolej/uruchomienie.yml.");
            for(var p:plans.values()){var s=plugin.data().launches.get(p.id());plugin.message(sender,p.id()+" — "+p.name()+" — "+(s==null?"nieuruchomiony":s.phase+(s.reason.isBlank()?"":" · "+s.reason)));}
            plugin.message(sender,"/kolej uruchom <plan> — sprawdzenie i koszty; /kolej uruchom <plan> potwierdz — uruchomienie.");return;
        }
        LaunchPlan plan=plans.get(a[1]);if(plan==null)throw new IllegalArgumentException("Nie ma planu "+a[1]+" w uruchomienie.yml.");
        if(run!=null)throw new IllegalArgumentException("Trwa już "+(run.confirm?"uruchamianie":"sprawdzanie")+" planu "+run.plan.id()+".");
        UUID who=sender instanceof Player p?p.getUniqueId():null;
        LaunchState state=plugin.data().launches.get(plan.id());
        if(a.length>=3 && a[2].equals("rozlicz")){
            if(state==null || !"BLOCKED".equals(state.phase))throw new IllegalArgumentException("Plan nie czeka na rozliczenie.");
            if(a.length!=4 || !Set.of("zaplacone","niezaplacone").contains(a[3]))throw new IllegalArgumentException("Użycie: /kolej uruchom "+plan.id()+" rozlicz zaplacone|niezaplacone");
            state.phase=a[3].equals("zaplacone")?"AUDITED_PAID":"NEW";state.reason="Rozliczono ręcznie: "+a[3];touch(state);plugin.save();
            plugin.getLogger().warning("Uruchomienie "+plan.id()+": zarządca rozliczył przerwaną płatność jako "+a[3]+" (zamiar "+state.intent+")");
            plugin.message(sender,"Zapisano. Ponów: /kolej uruchom "+plan.id()+" potwierdz");return;
        }
        boolean confirm=a.length==3 && a[2].equals("potwierdz");
        if(a.length>3 || a.length==3 && !confirm)throw new IllegalArgumentException("Użycie: /kolej uruchom <plan> [potwierdz|rozlicz zaplacone|niezaplacone]");
        if(state!=null && "BLOCKED".equals(state.phase))throw new IllegalArgumentException(state.reason);
        run=new Run(plan,who,confirm,state!=null && "AUDITED_PAID".equals(state.phase));
        plugin.message(sender,(confirm?"Uruchamianie":"Sprawdzanie")+" planu "+plan.id()+": wczytywanie stacji i magazynów…");
    }

    String status(){return run==null?"bezczynne":run.plan.id()+" · "+run.stage;}

    private void say(String text){
        CommandSender target=run==null || run.sender==null?Bukkit.getConsoleSender():Bukkit.getPlayer(run.sender);
        if(target!=null)plugin.message(target,text);
        plugin.getLogger().info("Uruchomienie: "+text);
    }

    void tick() {
        if(run==null)return;
        try {
            switch(run.stage) {
                case "LOAD" -> load();
                case "CHECK" -> check();
                case "INTENT" -> {if(run.saving.isDone()){run.saving.join();pay();}}
                case "PAID" -> {if(run.saving.isDone()){run.saving.join();line();}}
                case "FIXTURES" -> fixtures();
                case "FINAL" -> {if(run.saving.isDone()){run.saving.join();say("Plan "+run.plan.id()+" uruchomiony. Odjazdy: /kolej odjazdy, stan: /kolej linia lista, /kolej pociag lista.");finish();}}
                default -> finish();
            }
            if(run!=null && System.currentTimeMillis()-run.started>600_000){say("Przekroczono 10 minut — przerwano bez dalszych zmian. Stan: /kolej uruchom");abort(null);}
        }catch(RuntimeException e){
            plugin.getLogger().log(java.util.logging.Level.WARNING,"Uruchomienie",e);
            say("Przerwano: "+(e.getMessage()==null?"błąd, szczegóły w logu":e.getMessage()));abort(e.getMessage());
        }
    }

    private void abort(String reason){
        if(run!=null){
            var state=plugin.data().launches.get(run.plan.id());
            // Only the pre-payment intent can be rolled back: nothing has been charged in the INTENT stage yet.
            if(state!=null && "PAYING".equals(state.phase) && "INTENT".equals(run.stage) && run.saving!=null && run.saving.isDone() && !run.saving.isCompletedExceptionally()){state.phase="NEW";state.reason=reason==null?"przerwano":reason;touch(state);plugin.save();}
            else if(state!=null && reason!=null && !"BLOCKED".equals(state.phase) && !"PAYING".equals(state.phase)){state.reason=reason;touch(state);plugin.save();}
        }
        finish();
    }

    private void finish(){if(run==null)return;for(Chunk c:run.tickets)c.removePluginChunkTicket(plugin);run.tickets.clear();run=null;}

    private World world(){
        var first=plugin.graph().node(run.plan.line().stops().getFirst());
        World w=first==null?null:Bukkit.getWorld(first.world());
        if(w==null)throw new IllegalArgumentException("Brak stacji "+run.plan.line().stops().getFirst()+" w sieci — linia nie została odebrana.");
        return w;
    }

    private void load(){
        World w=world();
        if(run.keys==null){
            TreeSet<Long> keys=new TreeSet<>();
            for(var f:run.plan.fixtures())keys.add(key(f.x()>>4,f.z()>>4));
            for(var b:run.plan.boards())keys.add(key(b.x()>>4,b.z()>>4));
            for(var t:run.plan.trains()){var n=plugin.graph().node(t.station());if(n!=null)keys.add(key(n.x()>>4,n.z()>>4));}
            if(keys.size()>64)throw new IllegalArgumentException("Plan wymaga ponad 64 chunków.");
            run.keys=List.copyOf(keys);
        }
        for(var it=run.loading.iterator();it.hasNext();){var f=it.next();if(!f.isDone())continue;it.remove();Chunk c=f.join();if(c==null)throw new IllegalArgumentException("Niewygenerowany chunk stacji — uruchomienie nie generuje świata.");c.addPluginChunkTicket(plugin);run.tickets.add(c);}
        while(run.loading.size()<8 && run.chunkIndex<run.keys.size()){long k=run.keys.get(run.chunkIndex++);run.loading.add(w.getChunkAtAsync((int)(k>>32),(int)k,false));}
        if(run.chunkIndex<run.keys.size() || !run.loading.isEmpty())return;
        run.stage="CHECK";
    }

    /** Everything that must hold BEFORE money moves. Read-only. */
    List<String> problems(LaunchPlan plan,List<String> notes){
        List<String> out=new ArrayList<>();
        var nc=network.get();
        if(nc!=null){var p=nc.progress();if(p.isPresent() && p.get().done()<p.get().total())out.add("Budowa sieci nieukończona: "+p.get().describe());}
        Set<String> nodes=new LinkedHashSet<>(plan.line().stops());plan.trains().forEach(t->nodes.add(t.station()));plan.fixtures().forEach(f->nodes.add(f.station()));plan.boards().forEach(b->nodes.add(b.station()));
        for(String id:nodes){var n=plugin.graph().node(id);
            if(n==null || !n.isWaitingPoint())out.add("Brak stacji/bazy "+id+" w odebranej sieci");
            else if(!projects.ready(id))out.add("Stacja "+id+" nie ma ukończonego i odebranego toru");}
        if(out.isEmpty()){
            var stops=plan.line().stops();var back=new ArrayList<>(stops);Collections.reverse(back);
            if(plugin.graph().routeThrough(stops,TrackSegment::isBuilt,true).isEmpty() || plugin.graph().routeThrough(back,TrackSegment::isBuilt,true).isEmpty())out.add("Brak odebranego (BUILT) przejazdu "+String.join(" → ",stops)+" w obie strony");
        }
        if(plugin.getConfig().getBoolean("modele.wymagaj-bettermodel",true)){
            if(!Bukkit.getPluginManager().isPluginEnabled("BetterModel"))out.add("BetterModel niewłączony — odjazd byłby zablokowany");
            else {var models=new pl.medivalrpg.kolej.visual.TrainModels();
                Set<String> needed=new TreeSet<>(List.of("kolej_parowoz","kolej_tender"));plan.trains().forEach(t->t.cars().forEach(c->needed.add(c.model)));
                for(String m:needed)if(!models.ready(m))out.add("Model "+m+" niewczytany w BetterModel");}
        }
        int newTrains=0,newCrew=0;
        for(var t:plan.trains()){
            Train existing=plugin.data().trains.get(t.id());
            if(existing==null){
                newTrains++;
                if(plugin.data().trains.values().stream().anyMatch(o->t.station().equals(o.node) && o.yardBase==null))out.add("Tor stacyjny "+t.station()+" zajęty przez inny skład");
            }else {
                if(existing.running || existing.yardBase!=null)out.add("Skład "+t.id()+" jest w ruchu lub w parowozowni");
                if(existing.cars.size()>t.cars().size() || !t.cars().subList(0,existing.cars.size()).equals(existing.cars))out.add("Skład "+t.id()+" ma inne wagony niż plan");
            }
            for(boolean stoker:new boolean[]{false,true}){
                if(stoker && t.stoker()==null)continue;
                Crew c=plugin.data().crews.get(LaunchPlan.crewId(t.id(),stoker));
                if(c==null)newCrew++;
                else if(!t.id().equals(c.train) || c.stoker()!=stoker)out.add("Pracownik "+c.id+" ma inny przydział");
            }
        }
        if(plugin.data().trains.size()+newTrains>plugin.getConfig().getInt("firma.max-pociagow",12))out.add("Limit składów firma.max-pociagow");
        if(plugin.data().crews.size()+newCrew>32)out.add("Limit 32 pracowników");
        Line line=plugin.data().lines.get(plan.line().id());
        if(line!=null && !line.stops.equals(plan.line().stops()))out.add("Linia "+line.id+" istnieje z innymi przystankami");
        World w=world();
        for(var f:plan.fixtures()){
            SupplyKind kind=SupplyKind.parse(f.kind());
            String problem=plugin.infrastructure().siteProblem(f.id(),kind,f.station(),w,f.x(),f.y(),f.z(),f.rotation());
            if(problem!=null)out.add("Stanowisko "+f.id()+" ("+f.kind()+") przy "+f.x()+" "+f.y()+" "+f.z()+": "+problem+(problem.contains("skrzyni") || problem.contains("beczki")?" — postaw tam zwykłą beczkę":""));
            else {int stock=plugin.infrastructure().registered(f.id()).isPresent()?plugin.infrastructure().stockAt(f.id()):plugin.infrastructure().plainItems(w,f.x(),f.y(),f.z(),kind);
                notes.add("Stanowisko "+f.id()+": "+(stock<0?"?":stock)+" szt. "+(kind.coal?"węgla":"wiader wody")+(stock==0?" — napełnij przed pierwszym tankowaniem":""));}
        }
        for(var b:plan.boards()){String problem=StationBoards.siteProblem(plugin,w,b.x(),b.y(),b.z());if(problem!=null)out.add("Tablica "+b.id()+" przy "+b.x()+" "+b.y()+" "+b.z()+": "+problem);}
        var costs=remainingCosts(plan);
        long balance=plugin.economy().balance(RailData.ACCOUNT);
        notes.add("Koszt do zapłaty z kolej:przewoznik: "+plugin.economy().format(costs.total())+" (parowozy "+costs.locomotives()+", wagony "+costs.cars()+", zatrudnienie "+costs.hires()+", pierwszy żołd "+costs.firstWages()+" md); saldo "+plugin.economy().format(balance));
        if(balance<costs.total())out.add("Za mało środków firmy: potrzeba "+plugin.economy().format(costs.total()));
        return out;
    }

    /** Costs only of items that do not exist yet (a resumed launch never pays twice). */
    LaunchPlan.Costs remainingCosts(LaunchPlan plan){
        var p=prices();long locos=0,cars=0,hires=0,wages=0;
        boolean free=run!=null && run.auditedPaid;
        if(free)return new LaunchPlan.Costs(0,0,0,0);
        for(var t:plan.trains()){
            Train existing=plugin.data().trains.get(t.id());
            if(existing==null)locos+=p.locomotive();
            cars+=p.car()*(t.cars().size()-(existing==null?0:Math.min(existing.cars.size(),t.cars().size())));
            if(!plugin.data().crews.containsKey(LaunchPlan.crewId(t.id(),false))){hires+=p.hire();wages+=p.driverWage();}
            if(t.stoker()!=null && !plugin.data().crews.containsKey(LaunchPlan.crewId(t.id(),true))){hires+=p.hire();wages+=p.stokerWage();}
        }
        return new LaunchPlan.Costs(locos,cars,hires,wages);
    }

    private void check(){
        List<String> notes=new ArrayList<>();
        List<String> problems=problems(run.plan,notes);
        say("Plan "+run.plan.id()+" — "+run.plan.name()+": linia "+run.plan.line().id()+" "+String.join(" ↔ ",run.plan.line().stops())+", bilet "+plugin.economy().format(run.plan.line().fare())+", rozkład "+run.plan.line().timetable().describe()+(run.plan.line().returnAfterMinutes()>0?", powrót +"+run.plan.line().returnAfterMinutes()+" min":""));
        for(var t:run.plan.trains())say("Skład "+t.id()+" na "+t.station()+": parowóz + tender + "+t.cars()+"; maszynista "+t.driver()+(t.stoker()==null?"":", palacz "+t.stoker()));
        notes.forEach(this::say);
        if(!problems.isEmpty()){problems.forEach(p->say("✗ "+p));say("Nic nie zmieniono i nic nie pobrano.");finish();return;}
        if(!run.confirm){say("✓ Wszystko gotowe. Uruchom: /kolej uruchom "+run.plan.id()+" potwierdz");finish();return;}
        LaunchState state=plugin.data().launches.computeIfAbsent(run.plan.id(),k->new LaunchState());
        state.phase="PAYING";state.intent=UUID.randomUUID().toString().substring(0,8);state.reason="";touch(state);
        run.saving=plugin.saveDurable();run.stage="INTENT";
    }

    private void charge(LaunchState state,long amount,String what){
        if(amount<=0 || run.auditedPaid)return;
        if(plugin.economy().withdraw(RailData.ACCOUNT,amount,"[uruchomienie:"+state.intent+"] "+what)!=TxResult.OK)throw new IllegalArgumentException("Brak środków firmy na: "+what);
        state.paid+=amount;
    }

    /** Same tick: each payment is followed by creating exactly the paid item. Then one durable save. */
    private void pay(){
        LaunchState state=plugin.data().launches.get(run.plan.id());
        List<String> notes=new ArrayList<>();var again=problems(run.plan,notes);
        if(!again.isEmpty()){state.phase="NEW";state.reason="Zmiana przed płatnością: "+again.getFirst();touch(state);plugin.save();again.forEach(p->say("✗ "+p));finish();return;}
        var p=prices();
        try {
            for(var spec:run.plan.trains()){
                Train t=plugin.data().trains.get(spec.id());
                if(t==null){charge(state,p.locomotive(),"Zakup parowozu z tendrem "+spec.id());t=new Train();t.id=spec.id();t.node=plugin.node(spec.station()).id();plugin.data().trains.put(t.id,t);}
                while(t.cars.size()<spec.cars().size()){Car car=spec.cars().get(t.cars.size());charge(state,p.car(),"Wagon "+car.name().toLowerCase(Locale.ROOT)+" do "+spec.id());t.cars.add(car);}
                hire(state,spec,false,spec.driver(),p.hire()+p.driverWage());
                if(spec.stoker()!=null)hire(state,spec,true,spec.stoker(),p.hire()+p.stokerWage());
            }
            state.phase="PAID";state.reason="";
        }catch(IllegalArgumentException e){state.phase="NEW";state.reason=e.getMessage();say("✗ "+e.getMessage()+" — opłacone pozycje zostały utworzone; ponów po uzupełnieniu środków.");}
        touch(state);run.saving=plugin.saveDurable();
        if(!"PAID".equals(state.phase)){run.stage="FINAL_FAIL";return;}
        say("Opłacono "+plugin.economy().format(state.paid)+" z kolej:przewoznik (wpisy [uruchomienie:"+state.intent+"]).");
        run.stage="PAID";
    }

    private void hire(LaunchState state,LaunchPlan.TrainSpec spec,boolean stoker,String name,long cost){
        String id=LaunchPlan.crewId(spec.id(),stoker);
        if(plugin.data().crews.containsKey(id))return;
        charge(state,cost,"Zatrudnienie i pierwszy żołd: "+(stoker?"palacz ":"maszynista ")+name+" ("+spec.id()+")");
        Crew c=new Crew();c.id=id;c.name=name;c.train=spec.id();c.role=stoker?Crew.STOKER:Crew.DRIVER;c.paid=true;plugin.data().crews.put(id,c);
    }

    private void line(){
        var spec=run.plan.line();
        Line l=plugin.data().lines.computeIfAbsent(spec.id(),k->{Line n=new Line();n.id=k;n.stops.addAll(spec.stops());return n;});
        l.fare=spec.fare();l.timetable=spec.timetable();l.returnAfterMinutes=spec.returnAfterMinutes();
        for(var t:run.plan.trains()){Train train=plugin.data().trains.get(t.id());if(train!=null && !train.running)train.line=l.id;}
        plugin.graph().routeThrough(l.stops,TrackSegment::isBuilt,true).orElseThrow(()->new IllegalArgumentException("Linia nie ma gotowych torów."));
        l.active=true;
        World w=world();
        for(var b:run.plan.boards())plugin.data().boards.put(b.id(),new Board(b.id(),b.station(),w.getName(),b.x(),b.y(),b.z(),b.rotation()));
        plugin.save();say("Linia "+l.id+" czynna: "+l.timetable.describe()+", bilet "+plugin.economy().format(l.fare)+". Tablice: "+run.plan.boards().size()+".");
        run.stage="FIXTURES";
    }

    private void fixtures(){
        var infra=plugin.infrastructure();
        if(!infra.idle())return;
        LaunchState state=plugin.data().launches.get(run.plan.id());
        if(run.pendingFixture!=null){
            if(infra.registered(run.pendingFixture).isEmpty())throw new IllegalArgumentException("Nie zapisano stanowiska "+run.pendingFixture+": "+infra.status());
            if(!state.fixturesDone.contains(run.pendingFixture))state.fixturesDone.add(run.pendingFixture);run.pendingFixture=null;
        }
        World w=world();
        for(var f:run.plan.fixtures()){
            if(infra.registered(f.id()).isPresent()){if(!state.fixturesDone.contains(f.id()))state.fixturesDone.add(f.id());continue;}
            infra.registerAt(f.id(),SupplyKind.parse(f.kind()),f.station(),w,f.x(),f.y(),f.z(),f.rotation());
            run.pendingFixture=f.id();say("Rejestracja stanowiska "+f.id()+" ("+f.kind()+")…");return;
        }
        state.phase="DONE";state.reason="";touch(state);run.saving=plugin.saveDurable();run.stage="FINAL";
    }

    private void touch(LaunchState s){s.updated=LocalDateTime.now(plugin.zone()).withNano(0).toString();}
    private static long key(int x,int z){return((long)x<<32)|(z&0xffffffffL);}
    void close(){finish();}
}
