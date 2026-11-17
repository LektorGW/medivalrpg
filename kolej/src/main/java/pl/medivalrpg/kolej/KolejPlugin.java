// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import pl.medivalrpg.kolej.data.*;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.runtime.RailRuntime;
import pl.medivalrpg.kraje.api.*;
import pl.medivalrpg.kraje.model.*;
import pl.medivalrpg.skarbiec.api.*;

/** Company manager owns network and dispatch; drivers only control their assigned locomotive. */
public final class KolejPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private SkarbiecApi economy;
    private KrajeApi countries;
    private RailStore store;
    private RailData data;
    private TrackGraph graph;
    private RailRuntime runtime;
    private RailwayProjects projects;
    private BaseCommission commission;
    private NetworkCommission networkCommission;
    private NpcConsentService consents;
    private pl.medivalrpg.kolej.runtime.RailYard yard;
    private pl.medivalrpg.kolej.freight.FreightService freight;
    private pl.medivalrpg.kolej.infrastructure.InfrastructureService infrastructure;
    private pl.medivalrpg.kolej.signal.SemaphoreService semaphores;
    private LineLaunch launch;
    private StationBoards boards;
    private ZoneId zone;
    private final Map<UUID,org.bukkit.permissions.PermissionAttachment> managementPermissions=new HashMap<>();
    public SkarbiecApi economy(){return economy;}
    public KrajeApi countries(){return countries;}
    public RailData data(){return data;}
    public TrackGraph graph(){return graph;}
    public ZoneId zone(){return zone;}
    public RailRuntime runtime(){return runtime;}
    long consentFeesPaidFrom(String account){return consents==null?0:consents.paidFrom(account);}
    java.util.Optional<NetworkCommission.Budget> networkBudget(){return networkCommission==null?java.util.Optional.empty():networkCommission.feeBudget();}
    public String networkCommissionStatus(){return networkCommission==null?"Brak zlecenia budowy sieci":networkCommission.status();}
    public pl.medivalrpg.kolej.infrastructure.InfrastructureService infrastructure(){return infrastructure;}
    public Object railwayPanel(UUID actor,String method,Map<String,String> args){return RailwayPanel.invoke(this,projects,actor,method,args);}
    public boolean railwayManager(UUID actor){return RailwayPanel.allowed(actor,getConfig().getStringList("firma.manager-uuids"));}
    @Override public void onEnable() {
        saveDefaultConfig();zone=ZoneId.of(getConfig().getString("firma.strefa-czasu","Europe/Warsaw"));
        economy=Bukkit.getServicesManager().load(SkarbiecApi.class);countries=Bukkit.getServicesManager().load(KrajeApi.class);
        if(economy==null || countries==null){getLogger().severe("Brak usług Kraje/Skarbiec");Bukkit.getPluginManager().disablePlugin(this);return;}
        store=new RailStore(getDataFolder().toPath(),getLogger());
        try {data=store.load();graph=data.graph();}catch(Exception e){getLogger().severe(e.toString());Bukkit.getPluginManager().disablePlugin(this);return;}
        // Preserve the founder's existing land consent once; future revocations remain effective.
        if(!data.privateCarrierPermits){if(data.ownerCountry!=null)data.permits.add(data.ownerCountry);data.privateCarrierPermits=true;save();}
        installModels();
        runtime=new RailRuntime(this,data,graph);projects=new RailwayProjects(this);yard=new pl.medivalrpg.kolej.runtime.RailYard(this);freight=new pl.medivalrpg.kolej.freight.FreightService(this);commission=new BaseCommission(this,projects);
        consents=new NpcConsentService(this);
        networkCommission=new NetworkCommission(this,projects);
        infrastructure=new pl.medivalrpg.kolej.infrastructure.InfrastructureService(this);
        semaphores=new pl.medivalrpg.kolej.signal.SemaphoreService(this);
        launch=new LineLaunch(this,projects,()->networkCommission);
        boards=new StationBoards(this);
        Bukkit.getPluginManager().registerEvents(runtime,this);Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getOnlinePlayers().forEach(this::managementPermission);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,e->e.registrar().register(Commands.literal("kolej")
            .requires(s->s.getSender().hasPermission("kolej.use"))
            .executes(c->execute(c.getSource().getSender(),""))
            .then(Commands.argument("polecenie",StringArgumentType.greedyString()).suggests((ctx,b)->{
                for(String choice:List.of("siec status","siec postep","siec ponow","siec wstrzymaj","siec wznow","uruchom","uruchom glowna","uruchom glowna potwierdz"))if(choice.startsWith(b.getRemainingLowerCase()))b.suggest(choice);
                if("infrastruktura tankuj ".startsWith(b.getRemainingLowerCase()))b.suggest("infrastruktura tankuj ");
                for(String choice:List.of("semafor dodaj ","semafor usun ","semafor lista"))if(choice.startsWith(b.getRemainingLowerCase()))b.suggest(choice);
                String rest=b.getRemainingLowerCase();for(String choice:List.of("pomoc","panel","odjazdy","odjazdy ","infrastruktura dodaj ","infrastruktura usun ","infrastruktura obsluz ","infrastruktura lista","infrastruktura status","firma info","firma przypisz ","firma wplac ","stacja dodaj ","stacja lista","tor planuj ","tor potwierdz","tor status","ladunek status","baza zbuduj","baza potwierdz","pociag lista","pociag kup ","wagon dodaj ","linia utworz ","linia rozklad ","linia wlacz ","maszynista nadaj ","maszynista odbierz ","maszynista lista","npc maszynista zatrudnij ","npc maszynista przydziel ","npc maszynista lista","npc palacz zatrudnij ","npc palacz przydziel ","npc palacz lista","prowadz ","jedz ","ster ","hamulec ","wejdz ","zejdz","zgoda "))if(choice.startsWith(rest))b.suggest(choice);return b.buildFuture();})
            .executes(c->execute(c.getSource().getSender(),StringArgumentType.getString(c,"polecenie")))).build()));
        Bukkit.getScheduler().runTaskTimer(this,()->{try{runtime.tick();projects.tick();networkCommission.tick();yard.tick();freight.tick();commission.tick();infrastructure.tick();launch.tick();boards.tick();}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Błąd symulacji kolei",e);}finally{if(semaphores!=null)semaphores.tick();}},2,2);
        getLogger().info("Kolej by Maciej Kaczmarczyk — lektoro.pl");
    }
    public void save(){if(store!=null && data!=null){data.nodes=new ArrayList<>(graph.nodes());data.segments=new ArrayList<>(graph.segments());store.save(data);}}
    public java.util.concurrent.CompletableFuture<Void> saveDurable(){if(store==null || data==null)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Brak rejestru kolei"));data.nodes=new ArrayList<>(graph.nodes());data.segments=new ArrayList<>(graph.segments());return store.save(data);}
    @Override public void onDisable(){AuditMutationWatch.closeAll();if(launch!=null)launch.close();if(boards!=null)boards.close();managementPermissions.values().forEach(org.bukkit.permissions.PermissionAttachment::remove);managementPermissions.clear();if(semaphores!=null)semaphores.close();if(networkCommission!=null)networkCommission.close();if(consents!=null)consents.close();if(infrastructure!=null)infrastructure.close();if(commission!=null)commission.close();if(freight!=null)freight.close();if(yard!=null)yard.close();if(runtime!=null)runtime.close();if(projects!=null)projects.close();if(data!=null){save();store.close();}}
    private void installModels() {
        if(!getConfig().getBoolean("modele.instaluj",true))return;
        Path target=getDataFolder().toPath().resolveSibling("BetterModel").resolve("models");
        try {Files.createDirectories(target);for(String name:List.of("parowoz","tender","pasazerski","salonowy","kryty","weglarka","platforma","cysterna","hamulcowy")) {
            String filename="kolej_"+name+".bbmodel";try(var stream=getResource("models/"+filename)){if(stream!=null && !Files.exists(target.resolve(filename)))Files.copy(stream,target.resolve(filename));}
        }}catch(Exception e){getLogger().warning("Instalacja modeli: "+e.getMessage());}
        try {Files.createDirectories(target);for(String name:List.of("zuraw_wodny","wieza_cisnien","pompa_reczna","zbiornik_stacjonarny","bunkier_weglowy","semafor_mechaniczny","dzwignia_zwrotnicy","koziol_oporowy","tor_referencyjny","rozjazd_referencyjny","most_obrotnicy","zegar_stacyjny","lawka_peronowa","tablica_peronowa","warsztat_skrzynie_beczki","latarnia_olejowa")) {
            String filename="kolej_"+name+".bbmodel";try(var stream=getResource("infrastructure-models/"+filename)){if(stream!=null && !Files.exists(target.resolve(filename)))Files.copy(stream,target.resolve(filename));}
        }}catch(Exception e){getLogger().warning("Instalacja modeli infrastruktury: "+e.getMessage());}
    }
    public void message(CommandSender p,String s){p.sendMessage(Component.text("[Kolej] "+s));}
    public boolean isManager(CommandSender s){return managementAllowed(s instanceof Player p?p.getUniqueId():null,s.hasPermission("kolej.admin"),getConfig().getStringList("firma.manager-uuids"));}
    /** A refused retry is an expected answer for the manager, not a server error with a stack trace. */
    static String networkReply(java.util.function.Supplier<String> action){try{return action.get();}catch(IllegalStateException refused){return refused.getMessage()==null?"Ponowienie jest teraz niedostępne.":refused.getMessage();}}
    static boolean managementAllowed(UUID player,boolean permission,List<String> whitelist){return permission && (player==null || whitelist.isEmpty() || whitelist.contains(player.toString()));}
    private void manager(CommandSender s){if(!isManager(s))throw new IllegalArgumentException("Tylko wyznaczony zarządca przewoźnika.");}
    private void managementPermission(Player p){if(getConfig().getStringList("firma.manager-uuids").isEmpty())return;var old=managementPermissions.remove(p.getUniqueId());if(old!=null)old.remove();var attachment=p.addAttachment(this);attachment.setPermission("kolej.admin",getConfig().getStringList("firma.manager-uuids").contains(p.getUniqueId().toString()));managementPermissions.put(p.getUniqueId(),attachment);}
    @EventHandler public void managementJoin(org.bukkit.event.player.PlayerJoinEvent e){managementPermission(e.getPlayer());}
    @EventHandler public void managementQuit(org.bukkit.event.player.PlayerQuitEvent e){var attachment=managementPermissions.remove(e.getPlayer().getUniqueId());if(attachment!=null)attachment.remove();}
    private Player player(CommandSender s){if(!(s instanceof Player p))throw new IllegalArgumentException("Ta czynność wymaga gracza.");return p;}
    private void arg(String[] a,int count,String help){if(a.length<count)throw new IllegalArgumentException("Użycie: /kolej "+help);}
    private String id(String s){if(!RailData.identifier(s))throw new IllegalArgumentException("Id: 1–24 liter, cyfr, _ lub -.");return s;}
    public Train train(String id){Train t=data.trains.get(id);if(t==null)throw new IllegalArgumentException("Nie ma pociągu "+id);return t;}
    public TrackNode node(String id){return graph.nodeByName(id).orElseThrow(()->new IllegalArgumentException("Nie ma stacji "+id));}
    private Line line(String id){Line l=data.lines.get(id);if(l==null)throw new IllegalArgumentException("Nie ma linii "+id);return l;}
    private void pay(long amount,String reason){if(amount<0 || economy.withdraw(RailData.ACCOUNT,amount,reason)!=TxResult.OK)throw new IllegalArgumentException("Za mało środków firmy: "+economy.format(amount));}
    private UUID resolvePlayer(String name){var p=Bukkit.getOfflinePlayerIfCached(name);if(p==null)throw new IllegalArgumentException("Nieznany gracz, musi wcześniej wejść na serwer.");return p.getUniqueId();}
    public boolean allowed(Location at) {
        var country=countries.getCountryAt(at);if(country.isEmpty())return true;
        return pl.medivalrpg.kolej.permit.PermitRules.privateCarrierAllowed(country.get().id(),data.permits);
    }
    private void driver(Player p,Train t){if(!isManager(p) && !p.hasPermission("kolej.maszynista") && !data.drivers.containsKey(p.getUniqueId()))throw new IllegalArgumentException("Nie masz uprawnienia maszynisty.");
        String assigned=data.drivers.get(p.getUniqueId());if(!isManager(p) && assigned!=null && !assigned.equals("*") && !assigned.equals(t.id) && !assigned.equals(t.line))throw new IllegalArgumentException("Nie przydzielono ci tego składu ani linii.");}
    private int execute(CommandSender sender,String text) {
        try {
            String[] a=text.isBlank()?new String[]{"panel"}:text.trim().split("\\s+");
            switch(a[0].toLowerCase(Locale.ROOT)) {
                case "panel" -> panel(player(sender));
                case "pomoc" -> help(sender);
                case "siec" -> {
                    manager(sender);arg(a,2,"siec status|postep|ponow|wstrzymaj|wznow");
                    if(a.length!=2 || !Set.of("status","postep","ponow","wstrzymaj","wznow").contains(a[1]))throw new IllegalArgumentException("Użycie: /kolej siec status|postep|ponow|wstrzymaj|wznow");
                    if(networkCommission==null)throw new IllegalArgumentException("Brak zlecenia budowy sieci.");
                    if(a[1].equals("wstrzymaj")){message(sender,networkReply(networkCommission::pauseNetwork));break;}
                    if(a[1].equals("wznow")){message(sender,networkReply(networkCommission::resumeNetwork));break;}
                    if(a[1].equals("postep")){message(sender,networkCommission.progress().map(NetworkCommission.Progress::describe).orElse("Brak zlecenia budowy sieci."));message(sender,networkCommission.status());break;}
                    message(sender,networkReply(a[1].equals("ponow")?networkCommission::retryPreparation:networkCommission::status));
                    if(a[1].equals("status")){networkCommission.progress().ifPresent(p->message(sender,p.describe()));message(sender,projects.works().persistenceStatus());}
                }
                case "odjazdy" -> DepartureBoard.show(this,sender,a.length>1?a[1]:null);
                case "uruchom" -> {manager(sender);launch.command(sender,a);}
                case "infrastruktura" -> {manager(sender);infrastructure.command(player(sender),a);}
                case "semafor" -> {manager(sender);semaphores.command(player(sender),a);}
                case "firma" -> {
                    arg(a,2,"firma info|przypisz <kraj>|wplac <md>");
                    switch(a[1]) {
                        case "info" -> message(sender,data.companyName+" — konto: "+economy.format(economy.balance(RailData.ACCOUNT))+", pociągi: "+data.trains.size());
                        case "przypisz" -> {manager(sender);arg(a,3,"firma przypisz <kraj>");if(!data.trains.isEmpty() || !graph.nodes().isEmpty())throw new IllegalArgumentException("Kraj firmy ustawia się przed budową sieci.");data.ownerCountry=countries.getCountryByName(a[2]).orElseThrow(()->new IllegalArgumentException("Nie ma kraju.")).id();save();}
                        case "wplac" -> {arg(a,3,"firma wplac <md>");long amount=Long.parseLong(a[2]);if(amount<=0)throw new IllegalArgumentException("Kwota musi być dodatnia.");if(economy.transfer(SkarbiecApi.playerAccount(player(sender).getUniqueId()),RailData.ACCOUNT,amount,"Zasilenie przewoźnika")!=TxResult.OK)throw new IllegalArgumentException("Brak pieniędzy na koncie.");message(sender,"Wpłacono "+economy.format(amount));save();}
                        default -> throw new IllegalArgumentException("Nieznana czynność firmy.");
                    }
                }
                case "maszynista" -> {
                    manager(sender);arg(a,2,"maszynista nadaj|odbierz|lista");
                    if(a[1].equals("lista")){data.drivers.forEach((u,t)->message(sender,Bukkit.getOfflinePlayer(u).getName()+" → "+t));break;}
                    arg(a,3,"maszynista nadaj|odbierz <gracz> [pociag|linia]");UUID u=resolvePlayer(a[2]);
                    if(a[1].equals("nadaj"))data.drivers.put(u,a.length>3?a[3]:"*");else if(a[1].equals("odbierz")){data.drivers.remove(u);for(Train t:data.trains.values())if(u.equals(t.manualDriver)){t.manualDriver=null;t.steam.brake=1;t.steam.throttle=0;}}else throw new IllegalArgumentException("nadaj|odbierz|lista");save();message(sender,"Zapisano uprawnienia maszynisty.");
                }
                case "npc" -> {
                    manager(sender);arg(a,3,"npc maszynista|palacz zatrudnij|zwolnij|lista|przydziel");if(!Set.of(Crew.DRIVER,Crew.STOKER).contains(a[1]))throw new IllegalArgumentException("Dostępne zawody: maszynista, palacz.");
                    boolean stoker=a[1].equals(Crew.STOKER);
                    switch(a[2]) {
                        case "lista" -> data.crews.values().stream().filter(c->c.stoker()==stoker).forEach(c->message(sender,c.id+" "+c.name+" ("+c.roleName()+") → "+c.train+(c.paid?" (opłacony)":" (brak żołdu)")));
                        case "zatrudnij" -> {if(data.crews.size()>=32)throw new IllegalArgumentException("Limit 32 pracowników.");arg(a,4,"npc "+a[1]+" zatrudnij <imie>");String key=(stoker?"p":"m")+(System.currentTimeMillis());Crew c=new Crew();c.role=stoker?Crew.STOKER:Crew.DRIVER;pay(getConfig().getLong("koszty.zatrudnienie",500)+RailRuntime.wage(getConfig(),c),"Zatrudnienie i pierwszy żołd ("+c.role+")");c.id=key;c.name=a[3];c.paid=true;data.crews.put(key,c);message(sender,"Zatrudniono "+c.name+" ("+c.role+") — id: "+key);}
                        case "zwolnij" -> {arg(a,4,"npc "+a[1]+" zwolnij <id>");if(data.crews.remove(a[3])==null)throw new IllegalArgumentException("Nie ma pracownika.");}
                        case "przydziel" -> {arg(a,5,"npc "+a[1]+" przydziel <id> <pociag>");Crew c=data.crews.get(a[3]);if(c==null || c.stoker()!=stoker)throw new IllegalArgumentException("Nie ma takiego pracownika ("+a[1]+").");Train t=train(a[4]);if(data.crews.values().stream().anyMatch(cr->t.id.equals(cr.train) && cr!=c && cr.stoker()==stoker))throw new IllegalArgumentException("Skład ma już: "+a[1]+".");c.train=t.id;}
                        default -> throw new IllegalArgumentException("Nieznana czynność NPC.");
                    }save();
                }
                case "pociag" -> {
                    arg(a,2,"pociag lista|kup <id> <stacja>|info <id>|serwis <id>");
                    if(a[1].equals("lista")){data.trains.values().forEach(t->message(sender,t.id+" — "+t.status+", miejsca "+t.seats()));break;}
                    arg(a,3,"pociag kup|info|serwis <id> [stacja]");
                    if(a[1].equals("kup")){manager(sender);arg(a,4,"pociag kup <id> <stacja>");String key=id(a[2]);TrackNode n=node(a[3]);if(!n.isWaitingPoint() || !projects.ready(n.id()))throw new IllegalArgumentException("Stacja nie jest gotowa.");if(data.trains.containsKey(key) || data.trains.size()>=getConfig().getInt("firma.max-pociagow",12))throw new IllegalArgumentException("Id zajęte lub osiągnięty limit składów.");if(data.trains.values().stream().anyMatch(t->n.id().equals(t.node)))throw new IllegalArgumentException("Tor stacyjny jest zajęty.");pay(getConfig().getLong("koszty.pociag",20000),"Zakup parowozu "+key);Train t=new Train();t.id=key;t.node=n.id();data.trains.put(key,t);}
                    else {Train t=train(a[2]);if(a[1].equals("serwis")){manager(sender);if(t.running)throw new IllegalArgumentException("Zatrzymaj skład w stacji.");runtime.service(t);}else if(a[1].equals("info"))message(sender,t.id+" — "+t.status+String.format(Locale.ROOT," | %.1f bar | węgiel %.0f kg | woda %.0f l | %.1f km/h | uszkodzenia %.0f%%",t.steam.pressure,t.steam.coal,t.steam.water,t.steam.speed*3.6,t.steam.damage));else throw new IllegalArgumentException("Nieznana czynność pociągu.");}save();
                }
                case "wagon" -> {manager(sender);
                    if(a.length>=3 && a[1].equals("odczep")){Train t=train(a[2]);if(t.running || t.yardBase!=null || !t.cargo.isEmpty())throw new IllegalArgumentException("Najpierw zatrzymaj i rozładuj skład przy stacji.");for(Car car:t.cars)data.spareCars.merge(car,1,Integer::sum);t.cars.clear();save();message(sender,"Wagony odstawiono do rezerwy firmy.");break;}
                    arg(a,4,"wagon dodaj <pociag> <pasazerski|salonowy|kryty|weglarka|platforma|cysterna|hamulcowy>");if(!a[1].equals("dodaj") && !a[1].equals("dolacz"))throw new IllegalArgumentException("Użycie: wagon dodaj|dolacz albo odczep");Train t=train(a[2]);if(t.running || t.cars.size()>=getConfig().getInt("firma.max-wagonow",6))throw new IllegalArgumentException("Skład w ruchu lub za dużo wagonów.");Car car=Car.parse(a[3]);if(a[1].equals("dolacz")){int stock=data.spareCars.getOrDefault(car,0);if(stock<=0)throw new IllegalArgumentException("Brak takiego wagonu w rezerwie.");data.spareCars.put(car,stock-1);}else pay(getConfig().getLong("koszty.wagon",2000),"Wagon "+a[3]);t.cars.add(car);save();}
                case "linia" -> {
                    manager(sender);arg(a,2,"linia utworz|rozklad|wlacz|wylacz|przydziel|lista");
                    if(a[1].equals("lista")){data.lines.values().forEach(l->message(sender,l.id+" "+l.stops+" "+l.timetable.describe()+(l.active?" — czynna":" — wyłączona")));break;}
                    arg(a,3,"linia <czynność> <id>");
                    if(a[1].equals("utworz")){arg(a,5,"linia utworz <id> <stacja1> <stacja2> [kolejne]");if(data.lines.containsKey(a[2]))throw new IllegalArgumentException("Id linii zajęte.");Line l=new Line();l.id=id(a[2]);for(int i=3;i<a.length;i++)l.stops.add(node(a[i]).id());if(l.stops.stream().distinct().count()!=l.stops.size())throw new IllegalArgumentException("Stacje muszą być różne.");data.lines.put(l.id,l);}
                    else {Line l=line(a[2]);switch(a[1]){
                        case "rozklad" -> {arg(a,4,"linia rozklad <id> <co-ile-minut|HH:MM,HH:MM>");l.timetable=RailwayPanel.editedTimetable(l.timetable,a[3]);}
                        case "wlacz" -> {graph.routeThrough(l.stops,TrackSegment::isBuilt,true).orElseThrow(()->new IllegalArgumentException("Linia nie ma gotowych torów."));l.active=true;}
                        case "wylacz" -> l.active=false;
                        case "przydziel" -> {arg(a,4,"linia przydziel <linia> <pociag>");Train t=train(a[3]);if(t.running)throw new IllegalArgumentException("Skład jest w ruchu.");t.line=l.id;}
                        case "bilet" -> {arg(a,4,"linia bilet <linia> <md>");long fare=Long.parseLong(a[3]);if(fare<0 || fare>10000)throw new IllegalArgumentException("Cena 0–10000 md.");l.fare=fare;}
                        default -> throw new IllegalArgumentException("Nieznana czynność linii.");
                    }}save();
                }
                case "prowadz" -> {arg(a,2,"prowadz <pociag>");Player p=player(sender);Train t=train(a[1]);driver(p,t);Location at=runtime.location(t,0);if(at==null || !at.getWorld().equals(p.getWorld()) || at.distanceSquared(p.getLocation())>100)throw new IllegalArgumentException("Podejdź do lokomotywy (10 bloków).");if(t.manualDriver!=null && !t.manualDriver.equals(p.getUniqueId()))throw new IllegalArgumentException("Ster zajęty.");runtime.mountDriver(p,t);t.manualDriver=p.getUniqueId();t.steam.fire=true;message(sender,"Objąłeś ster: /kolej jedz <pociąg> <stacja>, /kolej ster <pociąg> 0..100, /kolej hamulec <pociąg> 0..100.");}
                case "jedz" -> {arg(a,3,"jedz <pociag> <stacja>");Train t=train(a[1]);if(!(isManager(sender) || player(sender).getUniqueId().equals(t.manualDriver)))throw new IllegalArgumentException("Najpierw obejmij ster.");runtime.depart(t,node(a[2]).id());save();}
                case "ster","hamulec" -> {arg(a,3,a[0]+" <pociag> <0..100>");Train t=train(a[1]);Player p=player(sender);driver(p,t);if(!p.getUniqueId().equals(t.manualDriver))throw new IllegalArgumentException("Najpierw obejmij ster tego pociągu.");double value=Double.parseDouble(a[2]);if(!Double.isFinite(value) || value<0 || value>100)throw new IllegalArgumentException("Zakres 0–100.");if(a[0].equals("ster")){t.steam.throttle=value/100;t.steam.fire=true;}else t.steam.brake=value/100;}
                case "wejdz" -> {arg(a,2,"wejdz <pociag>");runtime.board(player(sender),train(a[1]));}
                case "zejdz" -> {Player p=player(sender);runtime.leave(p);for(Train t:data.trains.values())if(p.getUniqueId().equals(t.manualDriver)){t.manualDriver=null;t.steam.throttle=0;t.steam.brake=1;}}
                case "zgoda" -> {
                    arg(a,2,"zgoda tak|nie albo zgoda <kraj-NPC>");
                    if(!a[1].equals("tak") && !a[1].equals("nie")) {
                        manager(sender);KrajInfo target=countries.getCountryByName(a[1]).orElseThrow(()->new IllegalArgumentException("Nie ma kraju."));
                        if(!target.npc() || !getConfig().getBoolean("zgody.npc-przyjmuja-wnioski",true))throw new IllegalArgumentException("Władca NPC nie przyjmuje obecnie wniosków przewoźnika.");
                        if(a.length>2 && !Set.of("firma","budzet").contains(a[2]))throw new IllegalArgumentException("Użycie: /kolej zgoda <kraj-NPC> [firma|budzet] — kto płaci ewentualną opłatę.");
                        consents.request(sender,target,a.length>2?a[2]:"firma");break;
                    }
                    Player p=player(sender);KrajInfo country=countries.getCountryOf(p.getUniqueId()).orElseThrow(()->new IllegalArgumentException("Nie należysz do państwa."));if(!country.ruler().equals(p.getUniqueId()))throw new IllegalArgumentException("Zgodę nadaje władca.");if(a[1].equals("tak"))data.permits.add(country.id());else data.permits.remove(country.id());save();message(sender,"Zapisano zgodę na roboty i przejazdy prywatnego przewoźnika. Sojusz nie jest wymagany.");
                }
                case "ladunek" -> freight.command(player(sender),a);
                case "baza" -> {manager(sender);if(a.length>1 && (a[1].equals("wprowadz") || a[1].equals("wyprowadz")))yard.command(player(sender),a);else projects.command(player(sender),a);}
                case "stacja","tor","droga" -> {manager(sender);projects.command(player(sender),a);}
                default -> throw new IllegalArgumentException("Nieznana komenda. /kolej pomoc");
            }
            return 1;
        }catch(IllegalArgumentException e){message(sender,e.getMessage()==null?"Nieprawidłowe argumenty.":e.getMessage());return 0;}
        catch(Exception e){getLogger().log(java.util.logging.Level.WARNING,"Polecenie kolej",e);message(sender,"Operacja nie powiodła się; sprawdź log serwera.");return 0;}
    }
    private static final class Menu implements InventoryHolder {
        Inventory inventory;
        public Inventory getInventory(){return inventory;}
    }
    private void panel(Player p) {
        Menu holder=new Menu();holder.inventory=Bukkit.createInventory(holder,54,Component.text(data.companyName));
        int slot=0;for(Train t:data.trains.values()){if(slot>=45)break;ItemStack item=ItemStack.of(Material.MINECART);var meta=item.getItemMeta();meta.displayName(Component.text(t.id+" — "+t.status));meta.lore(List.of(Component.text("Miejsca: "+t.seats()+" | Kliknij, aby wejść")));item.setItemMeta(meta);holder.inventory.setItem(slot++,item);}
        p.openInventory(holder.inventory);
    }
    @EventHandler public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Menu))return;e.setCancelled(true);if(e.getWhoClicked() instanceof Player p && e.getRawSlot()>=0 && e.getRawSlot()<Math.min(data.trains.size(),45)){String id=new ArrayList<>(data.trains.keySet()).get(e.getRawSlot());p.closeInventory();execute(p,"wejdz "+id);}}
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true);}
    private void help(CommandSender p) {
        message(p,"Przewoźnik zarządza siecią i rozkładem. Pasażer: /kolej → kliknij skład, /kolej zejdz.");
        message(p,"/kolej odjazdy [stacja] — najbliższe odjazdy.");
        if(isManager(p))message(p,"/kolej zgoda <kraj-NPC> [firma|budzet] — wniosek do władcy NPC; odpowiada AI kraju, czasem za opłatą do jego skarbca.");
        if(isManager(p))message(p,"/kolej siec status|postep|ponow — stan i postęp budowy sieci lub ponowienie błędnej wyceny przed płatnością (maks. 3 razy na część). /kolej siec wstrzymaj|wznow — trwała pauza i wznowienie bez restartu; wznow zapisuje zgodę startu aktualnego planu.");
        if(isManager(p))message(p,"/kolej uruchom [plan] [potwierdz] — po odbiorze torów: zakup składów, załogi NPC, rozkład, bilety, tablice i stanowiska wody/węgla z uruchomienie.yml (najpierw sprawdzenie i koszty).");
        if(isManager(p))message(p,"/kolej npc palacz zatrudnij|przydziel|lista|zwolnij — palacz NPC na parowozie (żołd firma.dzienny-zold-palacza).");
        if(isManager(p))message(p,"/kolej infrastruktura dodaj <id> <zuraw|wieza|pompa|zbiornik|wegiel> <stacja> <0|90|180|270>; obsluz <id> <pociag> <1..64>; lista|status|usun <id>.");
        if(isManager(p))message(p,"/kolej semafor dodaj <id> <wezel> <odcinek> <0|90|180|270>; /kolej semafor lista; /kolej semafor usun <id>.");
        for(String command:List.of("pociag lista","linia lista","firma info"))p.sendMessage(Component.text("[ /kolej "+command+" ]").clickEvent(ClickEvent.runCommand("/kolej "+command)));
        if(isManager(p))for(String command:List.of("firma przypisz <kraj>","firma wplac <md>","stacja dodaj <id>","tor planuj <od> <do> [realistyczny|klasyczny]","tor potwierdz","tor status","ladunek status","baza zbuduj [0|90|180|270]","baza potwierdz","baza wprowadz <pociag> <baza> <stanowisko>","baza wyprowadz <pociag>","ladunek magazyn <stacja>","ladunek zlec <pociag> <material> <ilosc> <od> <do>","pociag kup <id> <stacja>","wagon dodaj <pociag> <typ>","maszynista nadaj <gracz>","npc maszynista zatrudnij <imie>","npc maszynista przydziel <id> <pociag>","linia utworz <id> <stacja1> <stacja2>","linia rozklad <id> <minuty|HH:MM,HH:MM>","linia przydziel <id> <pociag>","linia wlacz <id>"))p.sendMessage(Component.text("/kolej "+command).clickEvent(ClickEvent.suggestCommand("/kolej "+command.split(" <")[0]+" ")));
    }
}
