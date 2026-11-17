// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import org.bukkit.*;
import pl.medivalrpg.kolej.build.*;
import pl.medivalrpg.kolej.depot.DepotPlan;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.world.*;
import pl.medivalrpg.skarbiec.api.*;

/** One explicitly requested royal depot, queued after activation. No region files are edited offline. */
final class BaseCommission implements AutoCloseable {
    record Request(boolean enabled,String id,UUID country,String world,int x,int groundY,int z,int rotation,long maxCost){}
    record State(String id,String phase,long cost,String reason){}
    private final KolejPlugin plugin;
    private final RailwayProjects projects;
    private final AtomicJsonFile<State> store;
    private Request request;
    private State state;
    private final List<Chunk> tickets=new ArrayList<>();
    private boolean closed;
    BaseCommission(KolejPlugin plugin,RailwayProjects projects) {
        this.plugin=plugin;this.projects=projects;store=new AtomicJsonFile<>(plugin.getDataFolder().toPath().resolve("commission-state.json"),State.class);
        Path file=plugin.getDataFolder().toPath().resolve("commission.json");
        if(!Files.exists(file))return;
        try {
            request=new Gson().fromJson(Files.readString(file),Request.class);
            if(request==null || !request.enabled)return;
            if(!RailData.identifier(request.id) || request.country==null || request.world==null || request.rotation<0 || request.rotation>3 || request.maxCost<=0)throw new IllegalArgumentException("Nieprawidłowe zlecenie bazy");
            Path ledger=plugin.getDataFolder().toPath().resolve("commission-state.json");
            // Financial history must never silently roll back to an older .bak and pay twice.
            state=Files.exists(ledger)?new Gson().fromJson(Files.readString(ledger),State.class):new State(request.id,"NEW",0,"");
            if(state==null || state.phase==null)throw new IllegalArgumentException("Nieprawidłowy dziennik finansowy bazy");
            if(!request.id.equals(state.id))throw new IllegalArgumentException("Id zlecenia i dziennika różnią się");
            if(projects.works().job(request.id).isPresent()) {Bukkit.getScheduler().runTaskLater(plugin,this::load,40);return;}
            if(!state.phase.equals("NEW")){plugin.getLogger().warning("Baza: wymagana kontrola dziennika zlecenia "+state.phase);return;}
            Bukkit.getScheduler().runTaskLater(plugin,this::load,40);
        }catch(Exception e){plugin.getLogger().warning("Baza nie została uruchomiona: "+e.getMessage());}
    }
    private void load() {
        if(closed)return;
        try {
            World world=Bukkit.getWorld(request.world);if(world==null)throw new IllegalArgumentException("Brak świata bazy");
            var country=plugin.countries().getCountry(request.country).orElseThrow(()->new IllegalArgumentException("Brak państwa właściciela bazy"));
            if(plugin.data().ownerCountry!=null && !plugin.data().ownerCountry.equals(request.country))throw new IllegalArgumentException("Firma ma inne państwo właściciela");
            if(plugin.data().ownerCountry==null){plugin.data().ownerCountry=request.country;plugin.save();}
            var claims=plugin.countries().getClaims(request.country,request.world);
            boolean near=claims.stream().anyMatch(key->{int cx=(int)(key>>32),cz=(int)(long)key;double dx=Math.max(0,Math.abs(cx*16+8-request.x)-8),dz=Math.max(0,Math.abs(cz*16+8-request.z)-8);return Math.hypot(dx,dz)<=128;});
            if(!near)throw new IllegalArgumentException("Zlecona baza nie leży przy granicy swojego państwa");
            int hx=request.rotation%2==0?38:30,hz=request.rotation%2==0?30:38;
            List<CompletableFuture<Chunk>> loading=new ArrayList<>();
            for(int cx=(request.x-hx)>>4;cx<=(request.x+hx)>>4;cx++)for(int cz=(request.z-hz)>>4;cz<=(request.z+hz)>>4;cz++)loading.add(world.getChunkAtAsync(cx,cz,false));
            CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).whenComplete((v,error)->{
                if(closed || !plugin.isEnabled())return;
                Bukkit.getScheduler().runTask(plugin,()->{try {if(error!=null || loading.stream().anyMatch(f->f.join()==null))throw new IllegalArgumentException("Brak wygenerowanego terenu bazy");
                    for(var future:loading){Chunk c=future.join();c.addPluginChunkTicket(plugin);tickets.add(c);}
                    if(projects.works().job(request.id).isPresent()) {
                        if(plugin.graph().node(request.id)==null)register(world);
                    }else prepare(world,hx,hz);
                }catch(Exception ex){fail(ex.getMessage());}});
            });
        }catch(Exception e){fail(e.getMessage());}
    }
    private void prepare(World w,int hx,int hz) {
        try {
            List<BlockOp> ops=new ArrayList<>();
            for(int x=request.x-hx;x<=request.x+hx;x++)for(int z=request.z-hz;z<=request.z+hz;z++) {
                if(!plugin.allowed(new Location(w,x,request.groundY,z)))throw new IllegalArgumentException("Zmieniły się granice lub zgody na teren bazy");
                int top=w.getHighestBlockYAt(x,z);
                if(Math.abs(top-request.groundY)>64)throw new IllegalArgumentException("Niwelacja przekracza 64 bloki w kolumnie "+x+","+z);
                for(int y=request.groundY+1;y<=top;y++)ops.add(BlockOp.clear(x,y,z));
                boolean rim=x==request.x-hx || x==request.x+hx || z==request.z-hz || z==request.z+hz;
                for(int y=Math.min(top+1,request.groundY-2);y<request.groundY;y++)ops.add(BlockOp.fill(x,y,z,rim?BlockOp.Part.BRIDGE_PILLAR:BlockOp.Part.FILL));
                ops.add(BlockOp.place(x,request.groundY,z,BlockOp.Part.ROAD));
            }
            ops.addAll(DepotPlan.plan(request.x,request.groundY,request.z,request.rotation));
            var quote=projects.works().quote(w,ops,request.country);
            if(!quote.allowed())throw new IllegalArgumentException(String.join("; ",quote.obstacles()));
            long price=Math.addExact(plugin.getConfig().getLong("koszty.baza",50000),Math.multiplyExact(quote.cost(),plugin.getConfig().getLong("koszty.blok-budowy",1)));
            if(price>request.maxCost)throw new IllegalArgumentException("Koszt bazy "+price+" przekracza zatwierdzony limit "+request.maxCost);
            // No money is invented. Reserve the real quoted price in the company account.
            persist(new State(request.id,"CHARGING",price,""),()->{
                if(plugin.economy().transfer(SkarbiecApi.countryAccount(request.country),RailData.ACCOUNT,price,"Fundusz budowy bazy "+request.id)!=TxResult.OK){fail("Za mało środków w skarbcu państwa na bazę: "+plugin.economy().format(price));return;}
                persist(new State(request.id,"FUNDED",price,""),()->{
                    if(plugin.economy().withdraw(RailData.ACCOUNT,price,"Budowa królewskiej bazy "+request.id)!=TxResult.OK){fail("Brak środków odłożonych na budowę");return;}
                    persist(new State(request.id,"PAID",price,""),()->{
                        var accepted=projects.works().submit(request.id,w,ops,request.country);
                        if(!accepted.accepted()){fail("Opłacone zlecenie wymaga kontroli: "+accepted.reason());return;}
                        register(w);
                        persist(new State(request.id,"SUBMITTED",price,""),()->plugin.getLogger().info("Robotnicy przyjęli budowę bazy "+request.id+" przy "+request.x+", "+request.z));
                    });
                });
            });
        }catch(Exception e){fail(e.getMessage());}
    }
    private void register(World w) {
        int[] gate=PlanRotation.point(0,-18,request.rotation);
        plugin.graph().putNode(new TrackNode(request.id,"Baza przewoźnika",NodeType.DEPOT,w.getName(),request.x+gate[0],request.groundY+1,request.z+gate[1],request.rotation%2,1));
        plugin.data().depots.put(request.id,new RailData.Depot(request.x,request.groundY,request.z,request.rotation));plugin.save();
    }
    private void persist(State next,Runnable after) {
        state=next;Future<?> future=store.write(next);
        new org.bukkit.scheduler.BukkitRunnable(){public void run(){if(closed){cancel();return;}if(!future.isDone())return;cancel();try{future.get();after.run();}catch(Exception e){fail("Nie udało się zapisać dziennika zlecenia");}}}.runTaskTimer(plugin,1,1);
    }
    private void fail(String reason) {plugin.getLogger().warning("Baza: "+reason);if(request!=null){state=new State(request.id,"BLOCKED",state==null?0:state.cost,reason);store.write(state);}release();}
    private void release(){for(Chunk c:tickets)c.removePluginChunkTicket(plugin);tickets.clear();}
    public void tick(){if(request!=null && projects.ready(request.id))release();}
    public void close(){closed=true;release();store.close();}
}
