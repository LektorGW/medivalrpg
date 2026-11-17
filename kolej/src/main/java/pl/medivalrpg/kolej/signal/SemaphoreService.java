// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import pl.medivalrpg.kolej.KolejPlugin;
import pl.medivalrpg.kolej.depot.*;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.visual.SemaphoreModels;
import pl.medivalrpg.kolej.world.WorksProtection;

/** Bounded, persistent visual repeaters of verified runtime authority. No command can force CLEAR. */
public final class SemaphoreService implements AutoCloseable {
    private final KolejPlugin plugin;
    private final WorksProtection protection;
    private final Map<String,SemaphorePolicy.Indication> aspects=new HashMap<>();
    private SemaphoreModels models;
    private CompletableFuture<Void> writing;
    private Map<String,SemaphorePlacement> before;
    private String failure;
    public SemaphoreService(KolejPlugin plugin) {
        this.plugin=plugin;protection=new WorksProtection(plugin.countries());protection.setClaimValidator((at,country)->plugin.allowed(at));
        try {
            if(plugin.data().semaphores==null || plugin.data().semaphores.size()>64)throw new IllegalArgumentException("Nieprawidłowy rejestr semaforów");
            for(var e:plugin.data().semaphores.entrySet()){e.getValue().validate();if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Niezgodne id semafora");}
            if(Bukkit.getPluginManager().isPluginEnabled("BetterModel"))models=new SemaphoreModels();
        }catch(RuntimeException | LinkageError e){failure=e.toString();plugin.getLogger().severe("Semafory: "+failure);}
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Semafory wymagają głównego wątku");}
    public void tick() {
        main();
        if(writing!=null && writing.isDone()) {
            try{writing.join();}catch(RuntimeException e){plugin.data().semaphores=before;failure=e.toString();plugin.getLogger().severe("Zapis semaforów: "+failure);}
            writing=null;before=null;
        }
        if(models==null || plugin.data().semaphores==null || plugin.data().semaphores.size()>64)return;
        for(var s:plugin.data().semaphores.values()) {
            SemaphorePolicy.Indication indication=SemaphorePolicy.Indication.stop("Brak potwierdzonego stanu semafora");
            try {
                World w=world(s);
                if(w==null || !w.isChunkLoaded(s.x()>>4,s.z()>>4)){models.remove(s.id());aspects.put(s.id(),SemaphorePolicy.Indication.stop("Chunk semafora niewczytany"));continue;}
                Location at=new Location(w,s.x()+.5,s.y()+1,s.z()+.5,s.rotation()*90,0);
                boolean near=w.getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(at)<96*96);
                validateSite(s,false);
                indication=failure!=null?SemaphorePolicy.Indication.stop("Błąd zapisu semaforów"):writing!=null?SemaphorePolicy.Indication.stop("Trwa zapis semafora"):plugin.runtime().semaphore(s.node(),s.segment());
                if(!near)models.remove(s.id());
                else if(!models.show(s.id(),at,indication.clear()))indication=SemaphorePolicy.Indication.stop("Brak zgodnej kości arm w BetterModel");
            }catch(RuntimeException | LinkageError e){models.remove(s.id());indication=SemaphorePolicy.Indication.stop(e.getMessage()==null?"Błąd modelu semafora":e.getMessage());}
            aspects.put(s.id(),indication);
        }
    }
    public void command(Player player,String[] a) {
        main();if(!plugin.isManager(player))throw new IllegalArgumentException("Tylko wyznaczony zarządca przewoźnika.");
        if(a.length<2)throw new IllegalArgumentException("/kolej semafor dodaj|usun|lista");
        if(a[1].equals("lista")) {
            count(a,2,"lista");if(failure!=null)plugin.message(player,"BLOKADA: "+failure);
            if(plugin.data().semaphores!=null)for(var s:plugin.data().semaphores.values()) {
                var state=aspects.getOrDefault(s.id(),SemaphorePolicy.Indication.stop("Jeszcze niesprawdzony"));
                plugin.message(player,s.id()+" "+s.node()+" → "+s.segment()+": "+(state.clear()?"JEDŹ ("+state.train()+")":"STOP")+" — "+state.reason());
            }return;
        }
        if(failure!=null || writing!=null)throw new IllegalArgumentException(failure!=null?"Błąd semaforów — sprawdź zapis i log":"Poczekaj na zapis semafora");
        Map<String,SemaphorePlacement> copy=new LinkedHashMap<>(plugin.data().semaphores);
        switch(a[1]) {
            case "dodaj" -> {
                count(a,6,"dodaj <id> <wezel> <odcinek> <0|90|180|270>");
                if(copy.size()>=64 || copy.containsKey(a[2]))throw new IllegalArgumentException("Id zajęte lub limit 64 semaforów");
                int degrees;try{degrees=Integer.parseInt(a[5]);}catch(NumberFormatException e){throw new IllegalArgumentException("Obrót: 0|90|180|270");}
                if(!Set.of(0,90,180,270).contains(degrees))throw new IllegalArgumentException("Obrót: 0|90|180|270");
                var block=player.getTargetBlockExact(6);if(block==null)throw new IllegalArgumentException("Wyceluj w fundament semafora (6 bloków)");
                var s=new SemaphorePlacement(a[2],plugin.node(a[3]).id(),a[4],block.getWorld().getUID().toString(),block.getX(),block.getY(),block.getZ(),degrees/90,block.getType().name());
                s.validate();validateSite(s,true);
                if(models==null || !models.ready())throw new IllegalArgumentException("Model kolej_semafor_mechaniczny nie jest gotowy w BetterModel");
                copy.put(s.id(),s);
            }
            case "usun" -> {
                count(a,3,"usun <id>");var s=copy.get(a[2]);if(s==null)throw new IllegalArgumentException("Nie ma semafora");
                World w=world(s);if(w==null || !player.getWorld().equals(w) || player.getLocation().distanceSquared(new Location(w,s.x()+.5,s.y()+1,s.z()+.5))>36)throw new IllegalArgumentException("Podejdź do semafora (6 bloków)");
                copy.remove(s.id());aspects.remove(s.id());
            }
            default -> throw new IllegalArgumentException("/kolej semafor dodaj|usun|lista");
        }
        if(models!=null)models.close(); // All indications fail STOP until the durable placement change completes.
        before=plugin.data().semaphores;plugin.data().semaphores=copy;writing=plugin.saveDurable();
        plugin.message(player,"Zapis semaforów w toku. Brak ręcznego wymuszania sygnału JEDŹ.");
    }
    private static void count(String[] a,int n,String usage){if(a.length!=n)throw new IllegalArgumentException("/kolej semafor "+usage);}
    private World world(SemaphorePlacement s){return Bukkit.getWorld(UUID.fromString(s.world()));}
    private void territory(World w,int x,int y,int z){String denial=protection.territory(w,x,y,z,plugin.data().ownerCountry);if(denial!=null)throw new IllegalArgumentException(denial);}
    private void validateSite(SemaphorePlacement s,boolean placement) {
        s.validate();World w=world(s);if(w==null || !w.isChunkLoaded(s.x()>>4,s.z()>>4))throw new IllegalArgumentException("Chunk semafora niewczytany");
        TrackNode n=plugin.graph().node(s.node());TrackSegment segment=plugin.graph().segment(s.segment());
        if(n==null || !n.world().equals(w.getName()) || segment==null || !segment.isBuilt() || !segment.touches(n.id()) || segment.cells().size()<2)throw new IllegalArgumentException("Brak gotowego przebiegu semafora");
        if(n.point().distance(new pl.medivalrpg.kolej.geom.Vec3(s.x()+.5,s.y()+1,s.z()+.5))>16)throw new IllegalArgumentException("Semafor musi stać do 16 bloków od chronionego węzła");
        boolean forward=segment.from().equals(n.id());var rail=segment.cells().get(forward?0:segment.cells().size()-1);var next=segment.cells().get(forward?1:segment.cells().size()-2);
        int dx=next.x()-rail.x(),dz=next.z()-rail.z();
        int direction=dx==1?3:dx==-1?1:dz==1?0:2;
        if(rail.x()!=n.x() || rail.y()!=n.y() || rail.z()!=n.z() || Math.abs(dx)+Math.abs(dz)!=1 || direction!=s.rotation())throw new IllegalArgumentException("Kierunek semafora nie odpowiada wyjazdowi na wybrany odcinek");
        territory(w,s.x(),s.y(),s.z());var foundation=w.getBlockAt(s.x(),s.y(),s.z());
        if(!foundation.getType().name().equals(s.foundation()) || !foundation.getType().isSolid() || foundation.getState() instanceof TileState)throw new IllegalArgumentException("Brak nieprzenośnego, pełnego fundamentu semafora");
        var b=s.bounds();
        for(int x=(int)Math.floor(s.x()+.5+b.minX());x<=Math.floor(s.x()+.5+b.maxX());x++)for(int z=(int)Math.floor(s.z()+.5+b.minZ());z<=Math.floor(s.z()+.5+b.maxZ());z++)for(int y=s.y()+1;y<s.y()+1+b.height();y++) {
            territory(w,x,y,z);if(!w.getBlockAt(x,y,z).getType().isAir())throw new IllegalArgumentException("Brak miejsca na maszt i obie pozycje ramienia semafora");
        }
        if(placement) {
            for(TrackNode node:plugin.graph().nodes())if(node.world().equals(w.getName())) {
                int extent=node.isStation()?64:0;
                for(int d=-extent;d<=extent;d++)if(conflict(s,node.x()+.5+(node.axis()==1?d:0),node.y(),node.z()+.5+(node.axis()==0?d:0)))throw new IllegalArgumentException("Semafor wchodzi w skrajnię stacji");
            }
            for(var edge:plugin.graph().segments()) {
                TrackNode from=plugin.graph().node(edge.from());if(from==null || !from.world().equals(w.getName()))continue;
                for(var c:edge.cells())if(conflict(s,c.x()+.5,c.y(),c.z()+.5))throw new IllegalArgumentException("Semafor wchodzi w skrajnię toru");
            }
            for(var entry:plugin.data().depots.entrySet()) {
                var node=plugin.graph().node(entry.getKey());if(node==null || !node.world().equals(w.getName()))continue;var depot=entry.getValue();
                for(var link:DepotPlan.trackLinks(depot.x(),depot.groundY()+1,depot.z(),depot.rotation()))for(var c:link.cells())if(conflict(s,c.x()+.5,c.y(),c.z()+.5))throw new IllegalArgumentException("Semafor wchodzi w skrajnię bazy");
                for(int dxPit=-TurntablePlan.PIT_RADIUS;dxPit<=TurntablePlan.PIT_RADIUS;dxPit++)for(int dzPit=-TurntablePlan.PIT_RADIUS;dzPit<=TurntablePlan.PIT_RADIUS;dzPit++)if(conflict(s,depot.x()+dxPit+.5,depot.groundY()+1,depot.z()+dzPit+.5))throw new IllegalArgumentException("Semafor wchodzi w obszar obrotnicy");
            }
            for(var other:plugin.data().semaphores.values())if(other.world().equals(s.world()) && other.x()==s.x() && other.y()==s.y() && other.z()==s.z())throw new IllegalArgumentException("Fundament ma już semafor");
        }
    }
    private static boolean conflict(SemaphorePlacement s,double x,double y,double z){var b=s.bounds();return y+5>s.y()+1 && y<s.y()+1+b.height() && b.contains(x-s.x()-.5,z-s.z()-.5,2);}
    public void close(){if(models!=null)models.close();}
}
