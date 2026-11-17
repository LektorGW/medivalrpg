// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import com.google.gson.Gson;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import org.bukkit.*;
import org.bukkit.block.data.Rail;
import pl.medivalrpg.kolej.build.RailGeometry;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.runtime.BukkitRailProbe;
import pl.medivalrpg.kolej.signal.PhysicalRailGuard;

/** Separate, read-only physical acceptance before publishing new graph links. Never charges an account. */
final class NetworkGraphCommission implements AutoCloseable {
    record Request(int version,String file,String sha256) {}
    record Check(TrackCell cell,String shape,boolean grade,boolean junction) {}
    record PermissionPoint(int x,int y,int z) {}
    /** Invalidations are consumed only immediately before a loaded, live recheck. */
    static final class AuditProgress {
        private int cursor;
        private final LinkedHashSet<Integer> dirty=new LinkedHashSet<>();
        int cursor(){return cursor;}
        int dirtyCount(){return dirty.size();}
        void invalidated(int index){if(index>=0 && index<cursor)dirty.add(index);}
        int next(int total){return !dirty.isEmpty()?dirty.iterator().next():cursor<total?cursor:-1;}
        void beforeInspection(int index){
            if(index<0 || index>cursor || index==cursor && !dirty.isEmpty())throw new IllegalStateException("Nieprawidłowa kolejność odbioru");
            if(index<cursor && !dirty.remove(index))throw new IllegalStateException("Brak zgłoszonej zmiany odbioru");
        }
        void inspected(int index){if(index==cursor)cursor++;else if(index<0 || index>cursor)throw new IllegalStateException("Nieprawidłowy indeks odbioru");}
        boolean complete(int total){return cursor==total && dirty.isEmpty();}
    }
    private final KolejPlugin plugin;
    private final RailwayProjects projects;
    private final Path folder;
    private final NetworkCommission.Request work;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Kolej-Network-Acceptance");t.setDaemon(true);return t;});
    private CompletableFuture<CommissionGraph.Plan> reading;
    private CommissionGraph.Plan plan;
    private TrackGraph candidate;
    private List<Check> checks;
    private final List<Chunk> tickets=new ArrayList<>();
    private final List<CompletableFuture<Chunk>> loading=new ArrayList<>();
    private final Set<Long> loaded=new HashSet<>();
    private CompletableFuture<Void> saving;
    private final AuditProgress audit=new AuditProgress();
    private AuditMutationWatch watcher;
    private List<PermissionPoint> permissionPoints;
    private String status="Oczekiwanie na plan odbioru sieci";
    private boolean stopped,closed,finished,published;
    NetworkGraphCommission(KolejPlugin plugin,RailwayProjects projects,Path folder,NetworkCommission.Request work){this.plugin=plugin;this.projects=projects;this.folder=folder;this.work=work;}
    String status(){return status;}
    boolean finished(){return finished;}
    void tick(){
        if(closed || stopped || finished)return;
        try {
            if(saving!=null){
                if(!audit.complete(checks.size()))throw new IllegalStateException("Tor zmienił się podczas zapisu odbioru");
                if(saving.isDone()){saving.join();World world=Bukkit.getWorld(work.world());if(world==null)throw new IllegalStateException("Brak świata odbioru");validatePublication(world);if(!audit.complete(checks.size()))throw new IllegalStateException("Tor zmienił się podczas końcowej kontroli");finished=true;status="Sieć odebrana fizycznie i zapisana; można skonfigurować linię";closeWatcher();release();}return;
            }
            Path requestFile=folder.resolve("graph-request.json");if(!Files.exists(requestFile))return;
            World world=Bukkit.getWorld(work.world());if(world==null)throw new IllegalStateException("Brak świata odbioru");
            if(reading==null){
                var request=new Gson().fromJson(Files.readString(requestFile),Request.class);
                if(request==null || request.version()!=1 || request.file()==null || !request.file().matches("[a-zA-Z0-9_-]+[.]json") || request.sha256()==null || !request.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Nieprawidłowe zlecenie odbioru");
                reading=CompletableFuture.supplyAsync(()->{try {
                    Path file=folder.resolve(request.file());if(Files.size(file)>8_000_000)throw new IllegalArgumentException("Plan grafu za duży");byte[] bytes=Files.readAllBytes(file);
                    if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(request.sha256()))throw new IllegalArgumentException("Plan grafu zmieniony po zatwierdzeniu");
                    return new Gson().fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),CommissionGraph.Plan.class);
                }catch(Exception e){throw new CompletionException(e);}},io);return;
            }
            if(plan==null){
                if(!reading.isDone())return;plan=reading.join();
                Set<String> required=new HashSet<>();work.parts().forEach(p->required.add(p.id()));
                if(plan==null || !work.world().equals(plan.world()) || !required.equals(new HashSet<>(plan.sourceJobs())))throw new IllegalArgumentException("Graf nie odpowiada ukończonym robotom");
                Set<String> done=new HashSet<>();for(var id:required)if(projects.works().job(id).map(j->j.status().equals("DONE")).orElse(false))done.add(id);
                candidate=CommissionGraph.prepare(plugin.graph(),plan,done);checks=checks(candidate,plan);
                permissionPoints=permissionPoints(checks);watcher=new AuditMutationWatch(plugin,world,watchVolumes(checks),audit::invalidated);
                status="Odbiór "+checks.size()+" komórek toru";return;
            }
            for(var it=loading.iterator();it.hasNext();) {var future=it.next();if(!future.isDone())continue;var chunk=future.join();it.remove();if(chunk==null)throw new IllegalStateException("Brak wygenerowanego chunku odbioru");chunk.addPluginChunkTicket(plugin);tickets.add(chunk);loaded.add(key(chunk.getX(),chunk.getZ()));}
            if(!loading.isEmpty())return;
            var probe=new BukkitRailProbe(world,plugin::allowed);
            runBatch(audit,checks.size(),index->ensureLoaded(world,checks.get(index)),index->{
                Check c=checks.get(index);
                String shape=c.shape;
                if(c.junction){var data=world.getBlockAt(c.cell.x(),c.cell.y(),c.cell.z()).getBlockData();if(!(data instanceof Rail rail) || rail.getShape().name().startsWith("ASCENDING"))throw new IllegalStateException("Brak płaskiej zwrotnicy przy "+c.cell);shape=rail.getShape().name();}
                if(!world.getBlockAt(c.cell.x(),c.cell.y()-1,c.cell.z()).getType().isSolid())throw new IllegalStateException("Brak podparcia toru przy "+c.cell);
                String problem=probe.inspect(new PhysicalRailGuard.Rail(c.cell,0,shape,null,c.grade));if(problem!=null)throw new IllegalStateException(problem+" przy "+c.cell);
            });
            status="Odbiór torów "+audit.cursor()+"/"+checks.size()+"; ponowne kontrole "+audit.dirtyCount();
            if(!audit.complete(checks.size()))return;
            validatePublication(world);
            if(!audit.complete(checks.size()))return; // No publication with invalidations raised by final callbacks.
            if(plugin.data().commissionedNodeJobs==null)plugin.data().commissionedNodeJobs=new LinkedHashMap<>();
            // Only new audited links are published; existing unrelated graph state remains intact.
            for(var node:plan.nodes())plugin.graph().putNode(node);
            for(var spec:plan.segments()){var s=candidate.segment(spec.id());s.status(SegmentStatus.BUILT);plugin.graph().putSegment(s);}
            for(var node:plan.nodes())if(node.isWaitingPoint())plugin.data().commissionedNodeJobs.put(node.id(),List.copyOf(plan.sourceJobs()));
            published=true;saving=plugin.saveDurable();status="Zapis odebranej sieci";
        }catch(Exception error){
            if(published && plan!=null){
                for(var spec:plan.segments()){var active=plugin.graph().segment(spec.id());if(active==candidate.segment(spec.id()))active.status(SegmentStatus.PLANNED);}
                for(var node:plan.nodes())if(plugin.data().commissionedNodeJobs!=null && Objects.equals(plugin.data().commissionedNodeJobs.get(node.id()),plan.sourceJobs()))plugin.data().commissionedNodeJobs.remove(node.id());
                // Ordered after any in-flight BUILT snapshot; a late mutation must not remain published on disk.
                try{plugin.saveDurable().exceptionally(failure->{plugin.getLogger().log(java.util.logging.Level.SEVERE,"Nie można trwale wycofać odbioru sieci",failure);return null;});}
                catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Nie można zlecić trwałego wycofania odbioru sieci",failure);}
            }
            stopped=true;status="Odbiór wstrzymany: "+NetworkCommission.problem(error);closeWatcher();release();plugin.getLogger().log(java.util.logging.Level.SEVERE,status,error);}
    }
    private boolean ensureLoaded(World world,Check c){
        TreeSet<Long> needed=new TreeSet<>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){long chunk=key((c.cell.x()+dx)>>4,(c.cell.z()+dz)>>4);if(!loaded.contains(chunk))needed.add(chunk);}
        if(needed.isEmpty())return true;
        if(tickets.size()+needed.size()>16)release();
        for(long chunk:needed)loading.add(world.getChunkAtAsync((int)(chunk>>32),(int)chunk,false));return false;
    }
    private void validatePublication(World world){
        if(!plugin.railwayManager(work.manager()) || !Objects.equals(plugin.data().ownerCountry,work.country()))throw new IllegalStateException("Zmieniono uprawnienia firmy");
        Set<String> done=new HashSet<>();for(String id:plan.sourceJobs()){
            if(projects.works().job(id).map(job->!job.status().equals("DONE")).orElse(true))throw new IllegalStateException("Zmieniono stan robót podczas odbioru");done.add(id);
        }
        validateCurrentBorder(world,checks);
        // Country consent is chunk-based; these metadata lookups do not load or inspect world blocks.
        for(PermissionPoint point:permissionPoints)if(!plugin.allowed(new Location(world,point.x,point.y,point.z)))throw new IllegalStateException("Cofnięto zgodę na przejazd w chunku "+(point.x>>4)+","+(point.z>>4));
        CommissionGraph.prepare(plugin.graph(),plan,done);
    }
    /** Re-read the live border on every final validation, including after the durable save.
     * A chunk representative cannot stand in for actual rails or their support/clearance footprint. */
    static void validateCurrentBorder(World world,List<Check> checks){
        WorldBorder border=world.getWorldBorder();
        for(Check c:checks)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            if(!border.isInside(new Location(world,c.cell.x()+dx,c.cell.y(),c.cell.z()+dz)))
                throw new IllegalStateException("Tor lub jego skrajnia poza aktualną granicą świata przy "+c.cell);
    }
    static int runBatch(AuditProgress audit,int total,IntPredicate ready,IntConsumer inspect){
        int count=0;
        while(count<64){int index=audit.next(total);if(index<0 || !ready.test(index))break;audit.beforeInspection(index);inspect.accept(index);audit.inspected(index);count++;}
        return count;
    }
    static List<AuditMutationWatch.Volume> watchVolumes(List<Check> checks){
        List<AuditMutationWatch.Volume> out=new ArrayList<>();for(int i=0;i<checks.size();i++){Check c=checks.get(i);out.add(new AuditMutationWatch.Volume(c.cell.x(),c.cell.y(),c.cell.z(),c.grade?5:4,i));}return List.copyOf(out);
    }
    static List<PermissionPoint> permissionPoints(List<Check> checks){
        Map<Long,PermissionPoint> points=new LinkedHashMap<>();
        for(Check c:checks)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){int x=c.cell.x()+dx,z=c.cell.z()+dz;points.putIfAbsent(key(x>>4,z>>4),new PermissionPoint(x,c.cell.y(),z));}
        return List.copyOf(points.values());
    }
    static List<Check> checks(TrackGraph graph,CommissionGraph.Plan plan){
        Map<String,Check> out=new LinkedHashMap<>();
        for(var segment:plan.segments())for(int i=0;i<segment.cells().size();i++) {
            var cells=segment.cells();var c=cells.get(i);var a=cells.get(Math.max(0,i-1));var b=cells.get(Math.min(cells.size()-1,i+1));
            boolean junction=graph.nodes().stream().anyMatch(n->n.world().equals(plan.world()) && n.type()==NodeType.JUNCTION && n.x()==c.x() && n.y()==c.y() && n.z()==c.z());
            out.put(c.x()+":"+c.y()+":"+c.z(),new Check(c,RailGeometry.shape(a.x(),a.y(),a.z(),c.x(),c.y(),c.z(),b.x(),b.y(),b.z()).toUpperCase(Locale.ROOT),a.y()!=c.y() || b.y()!=c.y(),junction));
        }
        for(var node:plan.nodes())if(node.isStation())for(int d=-64;d<=64;d++) {
            int x=node.x()+(node.axis()==1?d:0),z=node.z()+(node.axis()==0?d:0);
            var c=new TrackCell(x,node.y(),z,CellKind.PLATFORM);out.put(x+":"+node.y()+":"+z,new Check(c,node.axis()==1?"EAST_WEST":"NORTH_SOUTH",false,false));
        }
        return List.copyOf(out.values());
    }
    private static long key(int x,int z){return((long)x<<32)|(z&0xffffffffL);}
    private void closeWatcher(){if(watcher!=null){watcher.close();watcher=null;}}
    private void release(){for(var chunk:tickets)chunk.removePluginChunkTicket(plugin);tickets.clear();loaded.clear();}
    @Override public void close(){closed=true;closeWatcher();release();io.shutdown();}
}
