// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.RoyalManifest;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.storage.RoyalJobStore;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.Placement;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;

/** One explicitly authorised royal reconstruction. No work starts automatically or accepts arbitrary file paths. */
public final class RoyalRebuildService implements AutoCloseable,org.bukkit.event.Listener {
    public enum Phase { PLATFORM, EVACUATE, CLAIM, CAPITAL, UNCLAIM, RESET, ARMIA_HOLD, CLEAN_OLD, OLD_FIRE, TERRAIN, BUILDINGS, ROADS, WALLS, ACCESS, DECORATIONS, FUNCTIONAL, GARRISON, RETURN, REMOVE_PLATFORM, FINAL_SAVE, DONE }
    public static final class State {
        public String manifestSha,phase="PLATFORM",message="Przygotowane";
        public boolean running;
        public long cursor,changes;
        public int index;
        public List<Long> originalClaims=new ArrayList<>(),terrainChunks=new ArrayList<>();
        public List<UUID> citizens=new ArrayList<>();
        public List<UUID> originalBuildingIds=new ArrayList<>();
        public List<Building> originalBuildings=new ArrayList<>();
        public Map<String,String> assets=new LinkedHashMap<>();
        public Set<UUID> rescuedPlayers=new LinkedHashSet<>();
        /** A journal cursor is not a Minecraft chunk-save receipt. Replay deterministically after an explicit resume. */
        public void restartFromPlatform(){if("DONE".equals(this.phase))throw new IllegalStateException("Zadanie jest ukończone.");this.running=true;this.phase=Phase.PLATFORM.name();this.index=0;this.cursor=0;}
    }
    private record Native(RoyalManifest.NativeBuilding spec,TemplateLibrary.Runtime runtime,Placement at) { }
    private record RoadCell(int x,int z,BlockData data) { }
    private final LudnoscPlugin plugin;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final Path directory,manifestFile;
    private final RoyalJobStore store;
    private final NamespacedKey savedAi,savedInvulnerable;
    private State state;
    private RoyalManifest manifest;
    private String manifestSha;
    private final List<Native> natives=new ArrayList<>();
    private final List<RoadCell> roads=new ArrayList<>();
    private BlockData wallData;
    private CompletableFuture<Void> checkpoint;
    private CompletableFuture<Chunk> loading;
    private long loadingKey=Long.MIN_VALUE,lastSave;
    private String loadingWorld;
    private final Set<Chunk> tickets=new LinkedHashSet<>();
    private int changedThisTick,stepsThisTick;
    private final Set<Long> checkedChunks=new java.util.HashSet<>();
    private final Map<Material,BlockData> simpleData=new java.util.EnumMap<>(Material.class);
    private final Map<UUID,Long> missingSince=new HashMap<>();
    private final Set<Long> evacuationChecked=new java.util.HashSet<>();
    private Set<Long> originalLand=Set.of();
    private final Map<UUID,Long> rescuedNow=new HashMap<>();
    private final Set<UUID> rescuePending=new java.util.HashSet<>();
    private long farmers;
    private static final class Decorations {int schema;List<Decoration> decorations=new ArrayList<>();}
    private static final class Decoration {String kind,block_state;int[] position;String[] text;boolean waxed;List<BannerPattern> patterns=new ArrayList<>();}
    private static final class BannerPattern {String color,pattern;}
    private static final class Garrison {UUID countryId;String world;List<Post> posts=new ArrayList<>(),siegePosts=new ArrayList<>();}
    private static final class Post {String world;double x,y,z;}
    private Decorations decorations;
    private Garrison garrison;
    private String garrisonJson;
    private Native palace;
    private Object armia;
    private String completedPlan;
    private static final class AccessFile {int schema;List<AccessCell> cells=new ArrayList<>();}
    private static final class AccessCell {int x,y,z;String state;}
    private record AccessEdit(int x,int y,int z,BlockData data) { }
    private final List<AccessEdit> access=new ArrayList<>();

    public RoyalRebuildService(final LudnoscPlugin plugin) {
        this.plugin=plugin;this.directory=plugin.getDataFolder().toPath().resolve("royal-rebuild");this.manifestFile=this.directory.resolve("manifest.json");
        this.store=new RoyalJobStore(this.directory);this.savedAi=new NamespacedKey(plugin,"royal_saved_ai");
        this.savedInvulnerable=new NamespacedKey(plugin,"royal_saved_invulnerable");
        try {
            final String json=this.store.load();
            if(json!=null) {
                try{this.state=this.gson.fromJson(json,State.class);Phase.valueOf(this.state.phase);}
                catch(RuntimeException ex){this.state=this.gson.fromJson(this.store.backup(),State.class);Phase.valueOf(this.state.phase);}
                if(this.state!=null&&!"DONE".equals(this.state.phase)) {
                    this.state.running=false;this.state.message="Wznowienie wymaga sprawdzenia manifestu i polecenia resume.";
                    this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=true;this.plugin.data().markDirty();
                } else if(this.state!=null){this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=false;this.plugin.data().markDirty();}
            }
        } catch(Exception ex) {this.plugin.getLogger().log(java.util.logging.Level.SEVERE,"Nie można odczytać dziennika królewskiej przebudowy; polecenie start pozostaje zablokowane.",ex);this.state=new State();this.state.phase="PLATFORM";this.state.message="Uszkodzony dziennik — wymagana kontrola administratora.";this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=true;this.plugin.data().markDirty();}
    }

