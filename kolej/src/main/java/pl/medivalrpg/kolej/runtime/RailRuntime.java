// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import pl.medivalrpg.kolej.KolejPlugin;
import pl.medivalrpg.kolej.AuditMutationWatch;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.geom.Vec3;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.signal.Interlocking;
import pl.medivalrpg.kolej.signal.PhysicalRailGuard;
import pl.medivalrpg.kolej.signal.SemaphorePolicy;
import pl.medivalrpg.kolej.steam.*;
import pl.medivalrpg.kolej.visual.TrainModels;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;

/** Server-thread simulation. Travel and boiler state continue independently of visual distance. */
public final class RailRuntime implements Listener,AutoCloseable {
    private final KolejPlugin plugin;
    private final RailData data;
    private final TrackGraph graph;
    private final LocomotiveSpec spec=LocomotiveSpec.defaults();
    private final Interlocking signals;
    private final Map<String,Route> routes=new HashMap<>();
    private final Map<UUID,ArmorStand> seats=new HashMap<>();
    private final Map<UUID,Location> boardingPoints=new HashMap<>();
    private final Map<String,Mannequin> drivers=new HashMap<>(),stokers=new HashMap<>();
    private final Map<String,String> yardLocks=new HashMap<>();
    private final Map<String,BoilerCare> boilerCare=new HashMap<>();
    private final Set<String> invalidRestores=new HashSet<>(), signalTrains=new HashSet<>();
    private List<Occupancy> physical=List.of();
    private Set<String> opposing=Set.of();
    private final Set<String> switchLoads=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final NamespacedKey tag;
    private TrainModels models;
    private long tick;
    private boolean frameComplete;
    private final Map<Route,PhysicalRailGuard.Index> railIndexes=new WeakHashMap<>();
    private final Map<String,VerifiedGrant> verifiedGrants=new HashMap<>();
    private record VerifiedGrant(Route route,double limit,double reservationLimit,long tick) {}

