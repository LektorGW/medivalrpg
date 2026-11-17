// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kolej.AuditMutationWatch;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kolej.network.TrackCell;
import pl.medivalrpg.kolej.world.WorksProtection;

/** Caller owns scheduling and charging. All public methods require the server thread. */
public final class RailwayWorks implements Listener, AutoCloseable {
    public record Entry(BlockOp op, String before, String target,String supportBefore) {
        public Entry(BlockOp op,String before,String target){this(op,before,target,null);}
    }
    public record Job(String id, UUID world, UUID country, List<Entry> entries, int cursor, long cost, String status, String reason,
                      List<RailReuse.Check> reused,boolean financeHeld,List<TrackCell> routeCells) {
        public Job { entries = List.copyOf(entries); reused=reused==null?List.of():List.copyOf(reused);routeCells=routeCells==null?List.of():List.copyOf(routeCells); }
        public Job(String id,UUID world,UUID country,List<Entry> entries,int cursor,long cost,String status,String reason,List<RailReuse.Check> reused,boolean financeHeld){this(id,world,country,entries,cursor,cost,status,reason,reused,financeHeld,List.of());}
        public Job(String id,UUID world,UUID country,List<Entry> entries,int cursor,long cost,String status,String reason){this(id,world,country,entries,cursor,cost,status,reason,List.of(),false);}
    }
    public record Snapshot(int version, List<Job> jobs) { public Snapshot { jobs = List.copyOf(jobs); } }
    public record Preview(long cost, Map<String, Integer> materials, List<String> obstacles, int blocks) {
        public Preview { materials = Map.copyOf(materials); obstacles = List.copyOf(obstacles); }
        public boolean allowed() { return obstacles.isEmpty(); }
    }
    public record Result(boolean accepted, String id, long cost, String reason) { }
    private record Point(int x, int y, int z) { }
    private record Prepared(List<Entry> entries, Preview preview) { }
    private record PreparationInput(List<BlockOp> ops,Set<Point> reserved,NaturalClearance.Policy policy,List<NaturalClearance.Span> audit) { }
    public record PreparedPlan(UUID world,UUID country,List<Entry> entries,Preview preview,List<RailReuse.Check> reused,long revision) {
        public PreparedPlan {entries=List.copyOf(entries);reused=List.copyOf(reused);}
    }
    private final Plugin plugin;
    private final WorksProtection protection;
    private final WorksStore store;
    private final NamespacedKey marker;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Mannequin> workers = new HashMap<>();
    private final Map<UUID, List<Location>> previews = new HashMap<>();
    private final Map<UUID, Long> previewVersions = new HashMap<>();
    private Future<?> pending;
    /** Save sequence of the snapshot that first contained each in-session DONE (absent = read from disk). */
    private final Map<String,Long> doneSeq=new HashMap<>();
    private long lastSeq;
    private volatile boolean closed;
    private long clock;
    private boolean dirty;
    private long lastSave = System.nanoTime();
    private long preparationNanos;
    private String saveFailure;
    private java.util.function.Consumer<Job> completion;
    private final Set<String> notified = new HashSet<>();
    private final Set<String> paidThisSession=new HashSet<>();
    private final Map<String,Future<?>> activationWrites=new HashMap<>();
    private final Map<String,int[]> reuseCursors=new HashMap<>();
    private final Map<String,RailReuse.Check> completionChecks=new HashMap<>();
    private final ExecutorService preparation=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Kolej-Przygotowanie");t.setDaemon(true);return t;});
    private final Set<CompletableFuture<PreparedPlan>> preparing=new HashSet<>();
    private long reservationRevision;
    private java.util.function.Predicate<String> suspended=id->false;
    private final Map<CompletableFuture<PreparedPlan>,java.util.function.BooleanSupplier> preparationGates=new HashMap<>();
    public void suspendJobs(java.util.function.Predicate<String> gate){open();suspended=Objects.requireNonNull(gate);}
    public void suspendPreparation(CompletableFuture<PreparedPlan> future,java.util.function.BooleanSupplier gate){open();preparationGates.put(future,gate);}
    /** Invoke every two server ticks; batch size comes from budowa.bloki-na-tick (default 8). */
    public RailwayWorks(Plugin plugin, java.util.function.Predicate<Location> allowed) {
        this(plugin, (KrajeApi) null);
        Objects.requireNonNull(allowed);
        setClaimValidator((location, country) -> allowed.test(location));
    }
    public RailwayWorks(Plugin plugin, KrajeApi countries) {
        main();
        this.plugin = Objects.requireNonNull(plugin);
        protection = new WorksProtection(countries);
        store = new WorksStore(plugin.getDataFolder().toPath().resolve("works/jobs.json"));
        marker = new NamespacedKey(plugin, "works_builder");
        try { restore(store.read()); }
        catch (Exception ex) { store.shutdown(); throw new IllegalStateException("Nie można odczytać zleceń kolei", ex); }
        for (World world : Bukkit.getWorlds()) cleanup(world.getEntities());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required"); }
    private void open() { main(); if (closed) throw new IllegalStateException("Works closed"); }
    /** Replaces only country policy; caller supplies current alliance AND ruler-permit checks. */
    public void setClaimValidator(java.util.function.BiPredicate<Location, UUID> validator) {
        open(); protection.setClaimValidator(validator);
    }
    /** Durable DONE notifications, replayed after restart. Handler must be idempotent. */
    public void onComplete(java.util.function.Consumer<Job> handler) { open(); completion = Objects.requireNonNull(handler); }
    public Optional<Job> job(String id) { main(); return Optional.ofNullable(jobs.get(id)); }
    public Optional<String> persistenceFailure() { main(); saved(); return Optional.ofNullable(saveFailure); }
    /** DONE whose snapshot is already durable on disk; payments and acceptance rely on this, never on memory alone. */
    public boolean durablyDone(String id) {
        main(); Job j=jobs.get(id);
        return j!=null && j.status.equals("DONE") && store.durableSeq()>=doneSeq.getOrDefault(id,0L);
    }
    /** One line for /kolej siec status: write health of works/jobs.json. */
    public String persistenceStatus() {
        main(); saved();
        if(saveFailure!=null) {
            String cause=store.failure().map(ex->String.valueOf(ex.getMessage())).orElse("");
            if(cause.length()>160)cause=cause.substring(0,160)+"…";
            return "Zapis zleceń (jobs.json): BŁĄD — "+saveFailure+(cause.isEmpty()?"":" ("+cause+")")+". Sprawdź dysk i log; zmiany wymagają restartu.";
        }
        return "Zapis zleceń (jobs.json): OK, ostatni zapis "+store.lastWriteMillis()+" ms"+(store.durableSeq()<store.submittedSeq()?", zapis w toku":"");
    }
    /** Last complete world validation duration, for profiling on the real server. */
    public long lastPreparationNanos() { main(); return preparationNanos; }
    public Preview preview(Player player, List<BlockOp> ops) { return preview(player, ops, null); }
    /** World validation and fixed-plan quote only: no player, packets, scheduling or block changes. */
    public Preview quote(World world, List<BlockOp> ops, UUID country) {
        open(); return prepare(world,ops,country).preview;
    }
    public Preview preview(Player player, List<BlockOp> ops, UUID country) {
        open(); clearPreview(player);
        Prepared p = prepare(player.getWorld(), ops, country);
        List<Location> sent = new ArrayList<>();
        if (p.preview.allowed()) {
            for (Entry entry : p.entries) {
                if (sent.size() >= 512) break;
                BlockOp op = entry.op;
                Location at = new Location(player.getWorld(), op.x(), op.y(), op.z());
                if (at.distanceSquared(player.getLocation()) > 64 * 64) continue;
                player.sendBlockChange(at, Bukkit.createBlockData(entry.target)); sent.add(at);
            }
        }
        previews.put(player.getUniqueId(), sent);
        long version = previewVersions.merge(player.getUniqueId(), 1L, Long::sum);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!closed && Objects.equals(previewVersions.get(player.getUniqueId()), version)) clearPreview(player);
        }, 100L);
        return p.preview;
    }
    public void clearPreview(Player player) {
        main();
        var locations = previews.remove(player.getUniqueId());
        if (locations != null && player.isOnline()) for (Location at : locations) {
            if (at.getWorld().equals(player.getWorld()) && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
                player.sendBlockChange(at, at.getBlock().getBlockData());
        }
    }
    /** Dedup/sort away from Bukkit; at most 128 inspections and 2 ms of live validation per tick. */
    public CompletableFuture<PreparedPlan> prepareAsync(World world,List<BlockOp> ops,UUID country,List<RailReuse.Check> reused) {
        return prepareInternalAsync(world,ops,country,reused,Map.of(),Map.of(),false);
    }
    /** Trusted, hash-pinned server loader only. Fingerprints authorize these coordinates in this plan alone. */
    public CompletableFuture<PreparedPlan> prepareTrustedAsync(World world,List<BlockOp> ops,UUID country,List<RailReuse.Check> reused,Map<String,String> naturalBefore,Map<String,String> plantSoil) {
        return prepareTrustedAsync(world,ops,country,reused,naturalBefore,plantSoil,Map.of());
    }
    /** {@code ownBefore}: provenance-checked fingerprints of the carrier's own completed blocks (trusted loader only). */
    public CompletableFuture<PreparedPlan> prepareTrustedAsync(World world,List<BlockOp> ops,UUID country,List<RailReuse.Check> reused,Map<String,String> naturalBefore,Map<String,String> plantSoil,Map<String,String> ownBefore) {
        open();
        if(naturalBefore.size()>NaturalClearance.MAX_FINGERPRINTS || plantSoil.size()>NaturalClearance.MAX_PLANTINGS || ownBefore.size()>NaturalClearance.MAX_FINGERPRINTS)return CompletableFuture.failedFuture(new IllegalArgumentException("Za dużo zezwoleń preclearu/sadzenia."));
        return prepareInternalAsync(world,List.copyOf(ops),country,List.copyOf(reused),Map.copyOf(naturalBefore),Map.copyOf(plantSoil),Map.copyOf(ownBefore),true);
    }
    private CompletableFuture<PreparedPlan> prepareInternalAsync(World world,List<BlockOp> ops,UUID country,List<RailReuse.Check> reused,Map<String,String> naturalBefore,Map<String,String> plantSoil,boolean trusted) {
        return prepareInternalAsync(world,ops,country,reused,naturalBefore,plantSoil,Map.of(),trusted);
    }
    private CompletableFuture<PreparedPlan> prepareInternalAsync(World world,List<BlockOp> ops,UUID country,List<RailReuse.Check> reused,Map<String,String> naturalBefore,Map<String,String> plantSoil,Map<String,String> ownBefore,boolean trusted) {
        open();Objects.requireNonNull(world);Objects.requireNonNull(country);
        int maximum=Math.max(1,Math.min(500000,plugin.getConfig().getInt("budowa.max-blokow",250000)));
        if(ops.isEmpty() || ops.size()>maximum || !preparing.isEmpty())return CompletableFuture.failedFuture(new IllegalArgumentException("Plan pusty, ponad limit lub trwa inne przygotowanie."));
        var result=new CompletableFuture<PreparedPlan>();preparing.add(result);
        long revision=reservationRevision;UUID worldId=world.getUID();List<Job> reservedJobs=List.copyOf(jobs.values());
        int minHeight=trusted?world.getMinHeight():0,maxHeight=trusted?world.getMaxHeight():0;
        CompletableFuture.supplyAsync(()->{
            NaturalClearance.Policy policy=trusted?NaturalClearance.forPlan(ops,naturalBefore,plantSoil,ownBefore):NaturalClearance.Policy.none();
            List<NaturalClearance.Span> audit=trusted?policy.auditUnion(minHeight,maxHeight):List.of();
            Map<Point,BlockOp> unique=new LinkedHashMap<>();
            for(BlockOp op:ops)unique.put(new Point(op.x(),op.y(),op.z()),op);
            Set<Point> reserved=new HashSet<>();
            for(Job job:reservedJobs)if(job.world.equals(worldId) && !Set.of("DONE","CANCELLED").contains(job.status))
                job.entries.stream().skip(job.cursor).forEach(e->reserved.add(new Point(e.op.x(),e.op.y(),e.op.z())));
            return new PreparationInput(List.copyOf(unique.values()),reserved,policy,audit);
        },preparation).whenComplete((input,error)->{
            if(closed){result.completeExceptionally(new IllegalStateException("Works closed"));return;}
            Bukkit.getScheduler().runTask(plugin,()->{
                if(error!=null){preparing.remove(result);result.completeExceptionally(error);return;}
                new org.bukkit.scheduler.BukkitRunnable(){
                    int cursor,reuseCursor,reusePart,auditSpan,auditY=Integer.MIN_VALUE;long nanos;
                    final List<Entry> entries=new ArrayList<>();final Map<String,Integer> materials=new TreeMap<>();
                    final Map<String,BlockData> targets=new HashMap<>();
                    final Map<String,String> canonical=new HashMap<>();
                    String supportBefore;boolean supportReady;
                    String normalize(String source){return canonical.computeIfAbsent(source,value->Bukkit.createBlockData(value).getAsString());}
                    public void run() {
                        if(closed){cancel();preparing.remove(result);result.completeExceptionally(new IllegalStateException("Works closed"));return;}
                        if(preparationGates.getOrDefault(result,()->false).getAsBoolean())return;
                        long start=System.nanoTime();var budget=new NaturalClearance.Budget(System::nanoTime);
                        try {
                            while(auditSpan<input.audit.size() && budget.take()) {
                                var span=input.audit.get(auditSpan);if(auditY<span.minY())auditY=span.minY();
                                var position=new NaturalClearance.Position(span.x(),auditY,span.z());
                                String denied=input.policy.auditProblem(position,naturalSample(world,country,position),this::normalize);
                                if(denied!=null)throw new IllegalArgumentException(denied+" przy "+position.key());
                                if(++auditY>span.maxY()){auditSpan++;auditY=Integer.MIN_VALUE;}
                            }
                            while(auditSpan==input.audit.size() && cursor<input.ops.size()) {
                                BlockOp op=input.ops.get(cursor);
                                String expectedSoil=input.policy.supportBefore(op);
                                if(expectedSoil!=null && !supportReady) {
                                    if(!budget.take())break;
                                    var soil=naturalSample(world,country,NaturalClearance.Position.of(op).below());
                                    String problem=input.policy.soilProblem(op,soil,this::normalize);
                                    if(problem!=null)throw new IllegalArgumentException(problem);
                                    supportBefore=normalize(expectedSoil);supportReady=true;
                                }
                                if(!budget.take())break;
                                String denied=protection.territory(world,op.x(),op.y(),op.z(),country);
                                if(denied!=null)throw new IllegalArgumentException(denied+" przy "+op.x()+","+op.y()+","+op.z());
                                if(input.reserved.contains(new Point(op.x(),op.y(),op.z())))throw new IllegalArgumentException("Inne zlecenie zajmuje blok.");
                                String key=op.part()+":"+op.mode()+":"+op.data();BlockData target=targets.computeIfAbsent(key,k->data(op));
                                var block=world.getBlockAt(op.x(),op.y(),op.z());String before=block.getBlockData().getAsString();
                                boolean tile=block.getState() instanceof TileState;
                                String problem=input.policy.naturalProblem(op,new NaturalClearance.Sample(before,tile,false,true),this::normalize);
                                if(problem!=null)throw new IllegalArgumentException(problem);
                                String ownProblem=input.policy.ownProblem(op,before,tile,this::normalize);
                                if(ownProblem!=null)throw new IllegalArgumentException(ownProblem+" przy "+op.x()+","+op.y()+","+op.z());
                                if(tile)throw new IllegalArgumentException("Chroniony kontener lub blok funkcyjny.");
                                String soilBefore=supportBefore;cursor++;supportBefore=null;supportReady=false;
                                if(before.equals(target.getAsString()))continue;
                                if(input.policy.naturalBefore(op)==null && input.policy.ownBefore(op)==null && !WorksProtection.natural(block))throw new IllegalArgumentException("Istniejąca zabudowa przy "+op.x()+","+op.y()+","+op.z());
                                if(op.mode()==BlockOp.Mode.FILL && !WorksProtection.soft(block))continue;
                                // Persist the actual canonical live state, including recalculated leaf distance, never the source pin.
                                entries.add(new Entry(op,before,target.getAsString(),soilBefore));
                                if(!target.getMaterial().isAir())materials.merge(target.getMaterial().name(),1,Integer::sum);
                            }
                            while(auditSpan==input.audit.size() && cursor==input.ops.size() && reuseCursor<reused.size() && budget.take()) {
                                RailReuse.Check check=reused.get(reuseCursor);String denied=reuseProblemAt(world,country,check,reusePart++);
                                if(reusePart==RailReuse.inspections(check)){reusePart=0;reuseCursor++;}
                                if(denied!=null)throw new IllegalArgumentException(denied);
                            }
                            nanos+=System.nanoTime()-start;
                            if(auditSpan==input.audit.size() && cursor==input.ops.size() && reuseCursor==reused.size()) {
                                cancel();long measured=nanos;
                                CompletableFuture.supplyAsync(()->{
                                    NaturalClearance.order(entries,input.policy.preserveOrder());
                                    return new PreparedPlan(worldId,country,entries,new Preview(ops.size(),materials,List.of(),entries.size()),reused,revision);
                                },preparation).whenComplete((plan,ex)->{
                                    if(closed){result.completeExceptionally(new IllegalStateException("Works closed"));return;}
                                    Bukkit.getScheduler().runTask(plugin,()->{preparing.remove(result);preparationNanos=measured;if(ex!=null)result.completeExceptionally(ex);else if(revision!=reservationRevision)result.completeExceptionally(new IllegalArgumentException("Zmieniono rezerwacje budów. Powtórz wycenę."));else result.complete(plan);});
                                });
                            }
                        }catch(Exception ex){cancel();preparing.remove(result);result.completeExceptionally(ex);}
                    }
                }.runTaskTimer(plugin,1,1);
            });
        });
        return result;
    }
    private NaturalClearance.Sample naturalSample(World world,UUID country,NaturalClearance.Position position) {
        if(protection.territory(world,position.x(),position.y(),position.z(),country)!=null)return new NaturalClearance.Sample(null,false,false,false);
        var block=world.getBlockAt(position.x(),position.y(),position.z());
        return new NaturalClearance.Sample(block.getBlockData().getAsString(),block.getState() instanceof TileState,WorksProtection.natural(block),true);
    }
    private String workerFingerprintProblem(World world,UUID country,Entry entry,org.bukkit.block.Block block,String current) {
        boolean strict=entry.supportBefore!=null || NaturalClearance.authorizedNaturalState(entry.before);
        if((strict || !current.equals(entry.target)) && block.getState() instanceof TileState)return "Chroniony blok funkcyjny";
        String support=null;
        if(entry.supportBefore!=null) {
            var soil=naturalSample(world,country,NaturalClearance.Position.of(entry.op).below());
            if(soil.tileEntity() || !soil.permitted() || !NaturalClearance.allowedSoil(soil.before()) || !entry.supportBefore.equals(soil.before()))return "Podłoże sadzonki zmieniło się po przyjęciu";
            support=soil.before();
        }
        return NaturalClearance.workerMatches(entry,current,support)?null:"Teren zmienił się po przyjęciu";
    }
    private String reuseProblemAt(World world,UUID country,RailReuse.Check check,int index) {
        return RailReuse.problemAt(check,index,(x,y,z)->{
            if(protection.territory(world,x,y,z,country)!=null)return new RailReuse.Sample(null,false,false,false);
            var block=world.getBlockAt(x,y,z);var data=block.getBlockData();
            String shape=data instanceof org.bukkit.block.data.Rail rail?rail.getShape().name().toLowerCase(Locale.ROOT):null;
            if(data instanceof org.bukkit.block.data.Waterlogged water && water.isWaterlogged())shape=null;
            return new RailReuse.Sample(shape,block.getType().isSolid() && !(block.getState() instanceof TileState),block.isPassable() && !block.isLiquid(),true);
        });
    }
    /** Persist an immutable, once-validated plan in HELD state. No world traversal or payment here. */
    public Future<?> stage(String id,PreparedPlan plan) {
        return stage(id,plan,List.of());
    }
    public Future<?> stage(String id,PreparedPlan plan,List<TrackCell> routeCells) {
        open();if(jobs.containsKey(id) || plan.revision!=reservationRevision || !plan.preview.allowed())throw new IllegalArgumentException("Id zajęte lub nieaktualne przygotowanie.");
        jobs.put(id,new Job(id,plan.world,plan.country,plan.entries,0,plan.preview.cost,"HELD","Oczekiwanie na trwałe potwierdzenie płatności i grafu",plan.reused,true,routeCells));
        reservationRevision++;persist();return pending;
    }
    /** Queue state is persisted while the independent payment gate remains closed. */
    public Future<?> activatePaid(String id) {
        open();Job j=jobs.get(id);if(j==null || !j.financeHeld || !Set.of("HELD","DONE").contains(j.status))throw new IllegalArgumentException("Brak wstrzymanego zlecenia.");
        if(!j.status.equals("DONE"))update(j,j.cursor,"QUEUED","");
        persist();activationWrites.put(id,pending);return pending;
    }
    public void allowPaidExecution(String id) {
        open();Job j=jobs.get(id);Future<?> ack=activationWrites.get(id);
        if(j==null || !j.financeHeld || !Set.of("QUEUED","DONE").contains(j.status) || saveFailure!=null || ack==null || !ack.isDone())throw new IllegalStateException("Zlecenie nie jest trwale gotowe.");
        try{ack.get();}catch(Exception ex){throw new IllegalStateException("Zapis zlecenia nie został potwierdzony.",ex);}
        paidThisSession.add(id);
    }
    public void holdFinancial(String id,String reason) {
        open();paidThisSession.remove(id);Job job=jobs.get(id);
        if(job!=null && job.financeHeld && !Set.of("DONE","CANCELLED").contains(job.status))update(job,job.cursor,"HELD",reason);
    }
    public static boolean matchesIntent(Job job,PreparedPlan plan) {
        return job.financeHeld && job.world.equals(plan.world) && Objects.equals(job.country,plan.country)
            && job.entries.equals(plan.entries) && job.reused.equals(plan.reused) && job.cost==plan.preview.cost;
    }
    static Job afterRestart(Job job) {
        if(job.financeHeld && !Set.of("DONE","CANCELLED").contains(job.status))return new Job(job.id,job.world,job.country,job.entries,job.cursor,job.cost,"HELD","Restart: transakcja wymaga kontroli, bez ponownej opłaty",job.reused,true,job.routeCells);
        return job;
    }
    static boolean executable(Job job,Set<String> authorized){return job.status.equals("QUEUED") && (!job.financeHeld || authorized.contains(job.id));}
    static boolean executable(Job job,Set<String> authorized,java.util.function.Predicate<String> suspended){return !suspended.test(job.id) && executable(job,authorized);}
    static String completedStatus(Job job){return job.reused.isEmpty() && job.routeCells.isEmpty()?"DONE":"VERIFYING";}
    /** Packet-only view of the already prepared plan; never validates the world a second time. */
    public void showPreparedPreview(Player player,PreparedPlan plan) {
        open();clearPreview(player);if(!player.getWorld().getUID().equals(plan.world))return;
        List<Location> sent=new ArrayList<>();long deadline=System.nanoTime()+2_000_000L;
        for(int i=0;i<Math.min(128,plan.entries.size()) && System.nanoTime()<deadline;i++) {
            Entry e=plan.entries.get(i);Location at=new Location(player.getWorld(),e.op.x(),e.op.y(),e.op.z());
            if(at.distanceSquared(player.getLocation())<=64*64){player.sendBlockChange(at,Bukkit.createBlockData(e.target));sent.add(at);}
        }
        previews.put(player.getUniqueId(),sent);
        long version=previewVersions.merge(player.getUniqueId(),1L,Long::sum);
        Bukkit.getScheduler().runTaskLater(plugin,()->{if(!closed && Objects.equals(previewVersions.get(player.getUniqueId()),version))clearPreview(player);},100L);
    }
    public Result submit(String id, World world, List<BlockOp> ops) { return submit(id, world, ops, null); }
    public Result submit(String id, World world, List<BlockOp> ops, UUID country) {
        open();
        if (id == null || id.isBlank() || id.length() > 128) return new Result(false, id, 0, "Niepoprawny identyfikator");
        if (jobs.containsKey(id)) return new Result(false, id, jobs.get(id).cost, "Identyfikator już istnieje");
        if (saveFailure != null) return new Result(false, id, 0, saveFailure);
        Prepared p = prepare(world, ops, country);
        if (!p.preview.allowed()) return new Result(false, id, p.preview.cost, String.join("; ", p.preview.obstacles));
        jobs.put(id, new Job(id, world.getUID(), country, p.entries, 0, p.preview.cost, "QUEUED", ""));
        reservationRevision++;
        persist();
        return new Result(true, id, p.preview.cost, "Przyjęto; zapis i budowa w kolejce");
    }
    private Prepared prepare(World world, List<BlockOp> ops, UUID country) {
        long start = System.nanoTime();
        try { return preparePlan(world,ops,country); }
        finally { preparationNanos = System.nanoTime()-start; }
    }
    private Prepared preparePlan(World world, List<BlockOp> ops, UUID country) {
        Objects.requireNonNull(world); Objects.requireNonNull(ops);
        int maximum = Math.max(1,Math.min(500_000,plugin.getConfig().getInt("budowa.max-blokow",250_000)));
        if (ops.isEmpty() || ops.size() > maximum) return new Prepared(List.of(), new Preview(ops.size(), Map.of(), List.of("Plan pusty lub ponad limit " + maximum + " operacji"), 0));
        // Last operation wins at a coordinate; clear/fill intermediates must not destroy the final structure.
        Map<Point, BlockOp> finalOps = new LinkedHashMap<>();
        for (BlockOp op : ops) { Objects.requireNonNull(op); finalOps.put(new Point(op.x(), op.y(), op.z()), op); }
        List<Entry> entries = new ArrayList<>(); List<String> obstacles = new ArrayList<>();
        Map<String, Integer> materials = new TreeMap<>(); long cost = ops.size();
        Set<Point> reservedPoints = new HashSet<>();
        for (Job j : jobs.values()) if (!j.status.equals("DONE") && !j.status.equals("CANCELLED") && j.world.equals(world.getUID()))
            j.entries.stream().skip(j.cursor).forEach(e -> reservedPoints.add(new Point(e.op.x(), e.op.y(), e.op.z())));
        for (BlockOp op : finalOps.values()) {
            String denied = protection.territory(world, op.x(), op.y(), op.z(), country);
            if (denied != null) { obstacle(obstacles, op, denied); continue; }
            var block = world.getBlockAt(op.x(), op.y(), op.z());
            BlockData target;
            try { target = data(op); } catch (RuntimeException ex) { obstacle(obstacles, op, "Niepoprawny blok"); continue; }
            String before = block.getBlockData().getAsString();
            if (block.getState() instanceof TileState) { obstacle(obstacles, op, "Chroniony kontener lub blok funkcyjny"); continue; }
            if (before.equals(target.getAsString())) continue;
            if (!WorksProtection.natural(block)) { obstacle(obstacles, op, "Istniejąca zabudowa"); continue; }
            if (op.mode() == BlockOp.Mode.FILL && !WorksProtection.soft(block)) continue;
            boolean reserved = reservedPoints.contains(new Point(op.x(), op.y(), op.z()));
            if (reserved) { obstacle(obstacles, op, "Inne zlecenie zajmuje blok"); continue; }
            entries.add(new Entry(op, before, target.getAsString()));
            if (!target.getMaterial().isAir()) materials.merge(target.getMaterial().name(), 1, Integer::sum);
        }
        // Foundations first, then dependent blocks; the caller's order is retained within each layer.
        entries.sort(Comparator.comparingInt((Entry e) -> e.op.y()).thenComparingInt(e -> e.target.contains(":rail") || e.target.contains(":lantern") ? 1 : 0));
        return new Prepared(List.copyOf(entries), new Preview(cost, materials, obstacles, entries.size()));
    }
    private static void obstacle(List<String> list, BlockOp op, String reason) {
        if (list.size() < 32) list.add(op.x() + "," + op.y() + "," + op.z() + ": " + reason);
    }
    private static BlockData data(BlockOp op) {
        if (op.mode() == BlockOp.Mode.CLEAR) return Bukkit.createBlockData(Material.AIR);
        String material = switch (op.part()) {
            case AIR -> "air"; case BED -> "gravel"; case RAIL -> "rail"; case FILL -> "dirt";
            case BRIDGE_DECK, PLATFORM -> "stone_bricks"; case BRIDGE_PILLAR, TUNNEL_WALL, TUNNEL_CEILING -> "stone_bricks";
            case BRIDGE_RAILING -> "oak_fence"; case ROAD -> "dirt_path"; case LAMP_POST, SIGNAL_POST -> "oak_fence";
            case LAMP, TUNNEL_LIGHT -> "lantern"; case SIGNAL_HEAD -> "redstone_lamp"; case RAW -> Objects.requireNonNull(op.data());
        };
        return Bukkit.createBlockData(material);
    }
    public void tick() {
        open();
        preparationGates.keySet().removeIf(CompletableFuture::isDone);
        ++clock;
        if (!saved()) return;
        // Progress (cursor) is coalesced in the background; workers never wait for it. A crash replays at most the
        // last interval: exact targets are accepted unchanged by workerFingerprintProblem.
        if (dirty && System.nanoTime() - lastSave >= progressIntervalNanos()) persist();
        // A queued paid job is not executable after restart until its independent financial evidence is reconciled.
        for(Job j:List.copyOf(jobs.values()))if(!suspended.test(j.id) && j.status.equals("VERIFYING") && (!j.financeHeld || paidThisSession.contains(j.id))) {
            World world=Bukkit.getWorld(j.world);if(world==null)return;
            int[] cursor=reuseCursors.computeIfAbsent(j.id,key->new int[2]);long deadline=System.nanoTime()+2_000_000L;
            int checks=j.routeCells.isEmpty()?j.reused.size():j.routeCells.size();
            for(int inspected=0;inspected<128 && System.nanoTime()<deadline && cursor[0]<checks;inspected++) {
                RailReuse.Check check=completionChecks.computeIfAbsent(j.id,key->j.routeCells.isEmpty()?j.reused.get(cursor[0]):RailReuse.expected(j.routeCells,cursor[0]));
                String denied=reuseProblemAt(world,j.country,check,cursor[1]++);
                if(denied!=null){reuseCursors.remove(j.id);completionChecks.remove(j.id);update(j,j.cursor,"BLOCKED",denied);return;}
                if(cursor[1]==RailReuse.inspections(check)){cursor[1]=0;cursor[0]++;completionChecks.remove(j.id);}
            }
            if(cursor[0]==checks){reuseCursors.remove(j.id);completionChecks.remove(j.id);update(j,j.cursor,"DONE","");}return;
        }
        if (completion != null) for (Job done : List.copyOf(jobs.values())) {
            if (!suspended.test(done.id) && done.status.equals("DONE") && (!done.financeHeld || paidThisSession.contains(done.id)) && !notified.contains(done.id) && durablyDone(done.id)) {
                try { completion.accept(done); notified.add(done.id); }
                catch (RuntimeException ex) { plugin.getLogger().warning("Błąd odbiorcy zakończenia " + done.id + ": " + ex); }
            }
        }
        if (clock % 20 == 0) for (Job blocked : List.copyOf(jobs.values())) {
            if(suspended.test(blocked.id))continue;
            if (blocked.financeHeld && !paidThisSession.contains(blocked.id) || !blocked.status.equals("BLOCKED") || !blocked.reason.equals("Teren zmienił się po przyjęciu")
                || blocked.cursor >= blocked.entries.size()) continue;
            World world = Bukkit.getWorld(blocked.world); Entry next = blocked.entries.get(blocked.cursor);
            if (world != null && world.isChunkLoaded(next.op.x() >> 4, next.op.z() >> 4) && protection.territory(world,next.op.x(),next.op.y(),next.op.z(),blocked.country)==null) {
                var block=world.getBlockAt(next.op.x(),next.op.y(),next.op.z());
                if(workerFingerprintProblem(world,blocked.country,next,block,block.getBlockData().getAsString())==null)update(blocked,blocked.cursor,"QUEUED","");
            }
        }
        List<JobOrder.Item> order = jobs.values().stream().map(j -> new JobOrder.Item(j.id, 0, false, false, false,
            j.entries.isEmpty() ? 0 : 1.0 - (double) j.cursor / j.entries.size(), 0, !executable(j,paidThisSession,suspended))).toList();
        for (String id : JobOrder.active(order, jobs.size())) {
            Job j = jobs.get(id);
            World world = Bukkit.getWorld(j.world);
            if (world == null) continue;
            if (j.cursor >= j.entries.size()) { update(j, j.cursor, completedStatus(j), ""); return; }
            int budget = Math.max(1, Math.min(256, plugin.getConfig().getInt("budowa.bloki-na-tick", 8)));
            long deadline = System.nanoTime() + 4_000_000L;
            boolean progressed = false;
            for (int placed = 0; placed < budget && j.cursor < j.entries.size(); placed++) {
                if (placed > 0 && System.nanoTime() >= deadline) break;
                Entry e = j.entries.get(j.cursor); BlockOp op = e.op;
                if (!world.isChunkLoaded(op.x() >> 4, op.z() >> 4)) { removeWorker(id); break; }
                String denied = protection.territory(world, op.x(), op.y(), op.z(), j.country);
                var block = world.getBlockAt(op.x(), op.y(), op.z());
                String current = block.getBlockData().getAsString();
                // An exact replay target is never modified; trusted entries still recheck soil/TileState.
                if (denied == null) denied=workerFingerprintProblem(world,j.country,e,block,current);
                if (denied != null) { update(j, j.cursor, "BLOCKED", denied); return; }
                if (!current.equals(e.target)) {
                    var area=new org.bukkit.util.BoundingBox(op.x()+.01,op.y()+.01,op.z()+.01,op.x()+.99,op.y()+1.99,op.z()+.99);
                    boolean occupied=world.getNearbyEntities(area).stream().anyMatch(entity->entity instanceof LivingEntity
                        && !entity.getPersistentDataContainer().has(marker));
                    if(occupied)break; // Resume automatically once the player/animal leaves the next work cell.
                }
                if (placed == 0) worker(j, world, op);
                if (!current.equals(e.target)) {
                    block.setBlockData(Bukkit.createBlockData(e.target), false);
                    AuditMutationWatch.afterMutation(block);
                }
                update(j, j.cursor + 1, j.cursor + 1 == j.entries.size() ? completedStatus(j) : "QUEUED", "");
                j = jobs.get(id); progressed = true;
                if (!j.status.equals("QUEUED")) break;
            }
            if (progressed) return;
        }
    }
    /** False only after a failed write (sticky, as before): the works stop until the cause is fixed and restarted. */
    private boolean saved() {
        if (saveFailure != null) return false;
        var error = store.failure();
        if (error.isEmpty()) return true;
        saveFailure = "Błąd zapisu jobs; budowa wstrzymana"; plugin.getLogger().severe(saveFailure + ": " + error.get());
        return false;
    }
    private long progressIntervalNanos() {
        long ms = Math.max(1000L, Math.min(60_000L, plugin.getConfig().getLong("budowa.zapis-postepu-ms", 5000L)));
        return TimeUnit.MILLISECONDS.toNanos(Math.max(ms, 4 * store.lastWriteMillis()));
    }
    private void persist() { pending = store.save(snapshot()); lastSeq = store.submittedSeq(); dirty = false; lastSave = System.nanoTime(); }
    private void update(Job j, int cursor, String status, String reason) {
        jobs.put(j.id, new Job(j.id, j.world, j.country, j.entries, cursor, j.cost, status, reason,j.reused,j.financeHeld,j.routeCells));
        if (!status.equals("QUEUED")) removeWorker(j.id);
        dirty = true;
        if (!status.equals("QUEUED")) { persist(); if (status.equals("DONE")) doneSeq.put(j.id, lastSeq); }
    }
    public boolean resume(String id) { open(); Job j = jobs.get(id); if (j == null || !j.status.equals("BLOCKED") || j.financeHeld && !paidThisSession.contains(id)) return false; reuseCursors.remove(id);completionChecks.remove(id);update(j, j.cursor, j.cursor==j.entries.size()?completedStatus(j):"QUEUED", ""); return true; }
    public boolean cancel(String id) { open(); Job j = jobs.get(id); if (j == null || j.status.equals("DONE")) return false; update(j, j.cursor, "CANCELLED", "Anulowano bez cofania bloków"); return true; }
    public Snapshot snapshot() { main(); return new Snapshot(1, List.copyOf(jobs.values())); }
    public void restore(Snapshot snapshot) {
        main(); if (closed) throw new IllegalStateException("Works closed");
        validateSnapshot(snapshot);
        Map<String, Job> checked = new LinkedHashMap<>();
        for (Job j : snapshot.jobs) {
            if (j.id == null || j.world == null || j.cursor < 0 || j.cursor > j.entries.size() || checked.put(j.id, j) != null)
                throw new IllegalArgumentException("Invalid job");
            checked.put(j.id,afterRestart(j));
        }
        workers.values().forEach(Entity::remove); workers.clear(); notified.clear(); paidThisSession.clear();activationWrites.clear();reuseCursors.clear();completionChecks.clear();doneSeq.clear();jobs.clear(); jobs.putAll(checked);reservationRevision++;
        persist();
    }
    static void validateSnapshot(Snapshot snapshot) {
        if (snapshot == null || snapshot.version != 1 || snapshot.jobs == null) throw new IllegalArgumentException("Snapshot version");
        Set<String> ids = new HashSet<>();
        for (Job j : snapshot.jobs) {
            if (j == null || j.id == null || j.id.isBlank() || !ids.add(j.id) || j.world == null || j.entries == null
                    || j.cursor < 0 || j.cursor > j.entries.size() || j.cost < 0 || j.reason == null
                    || !Set.of("QUEUED","BLOCKED","DONE","CANCELLED","HELD","VERIFYING").contains(j.status)) throw new IllegalArgumentException("Invalid job");
            for (Entry e : j.entries) if (e == null || e.op == null || e.before == null || e.target == null
                    || e.op.part() == null || e.op.mode() == null || e.supportBefore!=null && (!NaturalClearance.sapling(e.op) || !NaturalClearance.allowedSoil(e.supportBefore))) throw new IllegalArgumentException("Invalid job entry");
        }
    }
    private void worker(Job job, World world, BlockOp op) {
        Mannequin npc = workers.get(job.id);
        Location location = standingPoint(job, world, op);
        if (location == null) { removeWorker(job.id); return; }
        if (npc == null || !npc.isValid()) {
            npc = world.spawn(location, Mannequin.class, entity -> {
                entity.getPersistentDataContainer().set(marker, PersistentDataType.STRING, job.id);
                entity.setImmovable(true); entity.setInvulnerable(true); entity.setGravity(false); entity.setPersistent(false);
                entity.customName(net.kyori.adventure.text.Component.text("Budowniczy kolei"));
                entity.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE));
            }); workers.put(job.id, npc);
        } else npc.teleport(location);
        npc.swingMainHand();
    }
    private Location standingPoint(Job job, World world, BlockOp op) {
        for (int[] offset : new int[][] {{2,0},{-2,0},{0,2},{0,-2},{3,3},{-3,-3}}) {
            int x = op.x() + offset[0], z = op.z() + offset[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = Math.min(world.getMaxHeight() - 3, op.y() + 3); y >= Math.max(world.getMinHeight(), op.y() - 24); y--) {
                if (world.getBlockAt(x,y,z).getType().isSolid() && world.getBlockAt(x,y+1,z).isPassable()
                        && world.getBlockAt(x,y+2,z).isPassable() && protection.territory(world,x,y+1,z,job.country) == null)
                    return new Location(world, x + .5, y + 1, z + .5);
            }
        }
        return null;
    }
    private void removeWorker(String id) { Entity entity = workers.remove(id); if (entity != null) entity.remove(); }
    private void cleanup(Collection<Entity> entities) {
        for (Entity entity : entities) if (entity.getPersistentDataContainer().has(marker, PersistentDataType.STRING)) entity.remove();
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent event) { cleanup(Arrays.asList(event.getChunk().getEntities())); }
    @Override public void close() {
        main(); if (closed) return;
        for (UUID id : List.copyOf(previews.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) clearPreview(p); }
        workers.values().forEach(Entity::remove); workers.clear();
        persist(); closed = true;for(var future:preparing)future.completeExceptionally(new IllegalStateException("Works closed"));preparing.clear();preparation.shutdownNow(); HandlerList.unregisterAll(this);
        // Wait for the final snapshot: the writer is a daemon thread and must not be cut off by JVM exit.
        if (!store.close(60, TimeUnit.SECONDS)) plugin.getLogger().severe("Ostatni zapis jobs.json nie został potwierdzony przy wyłączaniu; po starcie robotnicy powtórzą najwyżej ostatnie bloki.");
    }
}