    public boolean blocks(final UUID country) {
        if(country==null)return false;
        final var s=this.plugin.data().stateIfPresent(country);return s!=null&&s.royalRebuildActive;
    }
    private static String sha(final byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private World world(){return Bukkit.getWorld(this.manifest.world);}
    private long chunkKey(int x,int z){return RoyalManifest.key(x,z);}
    private boolean desired(int cx,int cz){return cx>=295&&cx<=311&&cz>=53&&cz<=69;}
    private Set<Long> desiredClaims(){return RoyalManifest.desiredChunks();}
    private boolean protectedChunk(int cx,int cz) throws Exception {
        final var kraje=Bukkit.getPluginManager().getPlugin("Kraje");
        if(kraje==null)throw new IllegalArgumentException("Brak Kraje.");
        final Object protection=kraje.getClass().getMethod("protectedAreas").invoke(kraje);
        return (boolean)protection.getClass().getMethod("isProtected",String.class,int.class,int.class).invoke(protection,this.manifest.world,cx,cz);
    }
    private void requireNoForeign(final int cx,final int cz) {
        final var owner=this.plugin.kraje().getCountryAt(this.manifest.world,cx,cz).orElse(null);
        if(owner!=null&&!RoyalManifest.COUNTRY.equals(owner.id()))throw new IllegalArgumentException("Cudzy teren: "+cx+", "+cz+" ("+owner.name()+").");
    }

    private void prepare(final boolean resume) throws Exception {
        if(!Files.isRegularFile(this.manifestFile)||Files.size(this.manifestFile)>4_194_304)throw new IllegalArgumentException("Brak manifestu royal-rebuild/manifest.json lub plik jest zbyt duży.");
        final byte[] bytes=Files.readAllBytes(this.manifestFile);this.manifestSha=sha(bytes);
        this.manifest=this.gson.fromJson(new String(bytes,StandardCharsets.UTF_8),RoyalManifest.class);this.manifest.validate();
        if(resume&&(this.state==null||!this.manifestSha.equals(this.state.manifestSha)))throw new IllegalArgumentException("Manifest różni się od zapisanego zadania. Wznowienie zablokowane.");
        final World w=this.world();if(w==null)throw new IllegalArgumentException("Świat nie jest dostępny.");
        final var country=this.plugin.kraje().getCountry(RoyalManifest.COUNTRY).orElseThrow(()->new IllegalArgumentException("Brak KRÓLESTWA."));
        if(!resume&&(country.chunkCount()!=289||this.plugin.kraje().getClaims(RoyalManifest.COUNTRY,this.manifest.world).size()!=289))
            throw new IllegalArgumentException("Oczekiwano dokładnie289 obecnych chunków kraju, wszystkie w świecie manifestu.");
        for(final long key:this.desiredClaims()) {
            final int cx=(int)(key>>32),cz=(int)key;this.requireNoForeign(cx,cz);
            if(this.protectedChunk(cx,cz))throw new IllegalArgumentException("Chroniony obszar w nowym kraju: "+cx+", "+cz);
            if(!w.getWorldBorder().isInside(new Location(w,cx*16,90,cz*16))||!w.getWorldBorder().isInside(new Location(w,cx*16+15,90,cz*16+15)))
                throw new IllegalArgumentException("Nowy kraj wychodzi poza aktualną granicę świata.");
        }
        if(resume) {
            this.originalLand=Set.copyOf(this.state.originalClaims);
            if(this.state.originalClaims==null||this.state.originalClaims.size()!=289||this.state.originalBuildingIds==null)
                throw new IllegalArgumentException("Niekompletny pierwotny rejestr zadania.");
            for(final long key:this.state.originalClaims)if((int)(key>>32)<303||(int)(key>>32)>319||(int)key<53||(int)key>69)
                throw new IllegalArgumentException("Pierwotne terytorium nie zgadza się z uzgodnioną starą mapą.");
            if(!this.desiredClaims().equals(new LinkedHashSet<>(this.state.terrainChunks)))throw new IllegalArgumentException("Dziennik rozszerza dozwolony obszar niwelacji.");
            final Set<Long> authorised=new LinkedHashSet<>(this.state.originalClaims);authorised.addAll(this.desiredClaims());
            for(final long key:authorised){this.requireNoForeign((int)(key>>32),(int)key);if(this.protectedChunk((int)(key>>32),(int)key))throw new IllegalArgumentException("Obszar czyszczenia stał się chroniony.");}
        }
        this.natives.clear();this.roads.clear();final Map<String,String> assets=new LinkedHashMap<>();
        for(final RoyalManifest.NativeBuilding b:this.manifest.buildings) {
            final TemplateLibrary.Runtime rt=this.plugin.templates().runtime(b.type,b.rotation,b.dye,b.level);
            if(rt==null)throw new IllegalArgumentException("Brak szablonu: "+b.type+". Wgraj assety i użyj /ludnosc admin reload.");
            if(rt.template().meta().era()>2)throw new IllegalArgumentException("Manifest wykracza poza średniowiecze i renesans: "+b.id);
            final Placement at=Placement.centered(this.manifest.world,rt.template(),b.x,b.groundY,b.z);final BlockBox box=at.box();
            if(!this.manifest.contains(box.minX(),box.minZ())||!this.manifest.contains(box.maxX(),box.maxZ())||box.minY()<54||box.maxY()>158)
                throw new IllegalArgumentException("Pełny obrys budynku nie mieści się w dozwolonym terenie: "+b.id);
            if(b.box!=null&&(b.box.minX!=box.minX()||b.box.maxX!=box.maxX()||b.box.minZ!=box.minZ()||b.box.maxZ!=box.maxZ()))
                throw new IllegalArgumentException("Niezgodny obrys/obrót szablonu: "+b.id);
            for(final Native other:this.natives)if(other.at.box().intersects(box))throw new IllegalArgumentException("Kolizja budynków: "+b.id+" / "+other.spec.id);
            this.natives.add(new Native(b,rt,at));
            final String assetKey=b.type+"#"+b.rotation+"#"+b.dye+"#"+b.level;
            if(!assets.containsKey(assetKey)) {
                final var digest=MessageDigest.getInstance("SHA-256");
                digest.update((rt.template().width()+":"+rt.template().height()+":"+rt.template().depth()+":"+rt.template().meta()).getBytes(StandardCharsets.UTF_8));
                for(int n=0;n<rt.template().volume();n++)digest.update((String.valueOf(rt.template().state(n))+"\n").getBytes(StandardCharsets.UTF_8));
                assets.put(assetKey,HexFormat.of().formatHex(digest.digest()));
            }
        }
        this.palace=this.natives.stream().filter(n->n.spec.type.equals("palac_krola_maciusa")).findFirst().orElseThrow();
        if(this.palace.spec.rotation!=0)throw new IllegalArgumentException("Dekoracje pałacu wymagają uzgodnionego obrotu0.");
        final Path decorationsPath=this.directory.resolve("decorations.json"),garrisonPath=this.directory.resolve("garrisons.json");
        if(!Files.isRegularFile(decorationsPath)||!Files.isRegularFile(garrisonPath)||Files.size(decorationsPath)>262144||Files.size(garrisonPath)>262144)
            throw new IllegalArgumentException("Brak ograniczonych plików decorations.json/garrisons.json w royal-rebuild.");
        final byte[] decorationBytes=Files.readAllBytes(decorationsPath),garrisonBytes=Files.readAllBytes(garrisonPath);
        assets.put("decorations.json",sha(decorationBytes));assets.put("garrisons.json",sha(garrisonBytes));
        this.decorations=this.gson.fromJson(new String(decorationBytes,StandardCharsets.UTF_8),Decorations.class);
        if(this.decorations==null||this.decorations.schema!=1||this.decorations.decorations==null||this.decorations.decorations.size()>100)
            throw new IllegalArgumentException("Nieprawidłowy opis dekoracji.");
        for(final Decoration d:this.decorations.decorations) {
            if(d==null||d.position==null||d.position.length!=3||d.position[0]<0||d.position[0]>=this.palace.runtime.template().width()
                ||d.position[1]<0||d.position[1]>=this.palace.runtime.template().height()||d.position[2]<0||d.position[2]>=this.palace.runtime.template().depth()
                ||!List.of("banner","sign").contains(d.kind))throw new IllegalArgumentException("Dekoracja poza pałacem.");
            final BlockData blockData=Bukkit.createBlockData(d.block_state);
            if(d.kind.equals("banner")&&!blockData.getMaterial().name().contains("BANNER")||d.kind.equals("sign")&&!blockData.getMaterial().name().contains("SIGN"))
                throw new IllegalArgumentException("Nieprawidłowy blok dekoracji.");
            if(d.kind.equals("sign")&&(d.text==null||d.text.length!=4||java.util.Arrays.stream(d.text).anyMatch(t->t==null||t.length()>80)))
                throw new IllegalArgumentException("Nieprawidłowy tekst tabliczki.");
            if(d.kind.equals("banner"))for(final BannerPattern pattern:d.patterns){org.bukkit.DyeColor.valueOf(pattern.color.toUpperCase(java.util.Locale.ROOT));
                if(org.bukkit.Registry.BANNER_PATTERN.get(NamespacedKey.fromString(pattern.pattern))==null)throw new IllegalArgumentException("Nieznany wzór sztandaru.");}
        }
        this.garrisonJson=new String(garrisonBytes,StandardCharsets.UTF_8);this.garrison=this.gson.fromJson(this.garrisonJson,Garrison.class);
        if(this.garrison==null||!RoyalManifest.COUNTRY.equals(this.garrison.countryId)||!this.manifest.world.equals(this.garrison.world)||this.garrison.posts==null||this.garrison.posts.size()!=32
            ||this.garrison.siegePosts==null||this.garrison.siegePosts.size()!=10)throw new IllegalArgumentException("Wymagany plan32strażników i10artylerii KRÓLESTWA.");
        final List<Post> posts=new ArrayList<>(this.garrison.posts);posts.addAll(this.garrison.siegePosts);
        for(final Post post:posts)if(post==null||post.world!=null&&!this.manifest.world.equals(post.world)||!Double.isFinite(post.x)||!Double.isFinite(post.y)||!Double.isFinite(post.z)
            ||!this.manifest.contains((int)Math.floor(post.x),(int)Math.floor(post.z))||post.y<91||post.y>110)throw new IllegalArgumentException("Stanowisko wojska poza bezpiecznym planem.");
        this.armia=Bukkit.getPluginManager().getPlugin("Armia");if(this.armia==null)throw new IllegalArgumentException("Brak Armia; ewakuacja wojsk jest obowiązkowa.");
        this.armia.getClass().getMethod("beginRoyalRebuild");this.armia.getClass().getMethod("deployRoyalGarrison",String.class);
        Bukkit.getPluginManager().getPlugin("Kraje").getClass().getMethod("saveRoyalCheckpoint");
        final Path planPath=this.directory.resolve("plan.json"),accessPath=this.directory.resolve("access.json");
        if(!Files.isRegularFile(planPath)||Files.size(planPath)>4_194_304)throw new IllegalArgumentException("Brak ograniczonego pliku royal-rebuild/plan.json.");
        final byte[] planBytes=Files.readAllBytes(planPath);this.completedPlan=new String(planBytes,StandardCharsets.UTF_8);
        final var plan=pl.medivalrpg.ludnosc.planner.DesignPlan.parse(this.completedPlan);
        if(plan.options.autoFill||plan.options.allowDemolish)throw new IllegalArgumentException("Gotowy plan musi mieć autoFill:false i allowDemolish:false.");
        assets.put("plan.json",sha(planBytes));
        if(!Files.isRegularFile(accessPath)||Files.size(accessPath)>2_097_152)throw new IllegalArgumentException("Brak ograniczonego pliku royal-rebuild/access.json.");
        final byte[] accessBytes=Files.readAllBytes(accessPath);assets.put("access.json",sha(accessBytes));
        final AccessFile accessFile=this.gson.fromJson(new String(accessBytes,StandardCharsets.UTF_8),AccessFile.class);
        if(accessFile==null||accessFile.schema!=1||accessFile.cells==null||accessFile.cells.size()>10000)throw new IllegalArgumentException("Nieprawidłowy plik dojść.");
        this.access.clear();final Map<String,BlockData> blockData=new HashMap<>();
        for(final AccessCell c:accessFile.cells){
            if(c==null||!RoyalManifest.accessCoordinate(c.x,c.y,c.z))
                throw new IllegalArgumentException("Dojście wychodzi poza uzgodniony czteroblokowy pas.");
            final BlockData data=blockData.computeIfAbsent(c.state,Bukkit::createBlockData);final Material material=data.getMaterial();
            if(material==Material.TNT||material==Material.FIRE||material==Material.SOUL_FIRE||material==Material.LAVA||material==Material.WATER
                ||material.name().contains("COMMAND_BLOCK")||material==Material.STRUCTURE_BLOCK||material==Material.JIGSAW||material.getHardness()<0)
                throw new IllegalArgumentException("Niedozwolony materiał dojścia: "+material);
            this.access.add(new AccessEdit(c.x,c.y,c.z,data));
        }
        if(resume&&!assets.equals(this.state.assets))throw new IllegalArgumentException("Szablony lub pliki dekoracji/wojska zmieniły się od rozpoczęcia zadania. Wznowienie zablokowane.");
        if(!resume&&this.state==null){/* The validated fingerprints are copied into the start journal below. */}
        this.preparedAssets=assets;
        final Map<Long,RoadCell> roadMap=new LinkedHashMap<>();
        for(final RoyalManifest.Road r:this.manifest.roads) {
            final BlockData data=Bukkit.createBlockData(r.material);if(!data.getMaterial().isSolid())throw new IllegalArgumentException("Niewłaściwa nawierzchnia drogi.");
            for(final long column:RoyalManifest.roadColumns(r)) {
                final int x=(int)(column>>32),z=(int)column;
                roadMap.put(column,new RoadCell(x,z,data));
            }
        }
        this.roads.addAll(roadMap.values());this.wallData=Bukkit.createBlockData(this.manifest.walls.material);
        if(!this.wallData.getMaterial().isSolid())throw new IllegalArgumentException("Niewłaściwy materiał murów.");
        this.farmers=this.plugin.data().citizensOf(RoyalManifest.COUNTRY).stream().filter(c->c.adult()&&"minecraft:farmer".equals(c.profession())).count();
    }
    private Map<String,String> preparedAssets=Map.of();

    public void command(final CommandSender sender,final String action) {
        if(!sender.hasPermission("ludnosc.admin")){sender.sendMessage("Brak uprawnienia ludnosc.admin.");return;}
        try {
            if(this.state!=null&&this.state.running&&(action.equals("preview")||action.equals("resume"))){sender.sendMessage(this.status());return;}
            switch(action) {
                case "preview" -> {
                    if(this.state!=null&&!"DONE".equals(this.state.phase))this.prepare(true);else this.prepare(false);
                    int beds=this.natives.stream().filter(n->n.runtime.template().meta().function().housing()).mapToInt(n->n.runtime.template().bedCount()).sum();
                    sender.sendMessage("KRÓLESTWO: teren17×17, poziom90, "+this.natives.size()+" budynków, "+beds+" łóżek, "+this.roads.size()+" pól dróg, cztery bramy. SHA256 "+this.manifestSha);
                    sender.sendMessage("To pełna przebudowa autoryzowanego kraju. Preview nie zmienia świata. Start: /ludnosc admin krolestwo start");
                }
                case "start" -> {
                    if(this.state!=null)throw new IllegalArgumentException("Zadanie już istnieje; użyj status/resume. Dziennik nie jest nadpisywany.");
                    this.prepare(false);final State next=new State();next.manifestSha=this.manifestSha;next.assets=new LinkedHashMap<>(this.preparedAssets);
                    next.originalClaims=new ArrayList<>(this.plugin.kraje().getClaims(RoyalManifest.COUNTRY,this.manifest.world));
                    this.originalLand=Set.copyOf(next.originalClaims);
                    for(final long key:next.originalClaims)if((int)(key>>32)<303||(int)(key>>32)>319||(int)key<53||(int)key>69)
                        throw new IllegalArgumentException("Stare terytorium różni się od uzgodnionych chunków303..319 ×53..69.");
                    final Set<Long> authorised=new LinkedHashSet<>(next.originalClaims);authorised.addAll(this.desiredClaims());next.terrainChunks=new ArrayList<>(this.desiredClaims());
                    for(final long key:authorised){this.requireNoForeign((int)(key>>32),(int)key);if(this.protectedChunk((int)(key>>32),(int)key))throw new IllegalArgumentException("Chroniony stary teren — nie rozpoczynam czyszczenia.");}
                    next.citizens=this.plugin.data().citizensOf(RoyalManifest.COUNTRY).stream().map(CitizenRecord::entityId).toList();this.state=next;
                    next.originalBuildingIds=this.plugin.data().buildingsOf(RoyalManifest.COUNTRY).stream().map(Building::id).toList();
                    next.originalBuildings=new ArrayList<>(this.plugin.data().buildingsOf(RoyalManifest.COUNTRY));
                    this.state.running=true;this.state.message="Rozpoczęto; oczekiwanie na trwały zapis.";this.pausePlans();this.save();sender.sendMessage(this.status());
                }
                case "resume" -> {
                    if(this.state==null||"DONE".equals(this.state.phase))throw new IllegalArgumentException("Nie ma zadania do wznowienia.");
                    this.prepare(true);this.state.restartFromPlatform();
                    final Set<UUID> recovered=new LinkedHashSet<>(this.state.citizens);recovered.addAll(this.plugin.data().citizenIds(RoyalManifest.COUNTRY));this.state.citizens=new ArrayList<>(recovered);
                    this.state.message="Bezpieczne wznowienie: odtworzenie podestu, ewakuacji i deterministyczne powtórzenie przebudowy (zapis świata mógł cofnąć się względem dziennika).";
                    this.missingSince.clear();this.evacuationChecked.clear();this.pausePlans();this.save();sender.sendMessage(this.status());
                }
                case "status" -> sender.sendMessage(this.status());
                default -> sender.sendMessage("/ludnosc admin krolestwo preview|start|status|resume");
            }
        } catch(Exception ex){sender.sendMessage("Królewska przebudowa: "+ex.getMessage());this.plugin.getLogger().log(java.util.logging.Level.WARNING,"Walidacja królewskiej przebudowy: "+ex.getMessage());}
    }
    private void pausePlans(){this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=true;this.plugin.data().markDirty();}
    public String status(){return this.state==null?"Królewska przebudowa: brak rozpoczętego zadania.":"Królewska przebudowa: "+this.state.phase+", pozycja "+this.state.index+"/"+this.state.cursor+", zmiany "+this.state.changes+", "+(this.state.running?"trwa":"wstrzymana")+". "+this.state.message;}
    private void save(){this.checkpoint=this.store.save(this.gson.toJson(this.state));this.lastSave=System.currentTimeMillis();}
    private void phase(final Phase phase){this.state.phase=phase.name();this.state.index=0;this.state.cursor=0;this.state.message="Etap "+phase;this.plugin.saveRoyalRegistry();this.save();}
    private void pause(String reason){this.state.running=false;this.state.message=reason;this.save();this.plugin.getLogger().warning("Królewska przebudowa wstrzymana: "+reason);}

    private boolean loaded(final String worldName,final int cx,final int cz) {
        final World w=Bukkit.getWorld(worldName);if(w==null)throw new IllegalArgumentException("Brak świata "+worldName);
        if(w.isChunkLoaded(cx,cz)){final Chunk c=w.getChunkAt(cx,cz);if(this.tickets.add(c))c.addPluginChunkTicket(this.plugin);return true;}
        final long key=chunkKey(cx,cz);
        if(this.loading!=null) {
            if(!this.loading.isDone())return false;
            final Chunk chunk=this.loading.join();this.loading=null;
            if(this.tickets.add(chunk))chunk.addPluginChunkTicket(this.plugin);
            return worldName.equals(this.loadingWorld)&&this.loadingKey==key;
        }
        this.loadingWorld=worldName;this.loadingKey=key;this.loading=w.getChunkAtAsync(cx,cz,true);return false;
    }
    private void releaseTickets(){for(final Chunk c:this.tickets)c.removePluginChunkTicket(this.plugin);this.tickets.clear();}
    private Location hold(final int n){final int[] at=RoyalManifest.holdingCell(n);return new Location(this.world(),at[0]+0.5,at[1],at[2]+0.5);}
    private boolean platformLoaded(){for(int x:new int[]{this.manifest.square.x-7,this.manifest.square.x+7})for(int z:new int[]{this.manifest.square.z-7,this.manifest.square.z+7})
        if(!this.loaded(this.manifest.world,x>>4,z>>4))return false;return true;}
    private void set(final int x,final int y,final int z,final BlockData data) {
        this.guardChunk(x>>4,z>>4);
        final Block b=this.world().getBlockAt(x,y,z);this.stepsThisTick++;
        if(!b.getBlockData().matches(data)){b.setBlockData(data,false);this.changedThisTick++;this.state.changes++;}
    }
    private void set(final int x,final int y,final int z,final Material material){this.set(x,y,z,this.simpleData.computeIfAbsent(material,Material::createBlockData));}
    private void guardChunk(int cx,int cz) {
        if(!this.checkedChunks.add(chunkKey(cx,cz)))return;
        this.requireNoForeign(cx,cz);
        if(this.desired(cx,cz)) {
            final var owner=this.plugin.kraje().getCountryAt(this.manifest.world,cx,cz);
            final boolean beforeCommit=Set.of("PLATFORM","EVACUATE","ARMIA_HOLD","CLAIM","CAPITAL","UNCLAIM").contains(this.state.phase);
            if(!beforeCommit&&owner.isEmpty())throw new IllegalArgumentException("Nowy chunk nie należy już do KRÓLESTWA: "+cx+", "+cz);
            if(!this.world().getWorldBorder().isInside(new Location(this.world(),cx*16,90,cz*16))
                ||!this.world().getWorldBorder().isInside(new Location(this.world(),cx*16+15,90,cz*16+15)))throw new IllegalArgumentException("Granica świata zmieniła się; nowy kraj już się nie mieści.");
        }
        try{if(this.protectedChunk(cx,cz))throw new IllegalArgumentException("Teren stał się chroniony.");}
        catch(ReflectiveOperationException ex){throw new IllegalArgumentException("Nie można potwierdzić ochrony terenu.",ex);}
        catch(Exception ex){throw new IllegalArgumentException(ex.getMessage(),ex);}
        if(this.protectedPlayer(cx,cz))throw new IllegalArgumentException("Gracz w aktualnie zmienianym chunku — odleć ponad160 i użyj resume.");
    }
    private boolean protectedPlayer(int cx,int cz){return this.world().getPlayers().stream().anyMatch(p->p.getLocation().getBlockX()>>4==cx&&p.getLocation().getBlockZ()>>4==cz&&p.getY()<=160);}
    private void capital(final Location point) throws Exception {
        final Object plugin=Bukkit.getPluginManager().getPlugin("Kraje");final Object manager=plugin.getClass().getMethod("manager").invoke(plugin);
        final Object country=manager.getClass().getMethod("get",UUID.class).invoke(manager,RoyalManifest.COUNTRY);
        final ClassLoader loader=plugin.getClass().getClassLoader();
        final Class<?> countryType=Class.forName("pl.medivalrpg.kraje.model.Country",true,loader);
        final Class<?> chunkType=Class.forName("pl.medivalrpg.kraje.model.ChunkPos",true,loader);
        final Class<?> spawnType=Class.forName("pl.medivalrpg.kraje.model.SpawnPoint",true,loader);
        final Object chunk=chunkType.getConstructor(String.class,int.class,int.class).newInstance(this.manifest.world,303,61);
        // The public square is not yet built. Holding platform is the only safe spawn until RETURN.
        final Object spawn=spawnType.getConstructor(String.class,double.class,double.class,double.class,float.class,float.class)
            .newInstance(this.manifest.world,point.getX(),point.getY(),point.getZ(),0f,0f);
        manager.getClass().getMethod("setCapital",countryType,chunkType,spawnType).invoke(manager,country,chunk,spawn);
    }
    private void remember(final Villager v) {
        if(v.isSleeping())v.wakeup();
        final var pdc=v.getPersistentDataContainer();
        if(!pdc.has(this.savedAi,PersistentDataType.BYTE)){pdc.set(this.savedAi,PersistentDataType.BYTE,(byte)(v.hasAI()?1:0));pdc.set(this.savedInvulnerable,PersistentDataType.BYTE,(byte)(v.isInvulnerable()?1:0));}
        v.setAI(false);v.setInvulnerable(true);v.setFireTicks(0);v.setFallDistance(0);v.setVelocity(new org.bukkit.util.Vector());
    }
    private void restore(final Villager v){final var pdc=v.getPersistentDataContainer();if(pdc.has(this.savedAi,PersistentDataType.BYTE)){
        v.setAI(pdc.getOrDefault(this.savedAi,PersistentDataType.BYTE,(byte)1)==1);v.setInvulnerable(pdc.getOrDefault(this.savedInvulnerable,PersistentDataType.BYTE,(byte)0)==1);
        pdc.remove(this.savedAi);pdc.remove(this.savedInvulnerable);}v.setMemory(MemoryKey.HOME,null);}

    public void tick() {
        if(this.state==null||!this.state.running||this.manifest==null)return;
        if(this.plugin.kraje().getCountry(RoyalManifest.COUNTRY).isEmpty()){this.pause("KRÓLESTWO już nie istnieje; nie wykonuję dalszych zmian.");return;}
        if(this.checkpoint!=null){if(!this.checkpoint.isDone())return;try{this.checkpoint.join();this.checkpoint=null;}catch(RuntimeException ex){this.state.running=false;this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=true;this.plugin.data().markDirty();this.state.message="Błąd zapisu dziennika — bez dalszych zmian świata.";this.plugin.getLogger().log(java.util.logging.Level.SEVERE,this.state.message,ex);return;}}
        final long deadline=System.nanoTime()+2_000_000L;this.changedThisTick=0;this.stepsThisTick=0;this.checkedChunks.clear();
        try {
            while(this.stepsThisTick<2000&&System.nanoTime()<deadline&&this.state.running&&this.checkpoint==null) {
                final Phase phase=Phase.valueOf(this.state.phase);
                if(!this.step(phase))break;
            }
            if(System.currentTimeMillis()-this.lastSave>5000&&this.checkpoint==null)this.save();
        } catch(Exception ex){final Throwable reason=ex instanceof java.lang.reflect.InvocationTargetException&&ex.getCause()!=null?ex.getCause():ex;this.pause(reason.getMessage()==null?reason.getClass().getSimpleName():reason.getMessage());}
    }

    private boolean step(final Phase phase) throws Exception {
        this.stepsThisTick++;
        switch(phase) {
            case PLATFORM -> {
                if(!this.platformLoaded())return false;
                if(this.state.cursor>=225){this.phase(Phase.EVACUATE);return false;}
                final Location at=this.hold((int)this.state.cursor++);this.set(at.getBlockX(),162,at.getBlockZ(),Material.STONE_BRICKS);return true;
            }
            case EVACUATE, RETURN -> {
                if(!this.platformLoaded())return false;
                if(this.state.index>=this.state.citizens.size()){
                    if(phase==Phase.RETURN){final Location safe=this.publicPoint(0);if(safe==null)return false;this.capital(safe);}
                    if(phase==Phase.RETURN&&this.manifest.provisions!=null){final var st=this.plugin.data().state(RoyalManifest.COUNTRY);st.food=Math.max(st.food,this.manifest.provisions.foodStock);this.plugin.data().markDirty();}
                    this.phase(phase==Phase.EVACUATE?Phase.ARMIA_HOLD:Phase.REMOVE_PLATFORM);return false;
                }
                final CitizenRecord rec=this.plugin.data().citizen(this.state.citizens.get(this.state.index));
                if(rec==null){this.state.index++;return true;}
                if(!rec.pending()&&!this.loaded(rec.world(),rec.chunkX(),rec.chunkZ()))return false;
                if(!rec.pending()&&!Bukkit.getWorld(rec.world()).getChunkAt(rec.chunkX(),rec.chunkZ()).isEntitiesLoaded())return false;
                final Location at=phase==Phase.EVACUATE?this.hold(this.state.index):this.publicPoint(this.state.index);
                if(at==null)return false;
                String profession=rec.profession();
                if(Bukkit.getEntity(rec.entityId()) instanceof Villager v) {
                    if(phase==Phase.EVACUATE)this.remember(v);
                    if(!v.teleport(at))throw new IllegalArgumentException("Nie można ewakuować mieszkańca: "+rec.name());
                    if(phase==Phase.RETURN)this.restore(v);
                    if(phase==Phase.RETURN&&this.manifest.provisions!=null&&RoyalManifest.mayBecomeFarmer(rec.adult(),rec.profession(),this.farmers,this.manifest.provisions.farmerTarget)
                        &&v.getProfession()==Villager.Profession.NONE) {
                        v.setProfession(Villager.Profession.FARMER);v.setVillagerExperience(Math.max(1,v.getVillagerExperience()));profession="minecraft:farmer";this.farmers++;
                    }
                    this.missingSince.remove(rec.entityId());
                } else if(!rec.pending()) {
                    final long since=this.missingSince.computeIfAbsent(rec.entityId(),id->System.currentTimeMillis());
                    if(System.currentTimeMillis()-since<5000)return false;
                    throw new IllegalArgumentException("Nie odnaleziono żywego mieszkańca po załadowaniu encji: "+rec.name()+" ("+rec.entityId()+"). Rekord pozostaje bez zmian; sprawdź go przed resume.");
                }
                this.plugin.data().putCitizen(rec.withState(at.getWorld().getName(),at.getBlockX(),at.getBlockY(),at.getBlockZ(),profession,rec.adult()));
                this.state.index++;return false; // At most one teleport per tick.
            }
            case CLAIM -> {
                final List<Long> desired=new ArrayList<>(this.desiredClaims());
                if(this.state.index>=desired.size()){this.phase(Phase.CAPITAL);return false;}
                final long key=desired.get(this.state.index);int cx=(int)(key>>32),cz=(int)key;this.requireNoForeign(cx,cz);
                if(this.plugin.kraje().getCountryAt(this.manifest.world,cx,cz).isEmpty()) {
                    final var r=this.plugin.kraje().claimChunk(RoyalManifest.COUNTRY,this.manifest.world,cx,cz,ClaimCause.ADMIN);
                    if(!r.ok())throw new IllegalArgumentException("Nie można zająć chunka: "+r.message());
                }
                this.state.index++;return false;
            }
            case CAPITAL -> {this.capital(this.hold(0));this.phase(Phase.UNCLAIM);return false;}
            case UNCLAIM -> {
                if(this.state.index>=this.state.originalClaims.size()){if(this.plugin.kraje().getClaims(RoyalManifest.COUNTRY,this.manifest.world).size()!=289)throw new IllegalArgumentException("Końcowy kraj nie ma289chunków.");this.phase(Phase.RESET);return false;}
                final long key=this.state.originalClaims.get(this.state.index);int cx=(int)(key>>32),cz=(int)key;
                if(!desired(cx,cz)&&this.plugin.kraje().getCountryAt(this.manifest.world,cx,cz).map(c->RoyalManifest.COUNTRY.equals(c.id())).orElse(false)) {
                    final var r=this.plugin.kraje().unclaimChunk(RoyalManifest.COUNTRY,this.manifest.world,cx,cz,ClaimCause.ADMIN);
                    if(!r.ok())throw new IllegalArgumentException("Nie można zwolnić starego chunka: "+r.message());
                }
                this.state.index++;return false;
            }
            case RESET -> {
                this.plugin.construction().countryRemoved(RoyalManifest.COUNTRY);
                for(final UUID id:this.state.originalBuildingIds){final Building b=this.plugin.data().building(id);if(b!=null){
                    if(b.countryId()!=null&&!RoyalManifest.COUNTRY.equals(b.countryId()))throw new IllegalArgumentException("Stary budynek należy już do innego kraju.");
                    this.plugin.buildings().remove(b);}}
                for(final Building b:List.copyOf(this.plugin.data().buildingsOf(RoyalManifest.COUNTRY)))this.plugin.buildings().remove(b);
                final var st=this.plugin.data().state(RoyalManifest.COUNTRY);st.center=new BlockPos(this.manifest.world,4856,90,1028);
                st.designJson=null;st.planActive=false;st.planForced=false;st.planDue=false;st.rebuildSince=0;st.settlements=null;
                st.fireRepairs.clear();st.roads().clear();st.mines().clear();st.quarries().clear();st.underground().clear();st.chests().clear();
                this.plugin.data().markDirty();this.phase(Phase.CLEAN_OLD);return false;
            }
            case ARMIA_HOLD -> {
                final Object count=this.armia.getClass().getMethod("beginRoyalRebuild").invoke(this.armia);
                if(!(count instanceof Number n)||n.intValue()<0)throw new IllegalArgumentException("Armia nie potwierdziła trwałej ewakuacji.");
                this.phase(Phase.CLAIM);return false;
            }
            case CLEAN_OLD -> {
                if(this.state.index>=this.state.originalBuildings.size()){this.phase(Phase.OLD_FIRE);return false;}
                final Building old=this.state.originalBuildings.get(this.state.index);final BlockBox box=old.box();
                final long width=(long)box.maxX()-box.minX()+1,depth=(long)box.maxZ()-box.minZ()+1,volume=width*depth*(box.maxY()-box.minY()+1L);
                if(volume<1||volume>4_000_000)throw new IllegalArgumentException("Nieprawidłowy stary obrys budynku.");
                if(this.state.cursor>=volume){this.state.index++;this.state.cursor=0;this.releaseDistantTickets();return true;}
                final int x=box.minX()+(int)(this.state.cursor%width),z=box.minZ()+(int)(this.state.cursor/width%depth),y=box.minY()+(int)(this.state.cursor/(width*depth));
                if(this.desired(x>>4,z>>4)||!this.originalLand.contains(chunkKey(x>>4,z>>4))){this.state.cursor++;return true;}
                if(!this.loaded(this.manifest.world,x>>4,z>>4))return false;this.guardChunk(x>>4,z>>4);
                if(!this.evacuateRecoveredChunk(x>>4,z>>4))return false;
                final TemplateLibrary.Runtime rt=old.registered()?null:this.plugin.templates().runtime(old.type(),old.rotation(),old.dye(),pl.medivalrpg.ludnosc.template.MaterialPalette.decode(old.level()));
                final Block block=this.world().getBlockAt(x,y,z);boolean remove=false;
                if(rt!=null&&rt.template().width()==width&&rt.template().depth()==depth&&rt.template().height()==box.maxY()-box.minY()+1) {
                    final int cell=(int)this.state.cursor;final BlockData expected=rt.data()[cell];
                    remove=y>=box.minY()+rt.template().meta().groundLayer()&&expected!=null&&!expected.getMaterial().isAir()&&block.getType()==expected.getMaterial();
                } else remove=!TerrainCache.natural(block.getType())&&!block.getType().isAir();
                if(remove||block.getType()==Material.FIRE||block.getType()==Material.SOUL_FIRE)this.set(x,y,z,Material.AIR);
                this.state.cursor++;return true;
            }
            case OLD_FIRE -> {
                if(this.state.index>=this.state.originalClaims.size()){this.phase(Phase.TERRAIN);return false;}
                final long key=this.state.originalClaims.get(this.state.index);int cx=(int)(key>>32),cz=(int)key;
                if(this.desired(cx,cz)){this.state.index++;this.state.cursor=0;return true;}
                if(!this.loaded(this.manifest.world,cx,cz))return false;this.guardChunk(cx,cz);
                if(!this.evacuateRecoveredChunk(cx,cz))return false;
                final int x=cx*16+(int)(this.state.cursor%16),z=cz*16+(int)(this.state.cursor/16%16),y=54+(int)(this.state.cursor/256);
                final Material type=this.world().getBlockAt(x,y,z).getType();if(type==Material.FIRE||type==Material.SOUL_FIRE)this.set(x,y,z,Material.AIR);
                if(++this.state.cursor>=256L*105){this.state.index++;this.state.cursor=0;this.releaseDistantTickets();this.save();return false;}return true;
            }
            case TERRAIN -> {
                final long volume=256L*105;
                if(this.state.index>=this.state.terrainChunks.size()){this.phase(Phase.BUILDINGS);return false;}
                final long chunk=this.state.terrainChunks.get(this.state.index);int cx=(int)(chunk>>32),cz=(int)chunk;
                this.guardChunk(cx,cz);
                if(!this.loaded(this.manifest.world,cx,cz))return false;
                if(this.state.cursor==0&&!this.evacuateRecoveredChunk(cx,cz))return false;
                final int x=cx*16+(int)(this.state.cursor%16),z=cz*16+(int)(this.state.cursor/16%16),y=54+(int)(this.state.cursor/256);
                this.set(x,y,z,y>90?Material.AIR:y==90?Material.GRASS_BLOCK:y>=88?Material.DIRT:Material.STONE);
                if(++this.state.cursor>=volume){this.state.cursor=0;this.state.index++;this.releaseDistantTickets();this.save();return false;}return true;
            }
            case BUILDINGS -> {
                if(this.state.index>=this.natives.size()){this.phase(Phase.ROADS);return false;}
                final Native n=this.natives.get(this.state.index);final int cell=(int)this.state.cursor;
                if(cell>=n.runtime.template().volume()){this.register(n);this.state.index++;this.state.cursor=0;this.releaseDistantTickets();this.save();return false;}
                final int x=n.at.x(cell),y=n.at.y(cell),z=n.at.z(cell);
                if(!this.loaded(this.manifest.world,x>>4,z>>4))return false;
                final BlockData data=n.runtime.data()[cell];if(data!=null)this.set(x,y,z,data.getMaterial()==Material.FIRE||data.getMaterial()==Material.SOUL_FIRE?Material.AIR.createBlockData():data);
                this.state.cursor++;return true;
            }
            case ROADS -> {
                if(this.state.cursor>=this.roads.size()){this.registerRoads();this.phase(Phase.WALLS);return false;}
                final RoadCell cell=this.roads.get((int)this.state.cursor);if(!this.loaded(this.manifest.world,cell.x>>4,cell.z>>4))return false;
                this.set(cell.x,90,cell.z,cell.data);this.state.cursor++;
                this.plugin.data().state(RoyalManifest.COUNTRY).roads().add(chunkKey(cell.x,cell.z));if(this.state.cursor%1024==0)this.releaseDistantTickets();return true;
            }
            case WALLS -> {
                final int minX=this.manifest.minX()+4,minZ=this.manifest.minZ()+4,maxX=this.manifest.maxX()-4,maxZ=this.manifest.maxZ()-4;
                final int width=maxX-minX+1,depth=maxZ-minZ+1,perimeter=2*width+2*depth-4;
                if(this.state.cursor>=(long)perimeter*8){this.registerWalls();this.phase(Phase.ACCESS);return false;}
                final int along=(int)(this.state.cursor%perimeter),height=(int)(this.state.cursor/perimeter)+1;
                int x,z;String side;
                if(along<width){x=minX+along;z=minZ;side="north";}
                else if(along<width+depth-1){x=maxX;z=minZ+along-width+1;side="east";}
                else if(along<2*width+depth-2){x=maxX-(along-width-depth+2);z=maxZ;side="south";}
                else{x=minX;z=maxZ-(along-(2*width+depth-2)+1);side="west";}
                if(!this.loaded(this.manifest.world,x>>4,z>>4))return false;
                final int coordinate=side.equals("north")||side.equals("south")?x:z;
                final boolean gate=this.manifest.walls.gates.stream().anyMatch(g->side.equals(g.side)&&Math.abs(coordinate-g.centre)<=3);
                this.set(x,90+height,z,gate&&height<=5||height==8&&(along&1)==1?Material.AIR.createBlockData():this.wallData);this.state.cursor++;return true;
            }
            case ACCESS -> {
                if(this.state.index>=this.access.size()){this.phase(Phase.DECORATIONS);return false;}
                final AccessEdit edit=this.access.get(this.state.index);if(!this.loaded(this.manifest.world,edit.x>>4,edit.z>>4))return false;
                this.set(edit.x,edit.y,edit.z,edit.data);this.state.index++;if(this.state.index%512==0)this.releaseDistantTickets();return true;
            }
            case DECORATIONS -> {
                if(this.state.index>=this.decorations.decorations.size()){this.phase(Phase.FUNCTIONAL);return false;}
                final Decoration decoration=this.decorations.decorations.get(this.state.index);
                final int x=this.palace.at.minX()+decoration.position[0],y=this.palace.at.minY()+decoration.position[1],z=this.palace.at.minZ()+decoration.position[2];
                if(!this.loaded(this.manifest.world,x>>4,z>>4))return false;
                this.set(x,y,z,Bukkit.createBlockData(decoration.block_state));
                final var blockState=this.world().getBlockAt(x,y,z).getState();
                if(blockState instanceof org.bukkit.block.Sign sign) {
                    for(int n=0;n<4;n++)sign.getSide(org.bukkit.block.sign.Side.FRONT).line(n,net.kyori.adventure.text.Component.text(decoration.text[n]));
                    sign.setWaxed(decoration.waxed);sign.update(true,false);
                } else if(blockState instanceof org.bukkit.block.Banner banner) {
                    final List<org.bukkit.block.banner.Pattern> patterns=new ArrayList<>();
                    for(final BannerPattern pattern:decoration.patterns)patterns.add(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.valueOf(pattern.color.toUpperCase(java.util.Locale.ROOT)),
                        org.bukkit.Registry.BANNER_PATTERN.get(NamespacedKey.fromString(pattern.pattern))));
                    banner.setPatterns(patterns);banner.update(true,false);
                } else throw new IllegalArgumentException("Nie można zastosować dekoracji pałacu.");
                this.state.index++;return false;
            }
            case FUNCTIONAL -> {
                if(this.state.index>=this.natives.size()){this.phase(Phase.GARRISON);return false;}
                final Native n=this.natives.get(this.state.index);final Building building=this.plugin.data().building(this.stableId(n.spec.id));
                if(building==null)throw new IllegalArgumentException("Brak rejestru ukończonego budynku: "+n.spec.id);
                this.plugin.underground().onBuilt(building);
                if(building.function()==BuildingFunction.MINE)this.plugin.mines().register(building,this.plugin.templates().get(building.type()),building.rotation(),building.box().minX(),building.box().minY(),building.box().minZ());
                if(building.function()==BuildingFunction.GRANARY){final List<BlockPos> chests=new ArrayList<>();for(final int[] c:n.runtime.template().containers())chests.add(n.at.toWorld(c));this.plugin.granary().addAll(RoyalManifest.COUNTRY,chests);}
                this.state.index++;return false;
            }
            case GARRISON -> {
                final List<Post> posts=new ArrayList<>(this.garrison.posts);posts.addAll(this.garrison.siegePosts);
                if(this.state.index<posts.size()) {final Post post=posts.get(this.state.index);if(!this.loaded(this.manifest.world,(int)Math.floor(post.x)>>4,(int)Math.floor(post.z)>>4))return false;this.state.index++;return false;}
                final Object result=this.armia.getClass().getMethod("deployRoyalGarrison",String.class).invoke(this.armia,this.garrisonJson);
                if(!(result instanceof Number number)||number.intValue()!=32)throw new IllegalArgumentException("Armia nie potwierdziła32strażników. Zadanie pozostaje przed powrotem mieszkańców.");
                this.releaseDistantTickets();this.phase(Phase.RETURN);return false;
            }
            case REMOVE_PLATFORM -> {
                if(!this.platformLoaded())return false;
                if(this.state.cursor>=225){this.phase(Phase.FINAL_SAVE);return false;}
                final Location at=this.hold((int)this.state.cursor++);this.set(at.getBlockX(),162,at.getBlockZ(),Material.AIR);return true;
            }
            case FINAL_SAVE -> {this.finish();return false;}
            case DONE -> {this.state.running=false;return false;}
        }
        return false;
    }
    private void releaseDistantTickets(){final Set<Long> platform=new java.util.HashSet<>();for(int x:new int[]{4856-7,4856+7})for(int z:new int[]{1028-7,1028+7})platform.add(chunkKey(x>>4,z>>4));
        for(final Chunk c:new ArrayList<>(this.tickets))if(!c.getWorld().getName().equals(this.manifest.world)||!platform.contains(chunkKey(c.getX(),c.getZ()))){c.removePluginChunkTicket(this.plugin);this.tickets.remove(c);}}
    private boolean evacuateRecoveredChunk(final int cx,final int cz) {
        if(this.evacuationChecked.contains(chunkKey(cx,cz)))return true;
        if(!this.platformLoaded())return false;
        final Chunk chunk=this.world().getChunkAt(cx,cz);if(!chunk.isEntitiesLoaded())return false;
        for(final var entity:chunk.getEntities())if(entity instanceof Villager v) {
            if(v.getY()>160&&v.getPersistentDataContainer().has(this.savedAi,PersistentDataType.BYTE)){
                if(this.plugin.data().citizen(v.getUniqueId())==null)this.plugin.citizens().adopt(v,RoyalManifest.COUNTRY);
                if(!this.state.citizens.contains(v.getUniqueId())){this.state.citizens=new ArrayList<>(this.state.citizens);this.state.citizens.add(v.getUniqueId());}
                continue;
            }
            final CitizenRecord record=this.plugin.data().citizen(v.getUniqueId());
            if(record==null&&!RoyalManifest.COUNTRY.equals(this.plugin.citizens().taggedCountry(v)))continue;
            if(record!=null&&!RoyalManifest.COUNTRY.equals(record.countryId()))throw new IllegalArgumentException("Obcy mieszkaniec w przebudowywanym chunku — wymaga ręcznego zabezpieczenia.");
            this.remember(v);final Location at=this.hold(this.state.citizens.size());
            if(!v.teleport(at))throw new IllegalArgumentException("Nie udała się dodatkowa ewakuacja mieszkańca po odczycie NBT.");
            if(record==null)this.plugin.citizens().adopt(v,RoyalManifest.COUNTRY);
            final CitizenRecord actual=this.plugin.data().citizen(v.getUniqueId());
            this.plugin.data().putCitizen(actual.withState(this.manifest.world,at.getBlockX(),at.getBlockY(),at.getBlockZ(),actual.profession(),actual.adult()));
            if(!this.state.citizens.contains(v.getUniqueId())){this.state.citizens=new ArrayList<>(this.state.citizens);this.state.citizens.add(v.getUniqueId());}
            return false; // A bounded extra evacuation; inspect this chunk again on the next tick.
        }
        this.evacuationChecked.add(chunkKey(cx,cz));return true;
    }
    private Location publicPoint(final int index) {
        for(int n=0;n<81;n++) {
            final int[] at=RoyalManifest.publicCell(index+n);int x=at[0],z=at[2];
            if(!this.loaded(this.manifest.world,x>>4,z>>4))return null;
            final Block feet=this.world().getBlockAt(x,91,z),head=this.world().getBlockAt(x,92,z),floor=this.world().getBlockAt(x,90,z);
            if(feet.isPassable()&&!feet.isLiquid()&&head.isPassable()&&!head.isLiquid()&&floor.isSolid()
                &&feet.getType()!=Material.FIRE&&feet.getType()!=Material.SOUL_FIRE&&head.getType()!=Material.FIRE&&head.getType()!=Material.SOUL_FIRE
                &&floor.getType()!=Material.MAGMA_BLOCK&&floor.getType()!=Material.CAMPFIRE&&floor.getType()!=Material.SOUL_CAMPFIRE&&floor.getType()!=Material.CACTUS)return new Location(this.world(),x+0.5,91,z+0.5);
        }throw new IllegalArgumentException("Nie ma bezpiecznego miejsca na ukończonym rynku.");
    }
    private UUID stableId(final String id){return UUID.nameUUIDFromBytes(("royal-rebuild:"+this.manifestSha+":"+id).getBytes(StandardCharsets.UTF_8));}
    private void register(final Native n) {
        final var t=n.runtime.template();final int[] sc=t.spawnCell();
        this.plugin.buildings().addRoyal(this.stableId(n.spec.id),RoyalManifest.COUNTRY,t.id(),t.meta().function(),n.at.box(),t.bedCount(),sc==null?null:n.at.toWorld(sc),n.spec.rotation,n.spec.dye,pl.medivalrpg.ludnosc.template.MaterialPalette.encode(n.spec.level));
    }
    /** Roads use their exact column registry, as ordinary planner roads do; no synthetic polyline bounding boxes. */
    private void registerRoads(){this.plugin.data().markDirty();}
    private void registerWalls(){int x1=this.manifest.minX()+4,z1=this.manifest.minZ()+4,x2=this.manifest.maxX()-4,z2=this.manifest.maxZ()-4;
        int[][] lines={{x1,z1,x2,z1},{x2,z1,x2,z2},{x1,z2,x2,z2},{x1,z1,x1,z2}};
        for(int n=0;n<4;n++){final int[] b=lines[n];this.plugin.buildings().addRoyal(this.stableId("wall-"+n),RoyalManifest.COUNTRY,"royal_wall",BuildingFunction.WALL,new BlockBox(this.manifest.world,b[0],91,b[1],b[2],98,b[3]),0,null,0,"blue",0);}
        for(final var gate:this.manifest.walls.gates){boolean horizontal=gate.side.equals("north")||gate.side.equals("south");int x=horizontal?gate.centre:gate.side.equals("west")?x1:x2,z=horizontal?gate.side.equals("north")?z1:z2:gate.centre;
            this.plugin.buildings().addRoyal(this.stableId("gate-"+gate.side),RoyalManifest.COUNTRY,"royal_gate",BuildingFunction.GATE,new BlockBox(this.manifest.world,horizontal?x-3:x,91,horizontal?z:z-3,horizontal?x+3:x,98,horizontal?z:z+3),0,new BlockPos(this.manifest.world,x,91,z),0,"blue",0);}
        this.plugin.data().state(RoyalManifest.COUNTRY).walls=new int[]{x1,z1,x2,z2};this.plugin.data().markDirty();
    }
    private State committedState;
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
    public void onRoyalJoin(final org.bukkit.event.player.PlayerJoinEvent event){this.scheduleRescue(event.getPlayer(),event.getPlayer().getLocation());}
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
    public void onRoyalTeleport(final org.bukkit.event.player.PlayerTeleportEvent event){this.scheduleRescue(event.getPlayer(),event.getTo());}
    private boolean rescueScope(final Location at) {
        if(this.state==null||!"world".equals(at.getWorld().getName()))return false;
        final long key=chunkKey(at.getBlockX()>>4,at.getBlockZ()>>4);
        if(!"DONE".equals(this.state.phase))return this.desired(at.getBlockX()>>4,at.getBlockZ()>>4)||this.state.originalClaims.contains(key);
        return at.getY()<91&&this.desired(at.getBlockX()>>4,at.getBlockZ()>>4)&&this.plugin.kraje().getCountryAt("world",at.getBlockX()>>4,at.getBlockZ()>>4).map(c->RoyalManifest.COUNTRY.equals(c.id())).orElse(false);
    }
    private void scheduleRescue(final org.bukkit.entity.Player player,final Location destination) {
        if(destination==null||!this.rescueScope(destination)||!this.rescuePending.add(player.getUniqueId()))return;
        Bukkit.getScheduler().runTaskLater(this.plugin,()->{this.rescuePending.remove(player.getUniqueId());if(player.isOnline())this.rescueIfBlocked(player);},1L);
    }
    private void rescueIfBlocked(final org.bukkit.entity.Player player) {
        final Location old=player.getLocation();if(!this.rescueScope(old))return;
        if(this.state.rescuedPlayers==null)this.state.rescuedPlayers=new LinkedHashSet<>();
        final long now=System.currentTimeMillis();if("DONE".equals(this.state.phase)&&this.state.rescuedPlayers.contains(player.getUniqueId())&&now-this.rescuedNow.getOrDefault(player.getUniqueId(),0L)>60000)return;
        final var body=player.getBoundingBox().clone().expand(-0.02);
        boolean blocked=false;
        for(int y=old.getBlockY();y<=old.getBlockY()+1;y++){
            final Block block=old.getWorld().getBlockAt(old.getBlockX(),y,old.getBlockZ());
            if(block.getType().isOccluding()&&block.getBoundingBox().overlaps(body)){blocked=true;break;}
        }
        if(!blocked)return;
        for(int radius=0;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
            int x=old.getBlockX()+dx,z=old.getBlockZ()+dz;if(!old.getWorld().isChunkLoaded(x>>4,z>>4))continue;
            for(int y=Math.max(91,old.getBlockY()+1);y<=160;y++) {
                final Block feet=old.getWorld().getBlockAt(x,y,z),head=feet.getRelative(0,1,0),floor=feet.getRelative(0,-1,0);
                if(!feet.isPassable()||feet.isLiquid()||!head.isPassable()||head.isLiquid()||!floor.getType().isOccluding()
                    ||floor.getType()==Material.MAGMA_BLOCK||feet.getType()==Material.FIRE||feet.getType()==Material.SOUL_FIRE)continue;
                player.setFallDistance(0);final Location safe=new Location(old.getWorld(),x+0.5,y,z+0.5,old.getYaw(),old.getPitch());
                if(player.teleport(safe)){
                    this.rescuedNow.put(player.getUniqueId(),now);if("DONE".equals(this.state.phase)){this.state.rescuedPlayers.add(player.getUniqueId());this.save();}
                    player.sendActionBar(net.kyori.adventure.text.Component.text("Bezpieczny powrót po przebudowie: zapisane miejsce było wewnątrz bloku."));
                }return;
            }
        }
    }
    private boolean royalCitizen(final org.bukkit.entity.Entity entity){final var rec=this.plugin.data().citizen(entity.getUniqueId());return rec!=null&&RoyalManifest.COUNTRY.equals(rec.countryId())||RoyalManifest.COUNTRY.equals(this.plugin.citizens().taggedCountry(entity));}
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST,ignoreCancelled=true)
    public void protectCitizen(final org.bukkit.event.entity.EntityDamageEvent event){if(this.blocks(RoyalManifest.COUNTRY)&&event.getEntity() instanceof Villager&&this.royalCitizen(event.getEntity()))event.setCancelled(true);}
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST,ignoreCancelled=true)
    public void protectTransformation(final org.bukkit.event.entity.EntityTransformEvent event){if(this.blocks(RoyalManifest.COUNTRY)&&event.getEntity() instanceof Villager&&this.royalCitizen(event.getEntity()))event.setCancelled(true);}
    private void finish() throws Exception {
        if(this.state.index==0) {
            this.plugin.design().storeCompletedRoyalPlan(RoyalManifest.COUNTRY,this.completedPlan);
            final Object kraje=Bukkit.getPluginManager().getPlugin("Kraje");
            final Object result=kraje.getClass().getMethod("saveRoyalCheckpoint").invoke(kraje);
            if(!(result instanceof CompletableFuture<?> countrySaved))throw new IllegalArgumentException("Kraje nie zwróciły potwierdzenia trwałego zapisu.");
            this.checkpoint=CompletableFuture.allOf(countrySaved,this.plugin.saveRoyalCheckpoint());this.state.index=1;return;
        }
        if(this.state.index==1) {
            if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"minecraft:save-all flush"))throw new IllegalArgumentException("Nie można wykonać końcowego zapisu świata.");
            this.releaseTickets();
            this.plugin.data().state(RoyalManifest.COUNTRY).royalRebuildActive=false;this.plugin.data().markDirty();
            this.checkpoint=this.plugin.saveRoyalCheckpoint();this.state.index=2;return;
        }
        if(this.state.index==2) {
            this.committedState=this.gson.fromJson(this.gson.toJson(this.state),State.class);
            this.committedState.phase=Phase.DONE.name();this.committedState.running=false;
            this.committedState.message="Przebudowa ukończona i zapisana: kraj289chunków, mieszkańcy na rynku,32strażników i10artylerii.";
            this.checkpoint=this.store.save(this.gson.toJson(this.committedState));this.state.index=3;return;
        }
        this.state=this.committedState;this.releaseTickets();
        this.plugin.getLogger().info(this.state.message);
    }
    @Override public void close(){if(this.state!=null){this.state.running=false;this.save();}this.releaseTickets();this.store.close();}
}