    public PhysicalRailGuard.Result physicalTrack(Route route,double center,double ahead) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Kontrola toru wymaga głównego wątku");
        try {
            var index=railIndexes.get(route);
            if(index==null){index=PhysicalRailGuard.index(graph,route);railIndexes.put(route,index);}
            TrackNode first=graph.node(route.firstNode());
            World world=first==null?null:Bukkit.getWorld(first.world());
            return index.inspect(graph,center,ahead,new BukkitRailProbe(world,plugin::allowed));
        }catch(RuntimeException e){return new PhysicalRailGuard.Result(Math.max(0,Double.isFinite(center)?center:0),"Nieprawidłowy zapis toru",0);}
    }
    public SemaphorePolicy.Indication semaphore(String node,String segment) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Semafor wymaga głównego wątku");
        if(!frameComplete)return SemaphorePolicy.Indication.stop("Nieukończona klatka symulacji");
        var candidates=new ArrayList<SemaphorePolicy.Candidate>();
        for(Train t:data.trains.values()) {
            VerifiedGrant grant=verifiedGrants.get(t.id);Route route=routes.get(t.id);
            boolean active=grant!=null && grant.tick()==tick && grant.route()==route && t.running && t.yardBase==null && !invalidRestores.contains(t.id) && !opposing.contains(t.id) && !serviceHeld(t) && !t.steam.failed && !t.steam.emergency && driverAvailable(t);
            if(active) {
                int i=route.nodes().indexOf(node);
                if(i<0 || i>=route.legs().size() || !route.legs().get(i).segmentId().equals(segment))continue;
                double needed=route.legs().get(i).end()-t.distance+PhysicalRailGuard.BUFFER;
                if(needed>PhysicalRailGuard.MAX_AHEAD)continue; // Never advertise a route beyond the bounded proof window.
                var proof=physicalTrack(route,t.distance,needed);
                candidates.add(new SemaphorePolicy.Candidate(t.id,route,t.distance,Math.min(grant.reservationLimit(),proof.limit()),true));
            }
        }
        return SemaphorePolicy.evaluate(node,segment,graph,signals,candidates);
    }

    public RailRuntime(KolejPlugin plugin,RailData data,TrackGraph graph) {
        this.plugin=plugin;this.data=data;this.graph=graph;tag=new NamespacedKey(plugin,"train-entity");
        signals=new Interlocking(graph::node,32,12,6);
        if(Bukkit.getPluginManager().isPluginEnabled("BetterModel"))models=new TrainModels();
        Bukkit.getWorlds().forEach(w->w.getEntities().stream().filter(this::ours).forEach(Entity::remove));
        for(Train t:data.trains.values()) {
            t.manualDriver=null;t.steam.speed=0;t.steam.throttle=0;t.steam.brake=1;t.yardSpeed=0;
            if(!t.running)t.steam.fire=false; // Idle fires do not survive a restart unattended.
            if(t.yardBase==null && (t.yardPose!=null || t.yardPhase!=null)){invalidRestores.add(t.id);t.running=false;t.status="Osierocony zapis manewrów — wymagana naprawa";continue;}
            // Completed/stopped trains still occupy the last route with their whole tender and cars.
            if(t.yardBase==null && (!t.routeSegments.isEmpty() || t.running))try {
                Route r=restoreRoute(graph,t);routes.put(t.id,r);
            }catch(RuntimeException e){invalidRestores.add(t.id);t.running=false;t.status="Zapis trasy wymaga naprawy — ruch zablokowany";}
        }
        rebuildYardLocks();seedOccupancy();
    }
    public boolean ours(Entity e){return e.getPersistentDataContainer().has(tag,PersistentDataType.STRING);}
    @EventHandler public void chunk(ChunkLoadEvent e){for(Entity en:e.getChunk().getEntities())if(ours(en) && !seats.containsValue(en) && !drivers.containsValue(en) && !stokers.containsValue(en))en.remove();}
    @EventHandler public void quit(PlayerQuitEvent e){leave(e.getPlayer());for(Train t:data.trains.values())if(e.getPlayer().getUniqueId().equals(t.manualDriver)){t.manualDriver=null;t.steam.throttle=0;t.steam.brake=1;}}
    @EventHandler public void dismount(EntityDismountEvent e){if(e.getEntity() instanceof Player p && seats.get(p.getUniqueId())==e.getDismounted()){
        Bukkit.getScheduler().runTask(plugin,()->{leave(p);for(Train t:data.trains.values())if(p.getUniqueId().equals(t.manualDriver)){t.manualDriver=null;t.steam.brake=1;t.steam.throttle=0;}});
    }}
    public Location location(Train t,double behind) {
        if(invalidRestores.contains(t.id) || (t.yardBase!=null && !RailYard.validPose(t.yardPose)))return null;
        if(t.yardBase!=null){var depot=data.depots.get(t.yardBase);if(depot==null || !RailYard.consistentPose(t,depot))return null;}
        Route r=routes.get(t.id);TrackNode node=graph.node(t.node);
        if(node==null)return null;
        World w=Bukkit.getWorld(node.world());if(w==null)return null;
        Vec3 point,dir;
        if(t.yardPose!=null){double a=Math.toRadians(t.yardPose[3]);dir=new Vec3(-Math.sin(a),0,Math.cos(a));point=new Vec3(t.yardPose[0],t.yardPose[1],t.yardPose[2]).sub(dir.scale(behind));}
        else if(r!=null){point=r.path().pointAt(t.distance-behind);dir=r.path().directionAt(t.distance-behind,1);}
        else {var depot=data.depots.get(node.id());if(depot!=null){double a=Math.toRadians(depot.rotation()*90);dir=new Vec3(-Math.sin(a),0,Math.cos(a));}else dir=node.axis()==1?new Vec3(1,0,0):new Vec3(0,0,1);point=node.point().sub(dir.scale(behind));}
        return new Location(w,point.x(),point.y(),point.z(),(float)Math.toDegrees(Math.atan2(-dir.x(),dir.z())),0);
    }
    public boolean driverAvailable(Train t) {return t.manualDriver!=null && Bukkit.getPlayer(t.manualDriver)!=null || crewReady(data.crews.values(),t.id,plugin.getConfig().getBoolean("zaloga.palacz-wymagany",false));}
    /** NPC operation needs a paid engine driver and, when configured, a paid fireman on the same locomotive. */
    public static boolean crewReady(Collection<Crew> crews,String train,boolean stokerRequired) {
        boolean driver=false,stoker=false;
        for(Crew c:crews)if(c.paid && train.equals(c.train)){if(c.driver())driver=true;else if(c.stoker())stoker=true;}
        return driver && (stoker || !stokerRequired);
    }
    /** Daily wage per role; the fireman has his own rate (default 60 md). */
    public static long wage(org.bukkit.configuration.Configuration config,Crew c) {
        return c.stoker()?config.getLong("firma.dzienny-zold-palacza",60):config.getLong("firma.dzienny-zold",100);
    }
    public void depart(Train t,String destination) {
        if(plugin.infrastructure()!=null && plugin.infrastructure().holds(t))throw new IllegalArgumentException("Trwa zapis tankowania lub kontrola niedokończonej transakcji infrastruktury.");
        if(invalidRestores.contains(t.id))throw new IllegalArgumentException("Najpierw napraw zapis pozycji/trasy pociągu.");
        if(t.yardBase!=null)throw new IllegalArgumentException("Najpierw wyprowadź parowóz ze stanowiska: /kolej baza wyprowadz <pociag>.");
        if(t.running)throw new IllegalArgumentException("Pociąg już jedzie.");
        if(!driverAvailable(t))throw new IllegalArgumentException("Przydziel opłaconego maszynistę NPC albo obejmij ster.");
        if(plugin.getConfig().getBoolean("modele.wymagaj-bettermodel",true) && (models==null || !models.ready("kolej_parowoz") || t.cars.stream().anyMatch(c->!models.ready(c.model))))throw new IllegalArgumentException("Modele kolei nie są gotowe. Wczytaj je w BetterModel i odśwież paczkę zasobów.");
        Route route=graph.route(t.node,destination,TrackSegment::isBuilt).orElseThrow(()->new IllegalArgumentException("Brak gotowego połączenia."));
        if(route.nodes().stream().anyMatch(n->yardLocks.containsKey(n) && !t.id.equals(yardLocks.get(n))))throw new IllegalArgumentException("Podejście bazy zarezerwowane na manewry.");
        if(data.trains.values().stream().anyMatch(other->other!=t && other.yardBase==null && !other.running && Objects.equals(other.node,t.node)))throw new IllegalArgumentException("Tor stacyjny zajęty.");
        if(!signals.holders(t.node).isEmpty() && signals.holders(t.node).stream().anyMatch(id->!id.equals(t.id)))throw new IllegalArgumentException("Tor stacyjny zajęty.");
        if(!prepareDeparture(t))throw new IllegalArgumentException("Odjazd wstrzymany: uzupełnij zapasy na stacji i środki firmy (ponowienie za minutę lub serwis ręczny).");
        routes.put(t.id,route);t.routeNodes=new ArrayList<>(route.nodes());t.routeSegments=new ArrayList<>(route.segmentIds());
        t.distance=0;t.running=true;t.nextStop=1;t.steam.fire=true;t.status="Rozpalanie kotła";
    }
    private void wages() {
        String day=LocalDate.now(plugin.zone()).toString();
        if(day.equals(data.wagesDay))return;
        data.wagesDay=day;
        for(Crew c:data.crews.values())c.paid=plugin.economy().withdraw(RailData.ACCOUNT,wage(plugin.getConfig(),c),"Dzienny żołd ("+c.roleName()+") "+c.name)==TxResult.OK;
        plugin.save();
    }
    public void tick() {
        frameComplete=false;verifiedGrants.clear();
        tick++;rebuildYardLocks();seedOccupancy();
        if(tick%20==1){wages();dispatch();}
        for(Train t:data.trains.values()) {
            if(invalidRestores.contains(t.id)){halt(t);t.steam.fire=false;continue;}
            if(serviceHeld(t)){halt(t);t.steam.fire=false;t.status="Zapis tankowania / kontrola transakcji";visuals(t);continue;}
            if(t.yardBase!=null){halt(t);SteamModel.step(spec,t.steam,.1,0,t.mass());visuals(t);continue;}
            if(tick%10==1)controlIdleFire(t);
            if(serviceHeld(t)){halt(t);t.steam.fire=false;visuals(t);continue;}
            Route r=routes.get(t.id);
            if(!t.running || r==null) {
                halt(t);SteamModel.step(spec,t.steam,.1,0,t.mass());
            }else if(opposing.contains(t.id) || wouldForceOpposing(signals,t.id,r,t.distance,t.length(),graph)){
                halt(t);t.steam.emergency=true;t.status="Konflikt zajętości odcinka — ruch zablokowany";
            }else if(System.currentTimeMillis()<t.dwellUntil){halt(t);SteamModel.step(spec,t.steam,.1,0,t.mass());t.status="Postój rozkładowy";}
            else if(atServicePoint(t,r,graph) && BoilerCare.needsFuel(spec,t.steam) && !prepareDeparture(t)){
                halt(t);t.steam.fire=false;t.status="Oczekiwanie na zapasy i obsługę stacyjną";if(!serviceHeld(t))SteamModel.step(spec,t.steam,.1,0,t.mass());
            }
            else {
                double gradient=r.path().gradientAt(t.distance,2);
                double decel=Math.max(.05,spec.serviceBrake()+Math.min(0,gradient)*SteamModel.G);
                var grant=signals.update(t.id,r,t.distance+4,t.distance-t.length(),Math.max(40,SteamModel.brakingDistance(t.steam.speed,decel)+t.length()+10));
                double authority=grant.reason()==Interlocking.Limit.END_OF_ROUTE?r.length():Math.max(t.distance,grant.limit()-4);
                authority=Math.min(authority,physicalLimit(t.id,r,t.distance,t.length(),physical,graph));
                authority=Math.min(authority,prepareSwitches(t,r));
                double stop=r.length();
                for(int i=t.nextStop;i<r.nodes().size();i++)if(graph.node(r.nodes().get(i)).isWaitingPoint()){stop=r.nodeOffset(i);break;}
                // Re-check land consent while travelling, including war or revoked permissions.
                for(double ahead=0;ahead<=Math.min(256,Math.max(20,SteamModel.brakingDistance(t.steam.speed,decel)+14));ahead+=2) {
                    Vec3 probe=r.path().pointAt(Math.min(r.length(),t.distance+ahead));
                    Location loc=new Location(Bukkit.getWorld(graph.node(t.node).world()),probe.x(),probe.y(),probe.z());
                    if(!plugin.allowed(loc)){authority=Math.min(authority,t.distance+Math.max(0,ahead-6));break;}
                }
                double reservationLimit=Math.min(stop,authority);
                var physicalTrack=physicalTrack(r,t.distance,Math.max(40,SteamModel.brakingDistance(t.steam.speed,decel)+t.length()+12));
                authority=Math.min(authority,physicalTrack.limit());
                double remaining=Math.max(0,Math.min(stop,authority)-t.distance);
                verifiedGrants.put(t.id,new VerifiedGrant(r,Math.min(stop,authority),reservationLimit,tick));
                double routeSpeed=RouteSpeed.allowed(graph,r,t.distance,t.length(),Math.min(12,spec.maxSpeed()),decel);
                if(driverAvailable(t)) {
                    if(t.manualDriver==null){t.steam.fire=true;AutoDriver.drive(spec,t.steam,routeSpeed,remaining);}
                    else AutoDriver.supervise(spec,t.steam,remaining);
                }else {t.steam.throttle=0;t.steam.brake=1;}
                AutoDriver.enforceSpeed(t.steam,routeSpeed);
                superviseSafety(t.steam,remaining,decel);
                double moved=SteamModel.step(spec,t.steam,.1,gradient,t.mass());
                t.distance=advance(t.distance,moved,Math.min(stop,authority),t.steam);
                t.status=t.steam.emergency?"Hamowanie bezpieczeństwa":!driverAvailable(t)?"Brak maszynisty / żołdu":t.steam.failed?"Awaria kotła":!t.steam.ready(spec)?"Rozpalanie kotła":remaining<3?"Czerwony sygnał":"W trasie";
                if(physicalTrack.reason()!=null && physicalTrack.limit()<=t.distance+8)t.status=physicalTrack.reason();
                if(canArrive(t.distance,stop,t.steam.speed,authority)) {
                    int i=r.indexOf(r.nodes().get(Math.min(t.nextStop,r.nodes().size()-1)),t.nextStop);
                    while(i<r.nodes().size()-1 && !graph.node(r.nodes().get(i)).isWaitingPoint())i++;
                    t.node=r.nodes().get(i);t.nextStop=i+1;t.steam.speed=0;t.steam.brake=1;t.distance=stop;
                    t.dwellUntil=System.currentTimeMillis()+40000;
                    service(t,true);t.status="Postój: "+graph.node(t.node).name();
                    if(t.nextStop>=r.nodes().size()){t.running=false;t.manualDriver=null;}
                }
            }
            visuals(t);
            crewVisuals(t);
        }
        if(tick%300==0)plugin.save();
        frameComplete=true;
    }
    private void dispatch() {
        LocalDateTime now=LocalDateTime.now(plugin.zone());long minute=System.currentTimeMillis()/60000;
        for(Line l:data.lines.values())if(l.active && l.stops.size()>=2 && !l.timetable.minutesOfDay().isEmpty()) {
            var candidate=data.trains.values().stream().filter(t->!invalidRestores.contains(t.id) && graph.node(t.node)!=null && t.yardBase==null && l.id.equals(t.line) && !t.running && driverAvailable(t) && System.currentTimeMillis()>=t.dwellUntil).findFirst();
            if(candidate.isEmpty())continue;
            Train t=candidate.get();
            if(t.node.equals(l.stops.getLast())){
                long plannedReturn=plannedReturnMillis(l);
                if(System.currentTimeMillis()<plannedReturn){t.status="Postój do odjazdu powrotnego "+LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(plannedReturn),plugin.zone()).toLocalTime().withSecond(0).withNano(0);continue;}
                try{depart(t,l.stops.getFirst());}catch(IllegalArgumentException e){t.status=e.getMessage();}continue;}
            if(!t.node.equals(l.stops.getFirst()))continue;
            int m=now.getHour()*60+now.getMinute();
            boolean due=l.timetable.minutesOfDay().contains(m) && l.lastDepartureMinute!=minute;
            if(warmupDue(t,l,now,System.currentTimeMillis()))prepareDeparture(t);
            if(due)try {
                List<String> ns=new ArrayList<>(),ss=new ArrayList<>();
                Route route=graph.routeThrough(l.stops,TrackSegment::isBuilt,true).orElseThrow(()->new IllegalArgumentException("Brak przejezdnej linii."));
                depart(t,l.stops.get(1));routes.put(t.id,route);t.routeNodes=new ArrayList<>(route.nodes());t.routeSegments=new ArrayList<>(route.segmentIds());l.lastDepartureMinute=minute;
            }catch(IllegalArgumentException e){t.status=e.getMessage();}
        }
    }
    /** A train may only pass a physical switch after obtaining its exclusive interlocking reservation. */
    private double prepareSwitches(Train train,Route route) {
        double limit=route.length();
        for(int i=1;i<route.nodes().size()-1;i++) {
            TrackNode node=graph.node(route.nodes().get(i));double at=route.nodeOffset(i);
            if(node==null || node.type()!=NodeType.JUNCTION || at<train.distance-train.length()-6)continue;
            if(at>train.distance+128)continue;
            Set<String> holders=signals.holders(node.id());
            boolean ready=holders.size()==1 && holders.contains(train.id);
            var shape=JunctionSwitch.routeShape(graph,route,i);
            World world=Bukkit.getWorld(node.world());
            ready=ready && shape.isPresent() && world!=null;
            if(ready && !world.isChunkLoaded(node.x()>>4,node.z()>>4)) {
                ready=false;
                String key=world.getUID()+":"+(node.x()>>4)+":"+(node.z()>>4);
                if(tick%20==0 && switchLoads.size()<8 && switchLoads.add(key))
                    world.getChunkAtAsync(node.x()>>4,node.z()>>4,false).whenComplete((chunk,error)->switchLoads.remove(key));
            }
            if(ready) {
                var block=world.getBlockAt(node.x(),node.y(),node.z());
                if(!plugin.allowed(block.getLocation()) || !(block.getBlockData() instanceof org.bukkit.block.data.Rail rail))ready=false;
                else {
                    var target=org.bukkit.block.data.Rail.Shape.valueOf(shape.orElseThrow());
                    if(!rail.getShapes().contains(target))ready=false;
                    else if(rail.getShape()!=target){rail.setShape(target);block.setBlockData(rail,false);AuditMutationWatch.afterMutation(block);}
                }
            }
            if(!ready)limit=Math.min(limit,Math.max(train.distance,at-8));
        }
        return limit;
    }
    public void service(Train t) { service(t,false); }
    public boolean canService(Train t) {return !invalidRestores.contains(t.id) && atServicePoint(t,routes.get(t.id),graph);}
    private boolean serviceHeld(Train t){return plugin.infrastructure()!=null && plugin.infrastructure().holds(t);}
    private void service(Train t,boolean automatic) {
        if(!atServicePoint(t,routes.get(t.id),graph) || invalidRestores.contains(t.id))return;
        TrackNode n=graph.node(t.node);
        refuel(t,automatic);
        if(n.isDepot() && t.steam.damage>0 && plugin.economy().withdraw(RailData.ACCOUNT,plugin.getConfig().getLong("koszty.naprawa",200),"Naprawa "+t.id)==TxResult.OK){t.steam.damage=0;t.steam.failed=false;t.steam.boilerWater=spec.boilerWater()*.8;}
    }
    private boolean refuel(Train t,boolean automatic){
        if(!canService(t))return !BoilerCare.needsFuel(spec,t.steam);
        return plugin.infrastructure()!=null && plugin.infrastructure().refuel(t,automatic);
    }
    private boolean prepareDeparture(Train t){
        if(!refuel(t,true)){t.steam.fire=false;return false;}
        t.steam.fire=!t.steam.failed;return !t.steam.failed;
    }
    private void controlIdleFire(Train t){
        long now=System.currentTimeMillis();
        boolean available=driverAvailable(t),warm=available && warmupDue(t,data.lines.get(t.line),LocalDateTime.now(plugin.zone()),now);
        if(warm && !t.running)prepareDeparture(t);
        boolean working=t.running && available || t.manualDriver!=null && available;
        if(boilerCare.computeIfAbsent(t.id,id->new BoilerCare()).extinguish(now,working || warm) && t.steam.fire){
            t.steam.fire=false;plugin.save();
        }
    }
    /** Planned return departure from the terminus (epoch ms), or 0 for the legacy immediate return. */
    public static long plannedReturnMillis(Line line){
        if(line==null || line.returnAfterMinutes<=0 || line.lastDepartureMinute<0)return 0;
        return (line.lastDepartureMinute+line.returnAfterMinutes)*60_000L;
    }
    static boolean atServicePoint(Train t,Route route,TrackGraph graph){
        TrackNode n=graph.node(t.node);
        if(n==null || !n.isWaitingPoint() || t.yardBase!=null || !Double.isFinite(t.steam.speed) || Math.abs(t.steam.speed)>.1)return false;
        if(route==null)return !t.running;
        for(int i=0;i<route.nodes().size();i++)if(route.nodes().get(i).equals(t.node) && Math.abs(t.distance-route.nodeOffset(i))<.25)return true;
        return false;
    }
    static boolean warmupDue(Train t,Line line,LocalDateTime now,long wallTime){
        if(line==null || !line.active || line.stops.size()<2 || line.timetable.minutesOfDay().isEmpty() || t.yardBase!=null || t.running || t.steam.failed || !Objects.equals(line.id,t.line))return false;
        if(Objects.equals(t.node,line.stops.getLast()))return Math.max(t.dwellUntil,plannedReturnMillis(line))-wallTime<=BoilerCare.WARMUP_MS;
        if(!Objects.equals(t.node,line.stops.getFirst()))return false;
        if(line.timetable.minutesOfDay().contains(now.getHour()*60+now.getMinute()) && line.lastDepartureMinute!=wallTime/60000)return true;
        var next=line.timetable.next(now);
        return next.isPresent() && java.time.Duration.between(now,next.get()).toMillis()<=BoilerCare.WARMUP_MS;
    }
    /** Boiler thresholds and idle policy. All physical transfers are owned by InfrastructureService. */
    static final class BoilerCare {
        static final long WARMUP_MS=240_000,IDLE_MS=180_000;
        private long idleSince=Long.MIN_VALUE;
        static boolean needsFuel(LocomotiveSpec spec,SteamState s){return !Double.isFinite(s.coal) || !Double.isFinite(s.water) || s.coal<spec.coalCapacity()*.25 || s.water<spec.waterCapacity()*.25;}
        boolean extinguish(long now,boolean attendedOrScheduled){
            if(attendedOrScheduled){idleSince=Long.MIN_VALUE;return false;}
            if(idleSince==Long.MIN_VALUE)idleSince=now;
            return now-idleSince>=IDLE_MS;
        }
    }
    private void visuals(Train t) {
        Location front=location(t,0);if(front==null)return;
        boolean near=front.getWorld().getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(front)<128*128);
        if(!near){if(models!=null)models.remove(t.id+"/");return;}
        double visualSpeed=t.yardBase==null?t.steam.speed:t.yardSpeed;
        // Driving state → animations (idle/start/run/run_reverse/brake/whistle) and per-viewer steam/sparks/sounds.
        pl.medivalrpg.kolej.visual.LocomotiveAnimator.Frame frame;
        if(models!=null){
            frame=models.locomotive(t.id+"/engine",front,visualSpeed,t.steam.brake,t.steam.fire,t.yardBase!=null);
            Location tenderAt=location(t,8);if(tenderAt!=null)models.move(t.id+"/tender","kolej_tender",tenderAt,frame.speed());
            for(int i=0;i<t.cars.size();i++){Location carAt=location(t,16+i*8);if(carAt!=null)models.move(t.id+"/car"+i,t.cars.get(i).model,carAt,frame.speed());}
            // Lever stands repeat the real rail shape of junctions within 64 m of this train (refresh every 0.5 s).
            Route leverRoute=routes.get(t.id);
            if(leverRoute!=null && t.yardBase==null && tick%5==0 && plugin.getConfig().getBoolean("modele.dzwignie-zwrotnic",true))
                for(int i=1;i<leverRoute.nodes().size()-1;i++) {
                    TrackNode n=graph.node(leverRoute.nodes().get(i));double at=leverRoute.nodeOffset(i);
                    if(n==null || n.type()!=NodeType.JUNCTION || Math.abs(at-t.distance)>64 || !n.world().equals(front.getWorld().getName()) || !front.getWorld().isChunkLoaded(n.x()>>4,n.z()>>4))continue;
                    if(!(front.getWorld().getBlockAt(n.x(),n.y(),n.z()).getBlockData() instanceof org.bukkit.block.data.Rail rail))continue;
                    boolean reverse=Set.of("NORTH_EAST","NORTH_WEST","SOUTH_EAST","SOUTH_WEST").contains(rail.getShape().name());
                    Vec3 dir=leverRoute.path().directionAt(at,1);
                    double[] stand=pl.medivalrpg.kolej.visual.ServiceAnimation.lever(n.x()+.5,n.z()+.5,Math.toDegrees(Math.atan2(-dir.x(),dir.z())),3.2);
                    models.lever(n.id(),t.id,new Location(front.getWorld(),stand[0],n.y(),stand[1],(float)stand[2],0),reverse);
                }
        }else frame=pl.medivalrpg.kolej.visual.LocomotiveAnimator.stateless(visualSpeed,t.steam.fire);
        pl.medivalrpg.kolej.visual.RailEffects.locomotive(front,frame,t.steam.fire,pl.medivalrpg.kolej.visual.RailEffects.now());
        for(var e:seats.entrySet())if(e.getValue().getPersistentDataContainer().get(tag,PersistentDataType.STRING).startsWith(t.id+":")) {
            String[] seat=e.getValue().getPersistentDataContainer().get(tag,PersistentDataType.STRING).split(":");int index=Integer.parseInt(seat[1]);
            Location at=seatLocation(t,index);if(at!=null)e.getValue().teleport(at,TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        Crew c=t.manualDriver==null?data.crews.values().stream().filter(cr->t.id.equals(cr.train) && cr.driver()).findFirst().orElse(null):null;
        if(c==null){Mannequin old=drivers.remove(t.id);if(old!=null)old.remove();}
        if(c!=null){Mannequin d=drivers.get(t.id);if(d==null || !d.isValid()) {d=front.getWorld().spawn(front,Mannequin.class,en->{en.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile().name("Steve").build());en.setImmovable(true);en.setInvulnerable(true);en.setPersistent(false);en.customName(Component.text(c.name+" — maszynista"));en.getPersistentDataContainer().set(tag,PersistentDataType.STRING,t.id);});drivers.put(t.id,d);}d.teleport(location(t,3).add(0,1,0));}
    }
    /** Visible fireman on the footplate, next to the driver. Non-persistent, tagged, rebuilt like the driver. */
    private void crewVisuals(Train t) {
        Crew c=data.crews.values().stream().filter(cr->t.id.equals(cr.train) && cr.stoker()).findFirst().orElse(null);
        Location front=c==null?null:location(t,0);
        boolean near=front!=null && front.getWorld().getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(front)<128*128);
        if(!near){Mannequin old=stokers.remove(t.id);if(old!=null)old.remove();return;}
        Mannequin d=stokers.get(t.id);
        if(d==null || !d.isValid()){d=front.getWorld().spawn(front,Mannequin.class,en->{en.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile().name("Steve").build());en.setImmovable(true);en.setInvulnerable(true);en.setPersistent(false);en.customName(Component.text(c.name+" — palacz"));en.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SHOVEL));en.getPersistentDataContainer().set(tag,PersistentDataType.STRING,t.id);});stokers.put(t.id,d);}
        Location at=location(t,4);if(at!=null)d.teleport(at.add(0,1,0));
    }
    private Location seatLocation(Train t,int index) {
        if(index<0)return location(t,3).add(0,.55,0);
        int count=0;
        for(int car=0;car<t.cars.size();car++){int capacity=t.cars.get(car).seats;if(index<count+capacity){int i=index-count;Location at=location(t,16+car*8+(i/2-1.5)*1.15);Vec3 side=new Vec3(Math.cos(Math.toRadians(at.getYaw())),0,Math.sin(Math.toRadians(at.getYaw())));return at.add(side.x()*(i%2==0?-.65:.65),.55,side.z()*(i%2==0?-.65:.65));}count+=capacity;}
        return location(t,3).add(0,.55,0);
    }
    public void board(Player p,Train t) {
        Location origin=p.getLocation().clone();
        if(seats.containsKey(p.getUniqueId()))throw new IllegalArgumentException("Najpierw zejdź z pociągu.");
        Location loc=location(t,0);if(loc==null || !loc.getWorld().equals(p.getWorld()) || loc.distanceSquared(p.getLocation())>45*45 || t.steam.speed>.1)throw new IllegalArgumentException("Podejdź do stojącego składu (do 45 bloków).");
        int index=0;Set<Integer> taken=new HashSet<>();
        for(ArmorStand s:seats.values()){String value=s.getPersistentDataContainer().get(tag,PersistentDataType.STRING);if(value!=null && value.startsWith(t.id+":"))taken.add(Integer.parseInt(value.split(":")[1]));}
        while(taken.contains(index))index++;if(index>=t.seats())throw new IllegalArgumentException("Brak wolnych miejsc pasażerskich.");
        Line l=data.lines.get(t.line);long fare=l==null?0:l.fare;
        if(fare>0 && plugin.economy().transfer(SkarbiecApi.playerAccount(p.getUniqueId()),RailData.ACCOUNT,fare,"Bilet "+t.id)!=TxResult.OK)throw new IllegalArgumentException("Brak środków na bilet: "+plugin.economy().format(fare));
        Location at=seatLocation(t,index);String value=t.id+":"+index;
        ArmorStand s=at.getWorld().spawn(at,ArmorStand.class,en->{en.setVisible(false);en.setMarker(true);en.setGravity(false);en.setInvulnerable(true);en.setPersistent(false);en.getPersistentDataContainer().set(tag,PersistentDataType.STRING,value);});
        if(!s.addPassenger(p)){s.remove();if(fare>0)plugin.economy().transfer(RailData.ACCOUNT,SkarbiecApi.playerAccount(p.getUniqueId()),fare,"Zwrot biletu");throw new IllegalArgumentException("Nie udało się zająć miejsca.");}
        seats.put(p.getUniqueId(),s);boardingPoints.put(p.getUniqueId(),origin);
        // Visual only: the boarded car opens its doors (passenger car model; others have no "doors" animation).
        if(models!=null){int count=0;for(int car=0;car<t.cars.size();car++){count+=t.cars.get(car).seats;if(index<count){models.doors(t.id+"/car"+car);break;}}}
    }
    public void mountDriver(Player p,Train t) {
        if(t.yardBase!=null)throw new IllegalArgumentException("Trwają manewry lub parowóz stoi w parowozowni.");
        if(seats.containsKey(p.getUniqueId()))throw new IllegalArgumentException("Najpierw zejdź z obecnego siedzenia.");
        if(t.steam.speed>.1)throw new IllegalArgumentException("Obejmij ster w stojącym pociągu.");
        Location origin=p.getLocation().clone(),at=seatLocation(t,-1);
        ArmorStand s=at.getWorld().spawn(at,ArmorStand.class,en->{en.setVisible(false);en.setMarker(true);en.setGravity(false);en.setInvulnerable(true);en.setPersistent(false);en.getPersistentDataContainer().set(tag,PersistentDataType.STRING,t.id+":-1");});
        if(!s.addPassenger(p)){s.remove();throw new IllegalArgumentException("Nie udało się zająć kabiny.");}
        seats.put(p.getUniqueId(),s);boardingPoints.put(p.getUniqueId(),origin);
    }
    public boolean lockYard(String base,String train) {
        if(base==null || graph.node(base)==null)return false;
        if(yardLocks.containsKey(base))return yardLocks.get(base).equals(train);
        if(signals.holders(base).stream().anyMatch(id->!id.equals(train) && !id.equals("yard:"+base)))return false;
        if(data.trains.values().stream().anyMatch(t->!t.id.equals(train) && Objects.equals(base,t.node) && t.yardBase==null))return false;
        yardLocks.put(base,train);signals.update("yard:"+base,Route.stay(graph,base),0,0,0);return true;
    }
    public void unlockYard(String base){yardLocks.remove(base);signals.release("yard:"+base);}
    public void detachRoute(Train t){routes.remove(t.id);signals.release(t.id);t.routeNodes.clear();t.routeSegments.clear();t.distance=0;}
    private static void halt(Train t){t.steam.speed=0;t.steam.throttle=0;t.steam.brake=1;}

    // Package-visible pure safety functions are also used by the regression tests.
    record Occupancy(String id,Route route,double center,double length,String node) { }
    static Route restoreRoute(TrackGraph graph,Train t) {
        if(!Double.isFinite(t.distance) || t.distance<0)throw new IllegalArgumentException("Invalid distance");
        Route r=Route.of(graph,t.routeNodes,t.routeSegments);
        if(r.legs().isEmpty() || t.distance>r.length()+1e-6 || t.nextStop<1 || t.nextStop>r.nodes().size()
                || t.running && t.nextStop==r.nodes().size() || graph.node(t.node)==null || !r.nodes().contains(t.node))throw new IllegalArgumentException("Invalid route cursor");
        for(int i=0;i<r.legs().size();i++){
            var s=graph.segment(r.legs().get(i).segmentId());
            var a=graph.node(r.nodes().get(i));var b=graph.node(r.nodes().get(i+1));
            if(s==null || !s.isBuilt() || a==null || b==null || !a.world().equals(b.world()) || !s.touches(a.id()) || !s.other(a.id()).equals(b.id()))throw new IllegalArgumentException("Broken route");
        }
        return r;
    }
    static double advance(double current,double moved,double limit,SteamState steam) {
        double available=Math.max(0,limit-current);
        if(!Double.isFinite(moved) || !Double.isFinite(limit) || moved>available+1e-8 || available<=1e-8 && steam.speed>0){steam.speed=0;steam.throttle=0;steam.brake=1;steam.emergency=true;}
        if(!Double.isFinite(moved) || !Double.isFinite(limit))return current;
        return current+Math.min(available,Math.max(0,moved));
    }
    static boolean canArrive(double current,double stop,double speed,double authority){return stop>=current && stop-current<=2.35 && speed<.2 && authority+1e-8>=stop;}
    static void superviseSafety(SteamState s,double remaining,double decel){
        if(remaining<=1 || s.speed>SteamModel.maxSpeedToStop(Math.max(0,remaining-2),decel)){
            s.throttle=0;s.brake=1;
            if(SteamModel.brakingDistance(s.speed,decel)>Math.max(0,remaining-1))s.emergency=true;
        }else if(s.emergency && s.speed<.05)s.emergency=false;
    }
    static Map<String,Boolean> committed(Route r,double center,double length,TrackGraph graph) {
        Map<String,Boolean> out=new HashMap<>();double front=center+4,rear=center-length;
        for(int i=0;i<r.legs().size();i++){
            var leg=r.legs().get(i);
            double entry=graph.node(r.nodes().get(i)).isWaitingPoint()?12:6;
            double exit=graph.node(r.nodes().get(i+1)).isWaitingPoint()?12:6;
            if(front>leg.start()+entry && rear<leg.end()-exit)out.put(leg.segmentId(),leg.forward());
        }
        int from=0;
        for(int i=0;i<r.nodes().size();i++)if(r.nodeOffset(i)<=front && (i==0 || i==r.nodes().size()-1 || graph.node(r.nodes().get(i)).isWaitingPoint()))from=i;
        double zone=graph.node(r.nodes().get(from)).isWaitingPoint()?12:6;
        if(front>r.nodeOffset(from)+zone)for(int i=from;i<r.legs().size();i++){
            var leg=r.legs().get(i);out.put(leg.segmentId(),leg.forward());
            if(graph.node(r.nodes().get(i+1)).isWaitingPoint())break;
        }
        return out;
    }
    static boolean wouldForceOpposing(Interlocking signals,String id,Route r,double center,double length,TrackGraph graph){
        for(var e:committed(r,center,length,graph).entrySet())for(var h:signals.segmentHolders(e.getKey()).entrySet())
            if(!h.getKey().equals(id) && h.getValue()!=e.getValue())return true;
        return false;
    }
    static Set<String> opposingOccupants(List<Occupancy> positions,TrackGraph graph){
        Set<String> conflicts=new HashSet<>();
        for(int i=0;i<positions.size();i++)for(int j=i+1;j<positions.size();j++){
            var a=positions.get(i);var b=positions.get(j);if(a.route==null || b.route==null)continue;
            var aa=committed(a.route,a.center,a.length,graph);var bb=committed(b.route,b.center,b.length,graph);
            if(aa.entrySet().stream().anyMatch(e->bb.containsKey(e.getKey()) && !bb.get(e.getKey()).equals(e.getValue()))){conflicts.add(a.id);conflicts.add(b.id);}
        }
        return conflicts;
    }
    static double physicalLimit(String id,Route route,double center,double length,List<Occupancy> positions,TrackGraph graph){
        double limit=route.length(),front=center+4;
        for(var other:positions)if(!other.id.equals(id)){
            for(int i=0;i<route.legs().size();i++){
                var leg=route.legs().get(i);
                if(other.route==null){
                    if(Objects.equals(route.nodes().get(i+1),other.node) && graph.node(other.node).capacity()==1)
                        limit=Math.min(limit,Math.max(center,leg.end()-other.length-5));
                    continue;
                }
                for(var occupied:other.route.legs())if(occupied.segmentId().equals(leg.segmentId())){
                    double lo=Math.max(occupied.start(),other.center-other.length),hi=Math.min(occupied.end(),other.center+4);
                    if(hi<lo)continue;
                    double a=occupied.toSegment(lo),b=occupied.toSegment(hi);
                    double begin=leg.start()+(leg.forward()?Math.min(a,b):leg.length()-Math.max(a,b));
                    double end=leg.start()+(leg.forward()?Math.max(a,b):leg.length()-Math.min(a,b));
                    if(end>=center-length && begin<=front)limit=Math.min(limit,center);
                    else if(begin>front)limit=Math.min(limit,Math.max(center,begin-5));
                }
            }
        }
        return limit;
    }
    private void rebuildYardLocks(){
        Set<String> previous=new HashSet<>(yardLocks.keySet());
        yardLocks.entrySet().removeIf(e->{Train t=data.trains.get(e.getValue());return t==null || !Objects.equals(e.getKey(),t.yardBase) || "PARKED".equals(t.yardPhase);});
        for(Train t:data.trains.values())if(t.yardBase!=null && !"PARKED".equals(t.yardPhase))yardLocks.merge(t.yardBase,t.id,(a,b)->a.equals(b)?a:"!conflict");
        for(String base:previous)if(!yardLocks.containsKey(base))signals.release("yard:"+base);
    }
    private void seedOccupancy(){
        for(String id:new HashSet<>(signalTrains))if(!data.trains.containsKey(id)){signals.release(id);signalTrains.remove(id);routes.remove(id);}
        List<Occupancy> positions=new ArrayList<>();
        for(Train t:data.trains.values())positions.add(new Occupancy(t.id,t.yardBase==null?routes.get(t.id):null,t.distance,t.length(),t.node));
        physical=List.copyOf(positions);opposing=opposingOccupants(physical,graph);
        for(String base:yardLocks.keySet())if(graph.node(base)!=null)signals.update("yard:"+base,Route.stay(graph,base),0,0,0);
        // First seed standing stock, then all route footprints, before any dispatch or authority extension.
        for(int pass=0;pass<2;pass++)for(Train t:data.trains.values()){
            Route r=t.yardBase==null?routes.get(t.id):null;
            if((pass==0)!=(r==null))continue;
            if(r==null){if(graph.node(t.node)!=null)signals.update(t.id,Route.stay(graph,t.node),0,0,0);}
            else if(!opposing.contains(t.id) && !wouldForceOpposing(signals,t.id,r,t.distance,t.length(),graph))signals.update(t.id,r,t.distance+4,t.distance-t.length(),0);
            signalTrains.add(t.id);
        }
    }
    public void leave(Player p) {ArmorStand s=seats.remove(p.getUniqueId());Location fallback=boardingPoints.remove(p.getUniqueId());if(s!=null){Location at=s.getLocation();s.remove();World w=at.getWorld();for(int radius=2;radius<=7;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){int x=at.getBlockX()+dx,z=at.getBlockZ()+dz;int y=w.getHighestBlockYAt(x,z)+1;Location safe=new Location(w,x+.5,y,z+.5);if(Math.abs(y-at.getY())<=5 && w.getBlockAt(x,y-1,z).getType().isSolid() && safe.getBlock().isPassable() && safe.clone().add(0,1,0).getBlock().isPassable()){p.teleport(safe);return;}}if(fallback!=null)p.teleport(fallback);}}
    public void close(){new ArrayList<>(seats.keySet()).forEach(id->{Player p=Bukkit.getPlayer(id);if(p!=null)leave(p);});seats.values().forEach(Entity::remove);drivers.values().forEach(Entity::remove);stokers.values().forEach(Entity::remove);if(models!=null)models.close();}
}
