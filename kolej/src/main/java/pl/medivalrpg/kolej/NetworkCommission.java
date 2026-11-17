// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import org.bukkit.*;
import pl.medivalrpg.kolej.build.*;
import pl.medivalrpg.kolej.data.RailData;

/** Trusted local, hash-pinned operation batches. No money is created and no graph connection is inferred. */
final class NetworkCommission implements AutoCloseable {
    /** ownFile: provenance-bound fingerprints of the carrier's own completed blocks (rebuild of its own track bed). */
    record Part(String id,String file,String sha256,long maxCost,String naturalFile,String naturalSha,String soilFile,String soilSha,String ownFile,String ownSha) {
        Part(String id,String file,String sha256,long maxCost){this(id,file,sha256,maxCost,null,null,null,null,null,null);}
        Part(String id,String file,String sha256,long maxCost,String naturalFile,String naturalSha,String soilFile,String soilSha){this(id,file,sha256,maxCost,naturalFile,naturalSha,soilFile,soilSha,null,null);}
    }
    record Own(Map<String,String> before,String job) { static final Own NONE=new Own(Map.of(),null); }
    private record Loaded(List<BlockOp> ops,Map<String,String> naturalBefore,Map<String,String> plantSoil,Own own) {}
    record Request(int version,boolean enabled,UUID manager,UUID country,String world,long maxTotalMd,List<Part> parts,boolean fundFromWorldReserve,boolean requireStartReady) {
        Request(int version,boolean enabled,UUID manager,UUID country,String world,long maxTotalMd,List<Part> parts){this(version,enabled,manager,country,world,maxTotalMd,parts,false);}
        Request(int version,boolean enabled,UUID manager,UUID country,String world,long maxTotalMd,List<Part> parts,boolean fundFromWorldReserve){this(version,enabled,manager,country,world,maxTotalMd,parts,fundFromWorldReserve,false);}
    }
    private final KolejPlugin plugin;
    private final RailwayProjects projects;
    private final Path folder;
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Kolej-Network-Import");t.setDaemon(true);return t;});
    private Request request;
    private NetworkGraphCommission graph;
    private int index;
    private String status="Brak zlecenia";
    private boolean failed,closed;
    private boolean preparationFailed;
    private int preparationRetries;
    private CompletableFuture<Void> sourceRegistered;
    private CompletableFuture<Loaded> reading;
    private Map<String,String> naturalBefore=Map.of(),plantSoil=Map.of();
    private Own own=Own.NONE;
    private boolean provenanceChecked;
    private List<BlockOp> ops;
    private final List<Chunk> tickets=new ArrayList<>();
    private final List<CompletableFuture<Chunk>> loading=new ArrayList<>();
    private List<Long> keys;
    private int chunkIndex;
    private CompletableFuture<RailwayWorks.PreparedPlan> preparation;
    private CompletableFuture<String> accepted;
    private CompletableFuture<?> funding;
    private boolean reserveReady,refunded;
    private String requestSha;
    private boolean startReady;
    private final NetworkPause pause;
    private CompletableFuture<Void> controlWrite;
    private boolean resuming;
    private String controlError;
    private long ticks;
    NetworkCommission(KolejPlugin plugin,RailwayProjects projects) {
        this.plugin=plugin;this.projects=projects;folder=plugin.getDataFolder().toPath().resolve("network-commission");
        pause=new NetworkPause(folder);
        projects.suspendNetwork(this::suspendedJob);
        Path source=folder.resolve("request.json");
        if(!Files.exists(source))return;
        try {byte[] raw=Files.readAllBytes(source);requestSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));request=new Gson().fromJson(new String(raw,java.nio.charset.StandardCharsets.UTF_8),Request.class);validate(request);if(!request.enabled())request=null;else graph=new NetworkGraphCommission(plugin,projects,folder,request);}
        catch(Exception e){fail(e);}
    }
    static void validate(Request r) {
        if(r==null || r.version()!=1 || r.manager()==null || r.country()==null || r.world()==null || r.world().isBlank() || r.maxTotalMd()<1 || r.parts()==null || r.parts().isEmpty() || r.parts().size()>64)throw new IllegalArgumentException("Nieprawidłowy import sieci");
        Set<String> ids=new HashSet<>(),files=new HashSet<>();
        for(var p:r.parts())if(p==null || !RailData.identifier(p.id()) || !ids.add(p.id()) || p.file()==null || !p.file().matches("[a-zA-Z0-9_-]+[.]jsonl[.]gz") || !files.add(p.file()) || p.sha256()==null || !p.sha256().matches("[a-f0-9]{64}") || p.maxCost()<1 || p.maxCost()>r.maxTotalMd())throw new IllegalArgumentException("Nieprawidłowa część importu");
        for(var p:r.parts()){validateEvidencePair(p.naturalFile(),p.naturalSha());validateEvidencePair(p.soilFile(),p.soilSha());validateEvidencePair(p.ownFile(),p.ownSha());
            if(p.naturalFile()!=null && p.soilFile()!=null)throw new IllegalArgumentException("Rozdziel wycinkę i nasadzenia");
            if(p.ownFile()!=null && (p.naturalFile()!=null || p.soilFile()!=null))throw new IllegalArgumentException("Przebudowa własnej konstrukcji to osobna część");}
    }
    static List<BlockOp> decode(Path source,String expected,int maxOps) throws Exception {
        if(Files.size(source)>8_000_000)throw new IOException("Skompresowany plan za duży");
        byte[] bytes=Files.readAllBytes(source);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("Plan zmienił się po weryfikacji");
        List<BlockOp> out=new ArrayList<>();Gson gson=new Gson();long chars=0;
        try(var lines=new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes)),java.nio.charset.StandardCharsets.UTF_8))) {
            String line;while((line=lines.readLine())!=null) {
                chars+=line.length();if(chars>64_000_000 || line.length()>2048 || out.size()>=maxOps)throw new IOException("Przekroczono limit planu");
                var op=gson.fromJson(line,BlockOp.class);
                if(op==null || op.part()==null || op.mode()==null || Math.abs((long)op.x())>30_000_000 || Math.abs((long)op.z())>30_000_000 || op.y()<-2048 || op.y()>2048 || op.data()!=null && op.data().length()>512)throw new IOException("Nieprawidłowa operacja");
                out.add(op);
            }
        }
        if(out.isEmpty())throw new IOException("Pusty plan");return List.copyOf(out);
    }
    private static void validateEvidencePair(String file,String sha){
        if(file==null && sha==null)return;
        if(file==null || !file.matches("[a-zA-Z0-9_-]+[.]jsonl[.]gz") || sha==null || !sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Nieprawidłowy fingerprint planu");
    }
    static Map<String,String> decodeEvidence(Path file,String expected,boolean soil,List<BlockOp> ops) throws Exception {
        if(Files.size(file)>8_000_000)throw new IOException("Fingerprints za duże");byte[] bytes=Files.readAllBytes(file);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("Zmieniono fingerprinty planu");
        Set<String> needed=new HashSet<>();for(var op:ops)needed.add(op.x()+":"+op.y()+":"+op.z());Map<String,String> found=new HashMap<>();int count=0;long chars=0;
        try(var lines=new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes)),java.nio.charset.StandardCharsets.UTF_8))){
            String line;while((line=lines.readLine())!=null){
                chars+=line.length();if(++count>8192 || chars>64_000_000 || line.length()>8192)throw new IOException("Przekroczono limit fingerprintów");
                var row=com.google.gson.JsonParser.parseString(line).getAsJsonObject();var pos=row.getAsJsonArray("xyz");if(pos==null || pos.size()!=3)throw new IOException("Brak współrzędnych fingerprintu");
                String key=pos.get(0).getAsInt()+":"+pos.get(1).getAsInt()+":"+pos.get(2).getAsInt();if(!needed.contains(key))continue;
                String field=soil?"soilBlockData":"beforeBlockData",hash=soil?"soilSha256":"beforeSha256";
                if(!row.has(field) || !row.has(hash) || row.has("expectedTileEntity") && row.get("expectedTileEntity").getAsBoolean())throw new IOException("Nieprawidłowy dowód naturalnego bloku");
                String data=row.get(field).getAsString();if(data.length()>512 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))).equals(row.get(hash).getAsString()) || found.putIfAbsent(key,data)!=null)throw new IOException("Niespójny fingerprint bloku");
            }
        }
        if(!found.keySet().equals(needed))throw new IOException("Nie wszystkie operacje mają fingerprint");return Map.copyOf(found);
    }
    /** Own evidence: every record must match an operation of this part and name ONE completed job as provenance. */
    static Own decodeOwn(Path file,String expected,List<BlockOp> ops) throws Exception {
        if(Files.size(file)>8_000_000)throw new IOException("Odciski własnej konstrukcji za duże");byte[] bytes=Files.readAllBytes(file);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("Zmieniono odciski własnej konstrukcji");
        Set<String> keys=new HashSet<>();for(var op:ops)keys.add(op.x()+":"+op.y()+":"+op.z());Map<String,String> found=new HashMap<>();String job=null;int count=0;
        try(var lines=new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes)),java.nio.charset.StandardCharsets.UTF_8))){
            String line;while((line=lines.readLine())!=null){
                if(line.isBlank())continue;
                if(++count>8192 || line.length()>2048)throw new IOException("Przekroczono limit odcisków własnej konstrukcji");
                var row=com.google.gson.JsonParser.parseString(line).getAsJsonObject();var pos=row.getAsJsonArray("xyz");if(pos==null || pos.size()!=3)throw new IOException("Brak współrzędnych odcisku");
                String key=pos.get(0).getAsInt()+":"+pos.get(1).getAsInt()+":"+pos.get(2).getAsInt();
                String data=row.get("beforeBlockData").getAsString(),provenance=row.get("provenanceJob").getAsString();
                if(!keys.contains(key) || data.length()>512 || !RailData.identifier(provenance) || job!=null && !job.equals(provenance)
                    || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))).equals(row.get("beforeSha256").getAsString())
                    || found.putIfAbsent(key,data)!=null)throw new IOException("Niespójny odcisk własnej konstrukcji");
                job=provenance;
            }
        }
        if(found.isEmpty())throw new IOException("Pusty dowód własnej konstrukcji");
        return new Own(Map.copyOf(found),job);
    }
    /** Main thread: each own block must be the exact target of the named DONE job at that coordinate. */
    static String provenanceProblem(Own own,Optional<RailwayWorks.Job> job,java.util.function.UnaryOperator<String> canonical) {
        if(own.before().isEmpty())return null;
        if(job.isEmpty() || !job.get().status().equals("DONE"))return "Brak ukończonego zlecenia "+own.job()+" — przebudowa własnej konstrukcji niemożliwa";
        Map<String,String> targets=new HashMap<>();
        for(var e:job.get().entries())targets.put(e.op().x()+":"+e.op().y()+":"+e.op().z(),e.target());
        for(var entry:own.before().entrySet()){
            String target=targets.get(entry.getKey());
            if(target==null || !target.equals(canonical.apply(entry.getValue())))return "Blok "+entry.getKey()+" nie pochodzi z zlecenia "+own.job();
        }
        return null;
    }
    boolean suspendedJob(String id){return request!=null && request.parts().stream().anyMatch(p->p.id().equals(id)) && suspended();}
    private boolean suspended(){return controlWrite!=null || controlError!=null || pause.paused() || request!=null && request.requireStartReady() && !startReady;}
    private void pollControl() {
        if(controlWrite==null || !controlWrite.isDone())return;
        try{controlWrite.join();if(resuming)startReady=true;}
        catch(CompletionException e){controlError="BŁĄD zapisu sterowania siecią: "+problem(e)+" Prace zatrzymane w pamięci; trwałość ostatniej zmiany niepotwierdzona. Ponów komendę po sprawdzeniu dysku.";plugin.getLogger().log(java.util.logging.Level.SEVERE,controlError,e);}
        finally{controlWrite=null;}
    }
    String status(){pollControl();return controlError!=null?controlError:controlWrite!=null?(resuming?"Wznawianie":"PAUZA")+" — zapis sterowania w toku; roboty stoją. Ostatni stan: "+status:pause.paused()?(projects.networkPauseSettled()?"PAUZA — budowa sieci wstrzymana przez zarządcę (zapisana). /kolej siec wznow. Ostatni stan: ":"PAUZA — zapisana; trwa domknięcie wcześniejszego zapisu transakcji. Roboty stoją. Ostatni stan: ")+status:
        request!=null && request.requireStartReady() && !startReady?"Oczekiwanie na zgodę rozpoczęcia robót — /kolej siec wznow. "+status:status;}
    String pauseNetwork() {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        pollControl();if(controlWrite!=null)throw new IllegalStateException("Trwa zapis sterowania; sprawdź /kolej siec status.");
        pause.stopInMemory();resuming=false;controlError=null;
        controlWrite=CompletableFuture.runAsync(()->{try{pause.pause();}catch(IOException e){throw new CompletionException(e);}},reader);
        return "PAUZA — roboty zatrzymane; trwa zapis pauzy. Potwierdzenie: /kolej siec status. Wznowienie: /kolej siec wznow.";
    }
    String resumeNetwork() {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        if(closed || request==null)throw new IllegalStateException("Brak poprawnego zlecenia sieci do wznowienia.");
        pollControl();if(controlWrite!=null)throw new IllegalStateException("Trwa zapis sterowania; sprawdź /kolej siec status.");
        resuming=true;controlError=null;
        controlWrite=CompletableFuture.runAsync(()->{try {
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(folder.resolve("request.json"))));
            if(!actual.equals(requestSha))throw new IOException("Zmieniono plan od uruchomienia pluginu.");
            if(Files.exists(folder.resolve("accepted-request.sha256")))verifySource(folder.resolve("accepted-request.sha256"),request);
            pause.resume(requestSha);
        }catch(Exception e){throw new CompletionException(e);}},reader);
        return "Wznawianie — trwa zapis zgody startu i zdjęcia pauzy. Sprawdź /kolej siec status; istniejące błędy i blokady finansowe nadal obowiązują.";
    }
    /** Read-only progress for the manager: DONE parts, paid work (md) and the approved limit. Never mutates state. */
    record Progress(int done,int total,long paid,long limit,String current,boolean accepted) {
        String describe(){return "Części ukończone "+done+"/"+total+", opłacone roboty "+paid+" md z limitu "+limit+" md"+(current==null?"":", bieżąca: "+current)+(accepted?", sieć odebrana":"");}
    }
    Optional<Progress> progress() {
        if(request==null)return Optional.empty();
        int done=0;long paid=0;String current=null;
        for(var p:request.parts()){
            var job=projects.works().job(p.id());
            if(job.isPresent() && job.get().status().equals("DONE"))done++;else if(current==null)current=p.id()+(job.isPresent()?" "+job.get().cursor()+"/"+job.get().entries().size():"");
            var intent=projects.constructionIntent(p.id());if(intent.isPresent())paid=Math.addExact(paid,intent.get().price());
        }
        return Optional.of(new Progress(done,request.parts().size(),paid,request.maxTotalMd(),current,accepted()));
    }
    /** Physical acceptance of the graph finished in this run (published BUILT segments were saved durably). */
    boolean accepted(){return graph!=null && graph.finished();}
    record Budget(String account,long maxTotalMd,long usedByWork) {}
    /** Approved reserve budget of the active request; NPC consent fees paid from it count against the same limit. */
    Optional<Budget> feeBudget() {
        if(closed || request==null || !request.fundFromWorldReserve())return Optional.empty();
        long used=0;for(var p:request.parts()){var intent=projects.constructionIntent(p.id());if(intent.isPresent())used=Math.addExact(used,intent.get().price());}
        return Optional.of(new Budget(budgetAccount(request),request.maxTotalMd(),used));
    }
    /** Only a failed prepayment preparation may be retried, at most three times per part. */
    static void checkPreparationRetry(boolean preparationFailed,int retries,boolean paymentStarted) {
        if(!preparationFailed || retries<0 || retries>=3 || paymentStarted)
            throw new IllegalStateException("Ponowienie dostępne tylko po błędzie wyceny przed płatnością, najwyżej 3 razy na część.");
    }
    String retryPreparation() {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Main thread required");
        if(suspended())throw new IllegalStateException("Najpierw /kolej siec wznow; pauza nie ponawia błędnej wyceny.");
        if(closed || !failed || request==null || index>=request.parts().size())throw new IllegalStateException("Brak wstrzymanej wyceny do ponowienia.");
        String id=request.parts().get(index).id();
        checkPreparationRetry(preparationFailed,preparationRetries,accepted!=null || funding!=null
            || projects.works().job(id).isPresent() || projects.constructionIntent(id).isPresent());
        preparationRetries++;
        // Keep the accepted request and budget identity. Re-read all pinned inputs; never recreate the registry on retry.
        reset();
        sourceRegistered=CompletableFuture.runAsync(()->{try{verifySource(folder.resolve("accepted-request.sha256"),request);}catch(Exception e){throw new CompletionException(e);}},reader);
        failed=false;
        status="Ponowienie wyceny "+id+" ("+preparationRetries+"/3)";
        return status;
    }
    void tick() {
        pollControl();
        if(closed || request==null || controlWrite!=null || controlError!=null || pause.paused())return;
        try {
            if(!startReady){
                if(request.requireStartReady() && !startReady(folder.resolve("start.ready"),requestSha)){status="Oczekiwanie na kontrolę wdrożenia przed rozpoczęciem robót";return;}
                startReady=true;
            }
            if(failed || ++ticks%2!=0)return;
            if(!plugin.railwayManager(request.manager()) || !Objects.equals(plugin.data().ownerCountry,request.country()))throw new IllegalStateException("Zmieniono zarządcę lub przypisanie firmy");
            if(sourceRegistered==null){
                Path registry=folder.resolve("accepted-request.sha256");
                if(!Files.exists(registry) && request.parts().stream().anyMatch(p->projects.works().job(p.id()).isPresent() || projects.constructionIntent(p.id()).isPresent()))throw new IllegalStateException("Brak zapisu źródła istniejących robót — wymagana kontrola");
                sourceRegistered=CompletableFuture.runAsync(()->{try{registerSource(registry,request);}catch(Exception e){throw new CompletionException(e);}},reader);return;
            }
            if(!sourceRegistered.isDone())return;sourceRegistered.join();
            if(index>=request.parts().size()){
                release();
                if(funding!=null){if(!funding.isDone())return;funding.join();funding=null;}
                if(request.fundFromWorldReserve() && !refunded){long unused=plugin.economy().balance(budgetAccount(request));refunded=true;if(unused>0){funding=bankCall("world.return-rail-budget",budgetAccount(request),unused);status="Zwrot niewykorzystanego budżetu do rezerwy";return;}}
                graph.tick();status=graph.status();return;
            }
            Part part=request.parts().get(index);World world=Bukkit.getWorld(request.world());if(world==null)throw new IllegalStateException("Brak świata importu");
            var job=projects.works().job(part.id());
            if(job.isPresent() && job.get().status().equals("DONE")){if(!projects.works().durablyDone(part.id())){status="Zapis ukończenia "+part.id();return;}index++;reset();preparationRetries=0;return;}
            if(job.isPresent() && Set.of("CANCELLED","BLOCKED").contains(job.get().status())){status="Zatrzymane: "+part.id()+" · "+job.get().reason();reset();return;}
            if(ops==null) {
                if(reading==null){status="Odczyt planu "+part.id();int readLimit=Math.min(200000,plugin.getConfig().getInt("budowa.max-blokow",200000));reading=CompletableFuture.supplyAsync(()->{try{var decoded=decode(folder.resolve(part.file()),part.sha256(),readLimit);
                    return new Loaded(decoded,part.naturalFile()==null?Map.of():decodeEvidence(folder.resolve(part.naturalFile()),part.naturalSha(),false,decoded),part.soilFile()==null?Map.of():decodeEvidence(folder.resolve(part.soilFile()),part.soilSha(),true,decoded),
                        part.ownFile()==null?Own.NONE:decodeOwn(folder.resolve(part.ownFile()),part.ownSha(),decoded));}catch(Exception e){throw new CompletionException(e);}},reader);return;}
                if(!reading.isDone())return;var loaded=reading.join();ops=loaded.ops();naturalBefore=loaded.naturalBefore();plantSoil=loaded.plantSoil();own=loaded.own();provenanceChecked=false;TreeSet<Long> chunks=new TreeSet<>();
                int radius=naturalBefore.isEmpty()?0:8;
                for(var op:ops)for(int cx=(op.x()-radius)>>4;cx<=(op.x()+radius)>>4;cx++)for(int cz=(op.z()-radius)>>4;cz<=(op.z()+radius)>>4;cz++)chunks.add(((long)cx<<32)|(cz&0xffffffffL));
                if(chunks.size()>128)throw new IllegalArgumentException("Podziel plan na części obejmujące najwyżej 128 chunków");keys=List.copyOf(chunks);
            }
            for(var it=loading.iterator();it.hasNext();) {var future=it.next();if(!future.isDone())continue;Chunk chunk=future.join();it.remove();if(chunk==null)throw new IllegalStateException("Niewygenerowany chunk — import nie generuje świata");chunk.addPluginChunkTicket(plugin);tickets.add(chunk);}
            while(loading.size()<8 && chunkIndex<keys.size()){long key=keys.get(chunkIndex++);loading.add(world.getChunkAtAsync((int)(key>>32),(int)key,false));}
            if(chunkIndex<keys.size() || !loading.isEmpty())return;
            if(job.isPresent()){status="Budowa "+part.id()+" "+job.get().cursor()+"/"+job.get().entries().size();return;}
            if(accepted!=null){if(accepted.isDone()){accepted.join();status="Robotnicy przyjęli "+part.id();}return;}
            if(projects.constructionIntent(part.id()).isPresent()){status="Oczekiwanie na odzyskanie transakcji "+part.id()+"; bez ponownego obciążania";return;}
            if(preparation==null){
                if(!provenanceChecked){
                    String problem=provenanceProblem(own,own.job()==null?Optional.empty():projects.works().job(own.job()),data->Bukkit.createBlockData(data).getAsString());
                    if(problem!=null){preparationFailed=true;throw new IllegalStateException(problem);}
                    provenanceChecked=true;
                }
                status="Walidacja świata "+part.id();
                preparation=naturalBefore.isEmpty() && plantSoil.isEmpty() && own.before().isEmpty()?projects.works().prepareAsync(world,ops,request.country(),List.of()):projects.works().prepareTrustedAsync(world,ops,request.country(),List.of(),naturalBefore,plantSoil,own.before());
                projects.works().suspendPreparation(preparation,this::suspended);return;}
            if(!preparation.isDone())return;
            RailwayWorks.PreparedPlan plan;
            try {plan=preparation.join();}
            catch(CompletionException | CancellationException error){preparationFailed=true;throw error;}
            long cost=Math.multiplyExact(plan.preview().cost(),Math.max(0,plugin.getConfig().getLong("koszty.blok-budowy",1)));
            long used=0;for(var p:request.parts()){var intent=projects.constructionIntent(p.id());if(intent.isPresent())used=Math.addExact(used,intent.get().price());}
            if(request.fundFromWorldReserve())used=Math.addExact(used,plugin.consentFeesPaidFrom(budgetAccount(request)));
            if(cost>part.maxCost() || Math.addExact(used,cost)>request.maxTotalMd())throw new IllegalStateException("Wycena przekracza zatwierdzony limit");
            if(funding!=null){if(!funding.isDone())return;funding.join();funding=null;}
            if(request.fundFromWorldReserve() && !reserveReady){funding=bankCall("world.allocate-rail-once",budgetAccount(request),request.maxTotalMd());reserveReady=true;status="Potwierdzanie jednorazowego budżetu budowy";return;}
            String debitAccount=request.fundFromWorldReserve()?budgetAccount(request):RailData.ACCOUNT;
            if(plugin.economy().balance(debitAccount)<cost){
                if(request.fundFromWorldReserve())throw new IllegalStateException("Zatwierdzony budżet nie pokrywa wyceny; bez ponownego finansowania");
                status="Oczekiwanie na środki firmy: "+plugin.economy().format(cost);return;
            }
            if(cost==0){throw new IllegalStateException("Plan bez zmian wymaga odbioru przed oznaczeniem części ukończoną");}
            accepted=projects.submitFundedPrepared(part.id(),request.manager(),plan,part.maxCost(),new ConstructionJournal.GraphIntent(null,null,null,List.of(),null),debitAccount);
        }catch(Exception error){fail(error);}
    }
    private static String sourceFingerprint(Request r) throws Exception {
        String data=r.version()+"|"+r.manager()+"|"+r.country()+"|"+r.world()+"|"+r.maxTotalMd()+"|"+r.fundFromWorldReserve()+"|"+r.parts();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static void verifySource(Path file,Request r) throws Exception {
        if(!Files.readString(file).trim().equals(sourceFingerprint(r)))throw new IOException("Zmieniono przyjęty plan; nie można wznowić pod innym fingerprintem");
    }
    static void registerSource(Path file,Request r) throws Exception {
        String expected=sourceFingerprint(r);
        if(Files.exists(file)){verifySource(file,r);return;}
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");if(Files.exists(tmp))throw new IOException("Niepotwierdzony zapis źródła zlecenia; wymagana kontrola");
        Files.createDirectories(file.getParent());
        try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var bytes=java.nio.charset.StandardCharsets.UTF_8.encode(expected+"\n");while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE);
    }
    static boolean startReady(Path marker,String requestSha) throws IOException {
        return Files.isRegularFile(marker) && Files.size(marker)<=128 && Files.readString(marker).trim().equals(requestSha);
    }
    static String budgetAccount(Request r) {
        try {String data=r.manager()+"|"+r.world()+"|"+r.maxTotalMd()+"|"+r.parts();return "kolej:budzet_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private CompletableFuture<?> bankCall(String method,String account,long amount) throws Exception {
        var bank=Bukkit.getPluginManager().getPlugin("Skarbiec");if(bank==null || !bank.isEnabled())throw new IllegalStateException("Skarbiec niedostępny");
        bank.getClass().getMethod("worldTreasury",UUID.class,String.class,Map.class).invoke(bank,request.manager(),method,Map.of("account",account,"amount",Long.toString(amount)));return bankAck();
    }
    private CompletableFuture<?> bankAck() throws Exception {
        var bank=Bukkit.getPluginManager().getPlugin("Skarbiec");if(bank==null || !bank.isEnabled())throw new IllegalStateException("Skarbiec niedostępny");
        Object ack=bank.getClass().getMethod("bankDurable").invoke(bank);if(!(ack instanceof CompletableFuture<?> future))throw new IllegalStateException("Brak potwierdzenia banku");return future;
    }
    private void reset(){release();reading=null;ops=null;naturalBefore=Map.of();plantSoil=Map.of();own=Own.NONE;provenanceChecked=false;keys=null;chunkIndex=0;preparation=null;preparationFailed=false;accepted=null;funding=null;}
    private void release(){for(Chunk c:tickets)c.removePluginChunkTicket(plugin);tickets.clear();}
    static String problem(Throwable error) {
        while((error instanceof java.util.concurrent.CompletionException || error instanceof java.lang.reflect.InvocationTargetException) && error.getCause()!=null)error=error.getCause();
        if(error instanceof java.nio.file.FileSystemException)return "Nie można odczytać pliku przygotowanego planu.";
        return error.getMessage()==null?"Wymagana kontrola zarządcy; szczegóły w dzienniku serwera.":error.getMessage();
    }
    private void fail(Exception error){failed=true;status="Budowa wstrzymana: "+problem(error);release();plugin.getLogger().log(java.util.logging.Level.SEVERE,status,error);}
    @Override public void close(){closed=true;release();if(graph!=null)graph.close();reader.shutdown();
        try{if(!reader.awaitTermination(30,TimeUnit.SECONDS))plugin.getLogger().severe("Zapis sterowania siecią nie zakończył się przed wyłączeniem; sprawdź znacznik paused.");}
        catch(InterruptedException e){Thread.currentThread().interrupt();plugin.getLogger().severe("Przerwano oczekiwanie na zapis sterowania siecią.");}
    }
}
