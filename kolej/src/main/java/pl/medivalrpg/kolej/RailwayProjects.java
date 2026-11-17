// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import com.google.gson.Gson;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import pl.medivalrpg.kolej.build.*;
import pl.medivalrpg.kolej.depot.DepotPlan;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.survey.*;
import pl.medivalrpg.kolej.world.PlanRotation;
import pl.medivalrpg.skarbiec.api.*;

/** Manager's preview/confirm flow. Terrain capture is bounded per tick; A* runs on its immutable copy. */
final class RailwayProjects implements AutoCloseable {
    private record Pending(String id,UUID world,List<BlockOp> ops,TrackNode node,TrackSegment segment,pl.medivalrpg.kolej.data.RailData.Depot depot,long expires,List<RailReuse.Check> reused) {
        Pending(String id,UUID world,List<BlockOp> ops,TrackNode node,TrackSegment segment,pl.medivalrpg.kolej.data.RailData.Depot depot,long expires){this(id,world,ops,node,segment,depot,expires,List.of());}
    }
    private record Quote(Pending plan,UUID country,long price,int blocks){}
    private final Map<UUID,Quote> quotes=new HashMap<>();
    private final Set<UUID> preparingActors=new HashSet<>();
    private final ConstructionJournal transactions;
    private final List<ConstructionTransaction> committing=new ArrayList<>();
    private java.util.function.Predicate<String> suspendedNetwork=id->false;
    void suspendNetwork(java.util.function.Predicate<String> predicate){suspendedNetwork=predicate;works.suspendJobs(predicate);}
    boolean networkPauseSettled(){return committing.stream().filter(tx->suspendedNetwork.test(tx.intent().id())).allMatch(ConstructionTransaction::pauseSettled);}
    private final Map<String,CompletableFuture<String>> acceptance=new HashMap<>();
    private final KolejPlugin plugin;
    private final RailwayWorks works;
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Set<UUID> surveying=new HashSet<>();
    private final Map<UUID,List<int[]>> waypoints=new HashMap<>();
    private final ExecutorService planner=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Kolej-Projektant");t.setDaemon(true);return t;});
    private volatile boolean closed;
    // Persist intentions and audit metadata only. Block plans and confirmation tokens never survive a restart.
    static final class PanelRequest {
        String id, kind, from="", to="", world, state="REQUESTED", reason="", job="", token="";
        UUID actor, country;
        int x,y,z,rotation,blocks;
        long price,expires;
        transient Pending quoted;
    }
    private final Map<String,PanelRequest> requests=new LinkedHashMap<>();
    private final Map<UUID,String> panelSurvey=new HashMap<>();
    private final ExecutorService requestIo=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Kolej-Wnioski-IO");t.setDaemon(true);return t;});
    private volatile String requestSaveFailure;
    private Future<?> requestWrite;
    RailwayProjects(KolejPlugin plugin) {
        this.plugin=plugin;works=new RailwayWorks(plugin,plugin::allowed);
        works.onComplete(job->{TrackSegment s=plugin.graph().segment(job.id());if(s!=null){s.status(SegmentStatus.BUILT);plugin.save();}});
        try{transactions=new ConstructionJournal(plugin.getDataFolder().toPath().resolve("works/transactions.json"));}
        catch(Exception ex){works.close();throw new IllegalStateException("Nie można odczytać transakcji budowy. Nie pobrano opłat.",ex);}
        try {
            Path file=requestFile();
            if(Files.exists(file))for(PanelRequest r:new Gson().fromJson(Files.readString(file),PanelRequest[].class)) {
                restoreRequest(r);
                if(requests.size()>=200 || requests.containsKey(r.id))throw new IllegalArgumentException("Nieprawidłowy wniosek");
                requests.put(r.id,r);
            }
        }catch(Exception ex){transactions.close();requestIo.shutdown();works.close();throw new IllegalStateException("Nie można odczytać wniosków panelu kolei",ex);}
        for(var saved:transactions.intents()) {
            var recovered=ConstructionTransaction.recover(saved);
            var job=works.job(saved.id());
            if(ConstructionTransaction.canRecoverUnpaid(recovered,job))
                committing.add(ConstructionTransaction.resumeUnpaid(recovered,transactionPorts(),job.isPresent()));
            else if(ConstructionTransaction.canRecoverPaid(recovered,job))
                committing.add(ConstructionTransaction.resumePaid(recovered,transactionPorts()));
            else if(!saved.phase().equals("DECLINED")) {
                var blocked=recovered.phase("RECONCILE","Restart: sprawdź dziennik Skarbca, cenę, graf i zlecenie. Bez ponownej opłaty.");
                transactions.checkpoint(blocked);markRequest(blocked,"RECONCILE");
                var segment=plugin.graph().segment(saved.id());if(segment!=null)segment.status(SegmentStatus.BUILDING);
                plugin.getLogger().severe("Budowa "+saved.id()+": "+blocked.reason());
            }
        }
    }
    RailwayWorks works(){return works;}
    boolean ready(String id){
        // Only DONE already on disk: purchases and launches pay after this check.
        if(works.durablyDone(id))return true;
        var proof=plugin.data().commissionedNodeJobs==null?null:plugin.data().commissionedNodeJobs.get(id);
        return plugin.graph().node(id)!=null && certifiedReady(proof,works::durablyDone);
    }
    static boolean certifiedReady(List<String> proof,java.util.function.Predicate<String> done) {
        return proof!=null && !proof.isEmpty() && proof.size()<=64 && new HashSet<>(proof).size()==proof.size()
            && proof.stream().allMatch(id->pl.medivalrpg.kolej.data.RailData.identifier(id) && done.test(id));
    }
    void tick(){for(var tx:List.copyOf(committing))tx.tick(suspendedNetwork.test(tx.intent().id()));committing.removeIf(ConstructionTransaction::finished);works.tick();pending.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());
        for(var r:requests.values())if(r.state.equals("QUOTED") && r.expires<System.currentTimeMillis()){r.state="EXPIRED";r.token="";r.quoted=null;saveRequests();}}
    private Path requestFile(){return plugin.getDataFolder().toPath().resolve("works/panel-requests.json");}
    private void saveRequests() {
        String json=new Gson().toJson(requests.values());Path file=requestFile();
        requestWrite=requestIo.submit(()->{try {
            Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(tmp,json);
            if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception ex){requestSaveFailure="Nie udało się zapisać wniosków budowy.";plugin.getLogger().severe(requestSaveFailure+" "+ex.getMessage());}});
    }
    List<Map<String,Object>> panelRequests() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var r:requests.values()) {Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",r.id);row.put("kind",r.kind);row.put("from",r.from);row.put("to",r.to);row.put("world",r.world);
            row.put("state",r.state);row.put("reason",requestSaveFailure==null?r.reason:requestSaveFailure);row.put("job",r.job);
            row.put("price",r.price);row.put("blocks",r.blocks);row.put("expires",r.expires);row.put("token",r.state.equals("QUOTED")?r.token:"");
            row.put("position",List.of(r.x,r.y,r.z));rows.add(row);
        }return rows;
    }
    static void requestKind(String kind){if(!Set.of("station","rail","road").contains(Objects.toString(kind,"")))throw new IllegalArgumentException("Wybierz stację, tor lub drogę.");}
    static void restoreRequest(PanelRequest r) {
        if(r==null || r.actor==null || r.country==null || r.world==null || !pl.medivalrpg.kolej.data.RailData.identifier(r.id) || !Set.of("REQUESTED","SURVEYING","QUOTED","EXPIRED","FAILED","SUBMITTED","CANCELLED","RECONCILE").contains(Objects.toString(r.state,"")))throw new IllegalArgumentException("Nieprawidłowy wniosek");
        requestKind(r.kind);r.token="";r.quoted=null;
        if(Set.of("SURVEYING","QUOTED").contains(r.state)){r.state="EXPIRED";r.reason="Po uruchomieniu serwera wymagana nowa wycena.";}
    }
    static void validQuote(PanelRequest r,UUID actor,String token,long now) {
        if(!Objects.equals(r.actor,actor) || !"QUOTED".equals(r.state) || r.expires<now || r.token==null || r.token.isBlank() || !r.token.equals(token))
            throw new IllegalArgumentException("Wycena wygasła lub została już wykorzystana. Wykonaj nową wycenę.");
    }
    private Player panelPlayer(UUID actor,String world) {
        Player p=Bukkit.getPlayer(actor);
        if(p==null || !p.isOnline() || !p.getWorld().getName().equals(world))throw new IllegalArgumentException("Zaloguj się do gry w świecie projektu: "+world);
        return p;
    }
    private void endpoints(PanelRequest r) {
        TrackNode from=plugin.node(r.from),to=plugin.node(r.to);
        if(from.id().equals(to.id()) || !from.world().equals(to.world()) || !from.world().equals(r.world) || !from.isWaitingPoint() || !to.isWaitingPoint())throw new IllegalArgumentException("Wybierz dwie różne stacje w jednym świecie.");
        if(!ready(from.id()) || !ready(to.id()))throw new IllegalArgumentException("Najpierw ukończ obie stacje.");
        if(Math.hypot((double)to.x()-from.x(),(double)to.z()-from.z())>Math.min(1024,plugin.getConfig().getInt("budowa.max-dlugosc",1024)))throw new IllegalArgumentException("Za długi odcinek. Wybierz stację pośrednią.");
    }
    Object panelRequest(UUID actor,String method,Map<String,String> args) {
        if(requestSaveFailure!=null)throw new IllegalArgumentException(requestSaveFailure);
        if(plugin.data().ownerCountry==null)throw new IllegalArgumentException("Najpierw przypisz państwo właściciela firmy w grze.");
        String id=RailwayPanel.id(args,"id");
        if(method.equals("railway.request.create")) {
            if(requests.containsKey(id))throw new IllegalArgumentException("Id wniosku jest zajęte.");
            if(requests.size()>=200)throw new IllegalArgumentException("Osiągnięto limit 200 zapisanych wniosków.");
            PanelRequest r=new PanelRequest();r.id=id;r.actor=actor;r.country=plugin.data().ownerCountry;r.kind=args.get("kind");requestKind(r.kind);
            if(r.kind.equals("station")) {
                Player p=Bukkit.getPlayer(actor);if(p==null || !p.isOnline())throw new IllegalArgumentException("Stań w grze w miejscu nowej stacji.");
                checkFree(id);r.world=p.getWorld().getName();r.x=p.getLocation().getBlockX();r.y=p.getLocation().getBlockY()-1;r.z=p.getLocation().getBlockZ();
                int angle=(int)RailwayPanel.number(args,"rotation",0,270);if(angle%90!=0)throw new IllegalArgumentException("Obrót: 0, 90, 180 lub 270.");r.rotation=angle/90;
            } else {r.from=RailwayPanel.id(args,"from");r.to=RailwayPanel.id(args,"to");r.world=plugin.node(r.from).world();endpoints(r);}
            requests.put(id,r);saveRequests();return Map.of("ok",true,"id",id);
        }
        PanelRequest r=requests.get(id);
        if(r==null || !actor.equals(r.actor))throw new IllegalArgumentException("Nie ma twojego wniosku.");
        if(!Objects.equals(r.country,plugin.data().ownerCountry))throw new IllegalArgumentException("Zmienił się właściciel firmy. Utwórz nowy wniosek.");
        if(method.equals("railway.request.cancel")) {
            if(Set.of("SUBMITTED","CANCELLED","RECONCILE").contains(r.state))throw new IllegalArgumentException("Wniosek został już zamknięty.");
            if(r.state.equals("SURVEYING"))throw new IllegalArgumentException("Poczekaj na zakończenie pomiaru.");
            if(r.quoted!=null)pending.remove(actor,r.quoted);r.quoted=null;r.token="";r.state="CANCELLED";saveRequests();return Map.of("ok",true);
        }
        Player p=panelPlayer(actor,r.world);
        if(method.equals("railway.request.confirm")) {
            if(requestWrite!=null && !requestWrite.isDone())throw new IllegalArgumentException("Trwa zapis wniosku. Spróbuj ponownie za chwilę.");
            validQuote(r,actor,args.get("token"),System.currentTimeMillis());
            if(r.quoted==null || pending.get(actor)!=r.quoted)throw new IllegalArgumentException("Podgląd zmienił się. Wykonaj ponowną wycenę.");
            // Consume the exact quote once. The shared asynchronous confirmer validates the world only once.
            r.state="SUBMITTED";r.token="";r.reason="Trwa walidacja i trwały zapis transakcji. Budowa jeszcze nie ruszyła.";
            try{confirm(p);return Map.of("ok",true,"job",r.job);}catch(RuntimeException ex){r.state="FAILED";r.reason=detail(ex);r.quoted=null;throw ex;}finally{saveRequests();}
        }
        if(!method.equals("railway.request.quote") || Set.of("SUBMITTED","CANCELLED","SURVEYING","RECONCILE").contains(r.state))throw new IllegalArgumentException("Wniosku nie można teraz wycenić.");
        if(surveying.contains(actor) || requests.values().stream().anyMatch(other->other!=r && other.actor.equals(actor) && Set.of("QUOTED","SURVEYING").contains(other.state)))throw new IllegalArgumentException("Najpierw zakończ lub anuluj poprzednią wycenę.");
        r.state="SURVEYING";r.reason="";r.token="";r.quoted=null;panelSurvey.put(actor,id);saveRequests();
        try {
            if(r.kind.equals("station")) {
                checkFree(r.id);var node=new TrackNode(r.id,r.id,NodeType.STATION,r.world,r.x,r.y+1,r.z,r.rotation%2,1);
                preview(p,new Pending(r.id,p.getWorld().getUID(),PlanRotation.transform(Factory.station(0,0,-64,128),r.x,r.y,r.z,r.rotation),node,null,null,expiry()));
            }else {endpoints(r);boolean road=r.kind.equals("road");survey(p,plugin.node(r.from),plugin.node(r.to),road,List.of(),road?TrackStyle.CLASSIC:configuredStyle());}
        }catch(RuntimeException ex){failPanelSurvey(actor,ex.getMessage());throw ex;}
        return Map.of("ok",true,"id",id);
    }
    private void failPanelSurvey(UUID actor,String reason) {
        String id=panelSurvey.remove(actor);if(id==null)return;
        PanelRequest r=requests.get(id);r.state="FAILED";r.reason=Objects.toString(reason,"Pomiar przerwany.");saveRequests();
    }
    void command(Player p,String[] a) {
        String group=a[0],action=a.length>1?a[1]:"status";
        if((panelSurvey.containsKey(p.getUniqueId()) || preparingActors.contains(p.getUniqueId())) && !Set.of("status","lista").contains(action))throw new IllegalArgumentException("Poczekaj na zakończenie pomiaru z panelu.");
        if(action.equals("punkt")){List<int[]> points=waypoints.computeIfAbsent(p.getUniqueId(),id->new ArrayList<>());if(points.size()>=12)throw new IllegalArgumentException("Maksymalnie 12 punktów pośrednich.");points.add(new int[]{p.getLocation().getBlockX(),p.getLocation().getBlockZ()});plugin.message(p,"Dodano punkt przebiegu nr "+points.size());return;}
        if(action.equals("wyczysc")){waypoints.remove(p.getUniqueId());plugin.message(p,"Wyczyszczono punkty przebiegu.");return;}
        if(action.equals("status") || action.equals("lista")) {for(var j:works.snapshot().jobs())plugin.message(p,j.id()+" "+j.status()+" "+j.cursor()+"/"+j.entries().size()+" "+j.reason());return;}
        if(action.equals("potwierdz")){confirm(p);return;}
        if(action.equals("anuluj")) {if(a.length>2)works.cancel(a[2]);pending.remove(p.getUniqueId());works.clearPreview(p);return;}
        if(action.equals("wznow")){if(a.length<3 || !works.resume(a[2]))throw new IllegalArgumentException("Podaj id zablokowanej budowy.");return;}
        if(plugin.data().ownerCountry==null)throw new IllegalArgumentException("Najpierw /kolej firma przypisz <kraj>.");
        int x=p.getLocation().getBlockX(),z=p.getLocation().getBlockZ(),ground=p.getLocation().getBlockY()-1;
        switch(group) {
            case "stacja" -> {
                if(!action.equals("dodaj") || a.length<3)throw new IllegalArgumentException("/kolej stacja dodaj <id> [0|90|180|270], potem /kolej stacja potwierdz");
                String id=identifier(a[2]);checkFree(id);int rot=rotation(a,3,p);boolean alongX=rot%2==1;
                TrackNode node=new TrackNode(id,id,NodeType.STATION,p.getWorld().getName(),x,ground+1,z,alongX?1:0,1);
                var ops=PlanRotation.transform(Factory.station(0,0,-64,128),x,ground,z,rot);
                preview(p,new Pending(id,p.getWorld().getUID(),ops,node,null,null,expiry()));
            }
            case "baza" -> {
                if(!action.equals("zbuduj"))throw new IllegalArgumentException("/kolej baza zbuduj [0|90|180|270]");
                int rot=rotation(a,2,p);String id=nextId("baza");checkFree(id);
                int[] gate=PlanRotation.point(0,-18,rot);
                TrackNode node=new TrackNode(id,"Baza przewoźnika",NodeType.DEPOT,p.getWorld().getName(),x+gate[0],ground+1,z+gate[1],rot%2,1);
                if(plugin.countries().getCountryAt(p.getLocation()).map(c->!c.id().equals(plugin.data().ownerCountry)).orElse(true))throw new IllegalArgumentException("Baza musi stanąć na ziemi państwa właściciela firmy.");
                preview(p,new Pending(id,p.getWorld().getUID(),DepotPlan.plan(x,ground,z,rot),node,null,new pl.medivalrpg.kolej.data.RailData.Depot(x,ground,z,rot),expiry()));
            }
            case "tor","droga" -> {
                if(!action.equals("planuj") || a.length<4 || a.length>(group.equals("tor")?5:4))throw new IllegalArgumentException("/kolej "+group+" planuj <stacja-od> <stacja-do>"+(group.equals("tor")?" [realistyczny|klasyczny]":""));
                TrackStyle style=group.equals("droga")?TrackStyle.CLASSIC:a.length>4?TrackStyle.parse(a[4]):configuredStyle();
                TrackNode from=plugin.node(a[2]),to=plugin.node(a[3]);
                if(!from.world().equals(to.world()) || !p.getWorld().getName().equals(from.world()))throw new IllegalArgumentException("Obie stacje i projektant muszą być w tym samym świecie.");
                if(from.id().equals(to.id()))throw new IllegalArgumentException("Wybierz różne stacje.");
                if(!ready(from.id()) || !ready(to.id()))throw new IllegalArgumentException("Najpierw ukończ budowę obu stacji.");
                survey(p,from,to,group.equals("droga"),waypoints.getOrDefault(p.getUniqueId(),List.of()).stream().map(int[]::clone).toList(),style);
            }
            default -> throw new IllegalArgumentException("/kolej pomoc");
        }
    }
    private int rotation(String[] a,int index,Player p) {
        if(a.length<=index)return Math.floorMod(Math.round(p.getLocation().getYaw()/90),4);
        int angle=Integer.parseInt(a[index]);if(angle%90!=0)throw new IllegalArgumentException("Obrót: 0, 90, 180 lub 270.");return Math.floorMod(angle/90,4);
    }
    private String identifier(String id){if(!pl.medivalrpg.kolej.data.RailData.identifier(id))throw new IllegalArgumentException("Nieprawidłowe id.");return id;}
    private String nextId(String prefix){int i=1;while(plugin.graph().node(prefix+i)!=null || plugin.graph().segment(prefix+i)!=null || works.job(prefix+i).isPresent() || transactions.contains(prefix+i))i++;return prefix+i;}
    private void checkFree(String id){if(plugin.graph().node(id)!=null || plugin.graph().segment(id)!=null || works.job(id).isPresent() || transactions.contains(id))throw new IllegalArgumentException("Id jest już zajęte.");}
    private long expiry(){return System.currentTimeMillis()+180000;}
    private void preview(Player p,Pending initial) {
        UUID actor=p.getUniqueId(),country=plugin.data().ownerCountry;
        if(!preparingActors.add(actor))throw new IllegalArgumentException("Trwa przygotowanie projektu.");
        works.prepareAsync(p.getWorld(),initial.ops,country,initial.reused).whenComplete((prepared,error)->{
            preparingActors.remove(actor);
            if(closed)return;
            if(error!=null){failPanelSurvey(actor,detail(error));plugin.message(p,detail(error));return;}
            if(!p.isOnline() || !p.getWorld().getUID().equals(initial.world) || !Objects.equals(country,plugin.data().ownerCountry)){failPanelSurvey(actor,"Zmieniono świat lub właściciela firmy.");return;}
            Pending plan=new Pending(initial.id,initial.world,initial.ops,initial.node,initial.segment,initial.depot,expiry(),initial.reused);
            long price=cost(plan,prepared.preview().cost());
            pending.put(actor,plan);quotes.put(actor,new Quote(plan,country,price,prepared.preview().blocks()));
            works.showPreparedPreview(p,prepared);
            String requestId=panelSurvey.remove(actor);
            if(requestId!=null){PanelRequest r=requests.get(requestId);r.state="QUOTED";r.quoted=plan;r.price=price;r.blocks=prepared.preview().blocks();r.expires=plan.expires;r.token=UUID.randomUUID().toString();r.job=plan.id;saveRequests();}
            plugin.message(p,"Projekt "+plan.id+": "+prepared.preview().blocks()+" zmian, koszt "+plugin.economy().format(price)+". Potwierdź w 3 min: /kolej "+(plan.node!=null?(plan.node.isDepot()?"baza":"stacja"):"tor")+" potwierdz");
        });
    }
    private static String detail(Throwable error){while(error.getCause()!=null)error=error.getCause();return Objects.toString(error.getMessage(),"Nie udało się przygotować budowy.");}
    private long cost(Pending p,long changes){return Math.addExact(Math.multiplyExact(changes,Math.max(0,plugin.getConfig().getLong("koszty.blok-budowy",1))),p.node==null?0:plugin.getConfig().getLong(p.node.isDepot()?"koszty.baza":"koszty.stacja",3000));}
    private java.lang.reflect.Method bankBarrier() {
        var bank=Bukkit.getPluginManager().getPlugin("Skarbiec");
        try{if(bank==null || !bank.isEnabled())throw new ReflectiveOperationException();return bank.getClass().getMethod("bankDurable");}
        catch(ReflectiveOperationException ex){throw new IllegalArgumentException("Skarbiec nie potwierdza trwałości płatności. Nie pobrano opłaty.");}
    }
    private Future<?> paymentDurable() {
        try{Object result=bankBarrier().invoke(Bukkit.getPluginManager().getPlugin("Skarbiec"));if(result instanceof Future<?> future)return future;}
        catch(ReflectiveOperationException ex){return CompletableFuture.failedFuture(ex);}
        return CompletableFuture.failedFuture(new IllegalStateException("Nieprawidłowe potwierdzenie Skarbca"));
    }
    private void confirm(Player p) {
        UUID actor=p.getUniqueId();Pending plan=pending.remove(actor);Quote quoted=quotes.remove(actor);
        if(plan==null || quoted==null || quoted.plan!=plan || plan.expires<System.currentTimeMillis())throw new IllegalArgumentException("Brak aktualnego podglądu.");
        if(!p.getWorld().getUID().equals(plan.world) || !Objects.equals(quoted.country,plugin.data().ownerCountry))throw new IllegalArgumentException("Zmieniono świat lub właściciela firmy.");
        bankBarrier();checkFree(plan.id);
        if(!preparingActors.add(actor))throw new IllegalArgumentException("Trwa przygotowanie projektu.");
        works.prepareAsync(p.getWorld(),plan.ops,quoted.country,plan.reused).whenComplete((prepared,error)->{
            preparingActors.remove(actor);if(closed)return;
            if(error!=null){failedConfirmation(plan,detail(error));return;}
            try {
                if(!p.isOnline() || !plugin.railwayManager(actor) || !p.getWorld().getUID().equals(plan.world) || !Objects.equals(quoted.country,plugin.data().ownerCountry))throw new IllegalArgumentException("Zmieniono świat, zarządcę lub właściciela firmy.");
                if(cost(plan,prepared.preview().cost())!=quoted.price || prepared.preview().blocks()!=quoted.blocks)throw new IllegalArgumentException("Teren lub cena zmieniły się. Wykonaj ponowną wycenę.");
                checkFree(plan.id);validateGraphEndpoints(plan);
                var graph=new ConstructionJournal.GraphIntent(plan.node,plan.segment==null?null:plan.segment.from(),plan.segment==null?null:plan.segment.to(),plan.segment==null?List.of():plan.segment.cells(),plan.depot);
                submitPrepared(plan.id,actor,prepared,quoted.price,graph);
                plugin.message(p,"Zapisuję zlecenie "+plan.id+" przed płatnością. Roboty ruszą po potwierdzeniu zapisów.");
            }catch(Exception ex){failedConfirmation(plan,detail(ex));}
        });
    }
    private void validateGraphEndpoints(Pending plan) {
        if(plan.segment==null)return;
        var cells=plan.segment.cells();TrackNode from=plugin.node(plan.segment.from()),to=plugin.node(plan.segment.to());
        if(cells.isEmpty() || !from.world().equals(to.world()) || from.x()!=cells.getFirst().x() || from.y()!=cells.getFirst().y() || from.z()!=cells.getFirst().z() || to.x()!=cells.getLast().x() || to.y()!=cells.getLast().y() || to.z()!=cells.getLast().z())throw new IllegalArgumentException("Zmieniono końce odcinka. Powtórz pomiar.");
    }
    private void failedConfirmation(Pending plan,String reason) {
        for(var r:requests.values())if(r.quoted==plan){r.state="FAILED";r.reason=reason;r.token="";r.quoted=null;saveRequests();}
        plugin.getLogger().warning("Budowa "+plan.id+": "+reason);
    }
    private void markRequest(ConstructionJournal.Intent intent,String state) {
        for(var r:requests.values())if(r.job.equals(intent.id())){r.state=state;r.reason=intent.reason();r.token="";r.quoted=null;saveRequests();}
    }
    /** Trusted server callers only. Future means paid/durable/authorized, never physical completion. */
    CompletableFuture<String> submitPrepared(String id,UUID actor,RailwayWorks.PreparedPlan prepared,long maxCost,ConstructionJournal.GraphIntent graph) {
        return submitFundedPrepared(id,actor,prepared,maxCost,graph,pl.medivalrpg.kolej.data.RailData.ACCOUNT);
    }
    CompletableFuture<String> submitFundedPrepared(String id,UUID actor,RailwayWorks.PreparedPlan prepared,long maxCost,ConstructionJournal.GraphIntent graph,String debitAccount) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        if(closed || actor==null || !plugin.railwayManager(actor) || !pl.medivalrpg.kolej.data.RailData.identifier(id) || maxCost<=0 || !Objects.equals(prepared.country(),plugin.data().ownerCountry) || !prepared.preview().allowed())throw new IllegalArgumentException("Nieprawidłowe zlecenie budowy.");
        World world=Bukkit.getWorld(prepared.world());if(world==null)throw new IllegalArgumentException("Brak świata budowy.");
        if(graph.node()!=null && (!graph.node().id().equals(id) || !graph.node().world().equals(world.getName())))throw new IllegalArgumentException("Węzeł grafu nie pasuje do zlecenia.");
        if((graph.from()==null)!=(graph.to()==null) || graph.node()!=null && graph.from()!=null)throw new IllegalArgumentException("Nieprawidłowy zamiar grafu.");
        if(graph.from()!=null){TrackNode from=plugin.node(graph.from()),to=plugin.node(graph.to());
            if(graph.cells().isEmpty() || !from.world().equals(world.getName()) || !to.world().equals(world.getName()))throw new IllegalArgumentException("Nieprawidłowe końce odcinka.");
            var first=graph.cells().getFirst();var last=graph.cells().getLast();
            if(from.x()!=first.x() || from.y()!=first.y() || from.z()!=first.z() || to.x()!=last.x() || to.y()!=last.y() || to.z()!=last.z())throw new IllegalArgumentException("Zmieniono końce odcinka.");
        }
        long fee=graph.node()==null?0:plugin.getConfig().getLong(graph.node().isDepot()?"koszty.baza":"koszty.stacja",3000);
        long price=Math.addExact(fee,Math.multiplyExact(prepared.preview().cost(),Math.max(0,plugin.getConfig().getLong("koszty.blok-budowy",1))));
        if(price<=0 || price>maxCost)throw new IllegalArgumentException("Cena przekracza zatwierdzony limit lub jest nieprawidłowa.");
        bankBarrier();checkFree(id);
        var result=new CompletableFuture<String>();acceptance.put(id,result);
        try{committing.add(new ConstructionTransaction(new ConstructionJournal.Intent(id,actor,price,prepared,graph,"INTENT","",debitAccount),transactionPorts()));}
        catch(RuntimeException ex){acceptance.remove(id);result.completeExceptionally(ex);throw ex;}
        return result;
    }
    Optional<ConstructionJournal.Intent> constructionIntent(String id){return transactions.intents().stream().filter(intent->intent.id().equals(id)).findFirst();}
    private ConstructionTransaction.Ports transactionPorts() {
        return new ConstructionTransaction.Ports(){
            public Future<?> checkpoint(ConstructionJournal.Intent intent){return transactions.checkpoint(intent);}
            public Future<?> unpaidPause(ConstructionJournal.Intent intent){return transactions.checkpointUnpaidPause(intent);}
            public Future<?> stage(ConstructionJournal.Intent intent){return works.stage(intent.id(),intent.plan(),intent.graph().cells());}
            public TxResult debit(ConstructionJournal.Intent intent){
                var held=works.job(intent.id()).orElseThrow(()->new IllegalStateException("Brak trwale zleconego projektu."));
                if(!held.status().equals("HELD") || !RailwayWorks.matchesIntent(held,intent.plan()) || !plugin.railwayManager(intent.actor()) || !Objects.equals(plugin.data().ownerCountry,intent.plan().country()))throw new IllegalStateException("Zlecenie anulowano lub zmieniono zarządcę/właściciela przed płatnością.");
                return plugin.economy().withdraw(intent.debitAccount(),intent.price(),"Budowa kolei tx:"+intent.id());
            }
            public Future<?> paymentDurable(ConstructionJournal.Intent intent){return RailwayProjects.this.paymentDurable();}
            public Future<?> graph(ConstructionJournal.Intent intent){
                if(!Objects.equals(plugin.data().ownerCountry,intent.plan().country()))throw new IllegalStateException("Zmienił się właściciel opłaconego zlecenia.");
                var graph=intent.graph();
                if(graph.node()!=null){var old=plugin.graph().node(intent.id());if(old!=null && !old.equals(graph.node()))throw new IllegalArgumentException("Kolizja węzła grafu.");plugin.graph().putNode(graph.node());}
                if(graph.depot()!=null)plugin.data().depots.put(intent.id(),graph.depot());
                if(graph.from()!=null){var old=plugin.graph().segment(intent.id());if(old!=null && (!old.from().equals(graph.from()) || !old.to().equals(graph.to()) || !old.cells().equals(graph.cells())))throw new IllegalArgumentException("Kolizja odcinka grafu.");
                    if(old==null){old=new TrackSegment(intent.id(),graph.from(),graph.to());old.cells(graph.cells());plugin.graph().putSegment(old);}old.status(SegmentStatus.BUILDING);}
                return plugin.saveDurable();
            }
            public Future<?> activate(ConstructionJournal.Intent intent){return works.activatePaid(intent.id());}
            public void execute(ConstructionJournal.Intent intent){markRequest(intent,"SUBMITTED");works.allowPaidExecution(intent.id());var result=acceptance.remove(intent.id());if(result!=null)result.complete(intent.id());var p=Bukkit.getPlayer(intent.actor());if(p!=null)try{works.clearPreview(p);plugin.message(p,"Trwała płatność i zlecenie "+intent.id()+" potwierdzone. Robotnicy mogą rozpocząć prace.");}catch(RuntimeException ex){plugin.getLogger().warning("Nie można pokazać potwierdzenia budowy "+intent.id());}}
            public void stopped(ConstructionJournal.Intent intent){works.holdFinancial(intent.id(),intent.reason());markRequest(intent,"RECONCILE");var result=acceptance.remove(intent.id());if(result!=null)result.completeExceptionally(new IllegalStateException(intent.reason()));plugin.getLogger().severe("Budowa "+intent.id()+": "+intent.reason());}
        };
    }
    /** Style for new track projects; unknown values are rejected rather than silently changing the plan. */
    TrackStyle configuredStyle(){return TrackStyle.parse(plugin.getConfig().getString("budowa.styl-toru","realistyczny"));}
    private void survey(Player p,TrackNode from,TrackNode to,boolean roadOnly,List<int[]> via,TrackStyle style) {
        if(!surveying.add(p.getUniqueId()))throw new IllegalArgumentException("Trwa już badanie terenu.");
        int x0=Math.min(from.x(),to.x()),z0=Math.min(from.z(),to.z()),x1=Math.max(from.x(),to.x()),z1=Math.max(from.z(),to.z());
        for(int[] v:via){x0=Math.min(x0,v[0]);z0=Math.min(z0,v[1]);x1=Math.max(x1,v[0]);z1=Math.max(z1,v[1]);}
        int padding=20,minX=x0-padding,minZ=z0-padding;
        int width=x1-x0+2*padding+1,depth=z1-z0+2*padding+1;
        long area=(long)width*depth;double requestedLength=0;int previousX=from.x(),previousZ=from.z();
        for(int[] v:via){requestedLength+=Math.hypot((double)v[0]-previousX,(double)v[1]-previousZ);previousX=v[0];previousZ=v[1];}
        requestedLength+=Math.hypot((double)to.x()-previousX,(double)to.z()-previousZ);
        if(requestedLength>plugin.getConfig().getInt("budowa.max-dlugosc",1024) || area<=0 || area>plugin.getConfig().getInt("budowa.max-badanych-kolumn",180000)){surveying.remove(p.getUniqueId());throw new IllegalArgumentException("Odcinek zbyt duży. Dodaj stację pośrednią.");}
        int columns=(int)area;
        World w=p.getWorld();UUID worldId=w.getUID();String worldName=w.getName();int minHeight=w.getMinHeight();
        List<StationRegion> regions=stationRegions(worldName);
        Map<Long,ChunkSnapshot> chunks=new HashMap<>();List<CompletableFuture<Chunk>> load=new ArrayList<>();
        for(int cx=minX>>4;cx<=(minX+width-1)>>4;cx++)for(int cz=minZ>>4;cz<=(minZ+depth-1)>>4;cz++)load.add(w.getChunkAtAsync(cx,cz,false));
        plugin.message(p,"Badanie "+columns+" kolumn; używam wyłącznie już wygenerowanych chunków."+(roadOnly?"":" Styl toru: "+style.polish()+"."));
        CompletableFuture.allOf(load.toArray(CompletableFuture[]::new)).whenComplete((unused,error)->{
            if(closed || !plugin.isEnabled())return;
            Bukkit.getScheduler().runTask(plugin,()->{
                if(error!=null || load.stream().anyMatch(f->f.join()==null)){surveying.remove(p.getUniqueId());failPanelSurvey(p.getUniqueId(),"Brak wygenerowanego terenu lub błąd ładowania.");plugin.message(p,"Brak wygenerowanego terenu lub błąd ładowania.");return;}
                new BukkitRunnable(){int captured;public void run(){if(closed){cancel();return;}long deadline=System.nanoTime()+2_000_000L;for(int count=0;count<2 && captured<load.size() && System.nanoTime()<deadline;count++,captured++){Chunk c=load.get(captured).join();if(!c.isLoaded()){cancel();surveying.remove(p.getUniqueId());failPanelSurvey(p.getUniqueId(),"Chunk wyładowano podczas pomiaru. Załaduj teren i powtórz pomiar.");return;}chunks.put(((long)c.getX()<<32)|(c.getZ()&0xffffffffL),c.getChunkSnapshot());}if(captured<load.size())return;cancel();
                int[] heights=new int[columns],water=new int[columns];boolean[] blocked=new boolean[columns];Arrays.fill(water,TerrainGrid.NO_WATER);
                new BukkitRunnable(){int cursor;
                    public void run(){if(closed){cancel();return;}
                        long deadline=System.nanoTime()+2_000_000L;for(int count=0;count<32 && cursor<columns && System.nanoTime()<deadline;count++,cursor++) {
                            int x=minX+cursor%width,z=minZ+cursor/width;
                            ChunkSnapshot snap=chunks.get(((long)(x>>4)<<32)|((z>>4)&0xffffffffL));
                            int y=snap.getHighestBlockYAt(x&15,z&15);
                            while(y>minHeight) {Material type=snap.getBlockType(x&15,y,z&15);if(type==Material.WATER){water[cursor]=Math.max(water[cursor],y);y--;continue;}if(type.isSolid() && !type.name().endsWith("_LOG") && !type.name().endsWith("_LEAVES"))break;y--;}
                            heights[cursor]=y;blocked[cursor]=!plugin.allowed(new Location(w,x,y+1,z));
                        }
                        if(cursor==columns){cancel();TerrainGrid terrain=new TerrainGrid(){private int i(int x,int z){return(z-minZ)*width+x-minX;}public boolean known(int x,int z){return x>=minX&&z>=minZ&&x<minX+width&&z<minZ+depth;}public int ground(int x,int z){return known(x,z)?heights[i(x,z)]:minHeight;}public int water(int x,int z){return known(x,z)?water[i(x,z)]:NO_WATER;}public boolean blocked(int x,int z){return !known(x,z)||blocked[i(x,z)];}};
                            planner.execute(()->{try {
                                int[] da=RoutePlanner.leadDirection(from.axis(),from.x(),from.z(),to.x(),to.z()),db=RoutePlanner.leadDirection(to.axis(),to.x(),to.z(),from.x(),from.z());
                                var params=style==TrackStyle.REALISTIC?RoutePlanner.Params.realistic():RoutePlanner.Params.defaults();var result=new RoutePlanner(terrain,params).survey(new RoutePlanner.End(from.x(),from.y(),from.z(),da[0],da[1],10),new RoutePlanner.End(to.x(),to.y(),to.z(),db[0],db[1],10),via);
                                List<BlockOp> ops=result.ok()?style.segment(result.cells(),terrain::ground,terrain::ground,terrain::water,CellWorks.Settings.defaults()):List.of();
                                if(roadOnly)ops=ops.stream().filter(op->op.part()!=BlockOp.Part.RAIL && !(op.part()==BlockOp.Part.RAW && op.data()!=null && op.data().contains("rail["))).toList();
                                List<RailReuse.Check> reused=new ArrayList<>();
                                for(int i=0;i<result.cells().size();i++) {
                                    var cell=result.cells().get(i);var candidates=regions.stream().filter(region->region.containsColumn(cell.x(),cell.z())).toList();
                                    if(candidates.isEmpty())continue;
                                    if(candidates.stream().noneMatch(region->region.accepts(cell)))throw new IllegalArgumentException("Przebieg mija wysokość lub oś istniejącej stacji przy "+cell.x()+","+cell.y()+","+cell.z());
                                    reused.add(RailReuse.expected(result.cells(),i));
                                }
                                List<BlockOp> finalOps=ops.stream().filter(op->regions.stream().noneMatch(region->region.contains(op))).toList();
                                if(!closed)Bukkit.getScheduler().runTask(plugin,()->{surveying.remove(p.getUniqueId());if(!p.isOnline()){failPanelSurvey(p.getUniqueId(),"Zarządca opuścił grę. Powtórz wycenę.");return;}if(!p.getWorld().equals(w)){failPanelSurvey(p.getUniqueId(),"Zarządca zmienił świat. Powtórz wycenę.");plugin.message(p,"Wróć do świata projektu i powtórz pomiar.");return;}try {if(!result.ok())throw new IllegalArgumentException(String.join("; ",result.problems())+(style==TrackStyle.REALISTIC?" Styl realistyczny ma łagodniejsze łuki i pochylenia; możesz dodać punkt pośredni albo użyć /kolej tor planuj <od> <do> klasyczny.":""));String id=nextId(roadOnly?"droga":"tor");TrackSegment segment=roadOnly?null:new TrackSegment(id,from.id(),to.id());if(segment!=null)segment.cells(result.cells());preview(p,new Pending(id,worldId,finalOps,null,segment,null,expiry(),List.copyOf(reused)));}catch(IllegalArgumentException ex){failPanelSurvey(p.getUniqueId(),ex.getMessage());plugin.message(p,ex.getMessage());}});
                            }catch(Exception ex){if(!closed)Bukkit.getScheduler().runTask(plugin,()->{surveying.remove(p.getUniqueId());failPanelSurvey(p.getUniqueId(),ex.getMessage());plugin.message(p,"Projektant: "+ex.getMessage());});}});
                        }
                    }
                }.runTaskTimer(plugin,1,1);
                }}.runTaskTimer(plugin,1,1);
            });
        });
    }
    record StationRegion(TrackNode node,pl.medivalrpg.kolej.data.RailData.Depot depot) {
        boolean containsColumn(int x,int z) {
            if(node.isDepot()){if(depot==null)return false;int[] local=PlanRotation.point(x-depot.x(),z-depot.z(),-depot.rotation());return Math.abs(local[0])<=38 && Math.abs(local[1])<=30;}
            int side=node.axis()==0?x-node.x():z-node.z(),along=node.axis()==0?z-node.z():x-node.x();
            return node.isStation() && Math.abs(side)<=6 && Math.abs(along)<=64;
        }
        boolean accepts(TrackCell cell){return cell.y()==node.y() && (node.isDepot() || (node.axis()==0?cell.x()==node.x():cell.z()==node.z()));}
        boolean contains(BlockOp op){return containsColumn(op.x(),op.z()) && op.y()>=node.y()-3 && op.y()<=node.y()+5;}
    }
    private List<StationRegion> stationRegions(String world) {
        List<StationRegion> regions=new ArrayList<>();
        for(var node:plugin.graph().nodes())if(node.world().equals(world) && node.isWaitingPoint() && ready(node.id()))regions.add(new StationRegion(node,plugin.data().depots.get(node.id())));
        return List.copyOf(regions);
    }
    public void close(){closed=true;for(var future:acceptance.values())future.completeExceptionally(new IllegalStateException("Serwer wyłączono; sprawdź trwały stan transakcji."));acceptance.clear();planner.shutdownNow();works.close();transactions.close();requestIo.shutdown();try{if(!requestIo.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning("Zapis wniosków nadal trwa");}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
}
