// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bootstrap;

import static pl.medivalrpg.turnieje.bootstrap.BootstrapPlan.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.turnieje.arena.Arena;
import pl.medivalrpg.turnieje.data.*;
import pl.medivalrpg.turnieje.game.SessionManager;

/** Optional startup-only repair. No request file or enabled=false => no work, no registration. */
public final class ArenaBootstrap implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Storage storage;
    private final SessionManager sessions;
    private final BootstrapPlan plan;
    private final String fingerprint;
    private final World world;
    private final List<int[]> tickets=new ArrayList<>();
    private Progress progress;
    private BukkitTask task;
    private boolean active;
    private int cursor;

    public static ArenaBootstrap start(JavaPlugin plugin,Storage storage,SessionManager sessions) {
        Path file=plugin.getDataFolder().toPath().resolve("bootstrap-arena.json");
        if(!Files.exists(file))return null;
        try {
            Request request=JsonFiles.GSON.fromJson(Files.readString(file),Request.class);
            if(request==null)throw new IllegalArgumentException("Pusty bootstrap-arena.json");
            if(!request.enabled)return null;
            // Paper supplies default properties: compare complete block states, not just materials.
            if(request.expectedBlocks==null)throw new IllegalArgumentException("Brak snapshotu bootstrapu.");
            request.expectedBlocks=request.expectedBlocks.stream().map(e->new Expected(e.x(),e.y(),e.z(),Bukkit.createBlockData(e.state()).getAsString())).toList();
            var result=new ArenaBootstrap(plugin,storage,sessions,new BootstrapPlan(request));
            result.begin();return result;
        } catch(Exception|LinkageError e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Bootstrap areny odmówił działania przed naprawą",e);return null;
        }
    }
    private ArenaBootstrap(JavaPlugin plugin,Storage storage,SessionManager sessions,BootstrapPlan plan) throws Exception {
        this.plugin=plugin;this.storage=storage;this.sessions=sessions;this.plan=plan;
        fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonFiles.GSON.toJson(plan.request()).getBytes(StandardCharsets.UTF_8)));
        progress=storage.loadArenaBootstrap();world=Bukkit.getWorld("world");
        if(world==null)throw new IllegalStateException("Świat areny nie jest załadowany.");
        if(progress!=null && !progressValid(progress,fingerprint,plan.changes().size()))throw new IllegalStateException("Niezgodny dziennik/zmieniony manifest bootstrapu.");
    }
    private void begin() {
        if(progress!=null && progress.phase==Phase.BLOCKED) {plugin.getLogger().severe("Bootstrap zablokowany do audytu: "+progress.reason);return;}
        try {
            guards();
            for(int cx=308;cx<=309;cx++)for(int cz=55;cz<=56;cz++) {
                if(!world.loadChunk(cx,cz,false))throw new IllegalStateException("Brak istniejącego chunka areny: "+cx+","+cz);
                world.addPluginChunkTicket(cx,cz,plugin);tickets.add(new int[]{cx,cz});
            }
            validate(); // entire snapshot + ownership + spawn/regions + entities before ANY write
            if(progress!=null && (progress.phase==Phase.DONE || progress.phase==Phase.REGISTERING)) {
                finishedGeometry();registerArena();close();return;
            }
            if(storage.arenas().containsKey(plan.request().id))throw new IllegalStateException("Arena o tym ID już istnieje; bootstrap jej nie nadpisze.");
            if(progress==null){progress=new Progress();progress.fingerprint=fingerprint;storage.saveArenaBootstrap(progress);}
            active=true;Bukkit.getPluginManager().registerEvents(this,plugin);
            task=Bukkit.getScheduler().runTaskTimer(plugin,this::step,1,1);
        } catch(Exception|LinkageError e){block(e);}
    }
    private void guards() {
        var kraje=Bukkit.getPluginManager().getPlugin("Kraje");
        if(kraje==null || !kraje.isEnabled())throw new IllegalStateException("Brak aktywnych Krajów.");
        KrajeApi api=Bukkit.getServicesManager().load(KrajeApi.class);
        if(api==null || api.getCountry(OWNER).isEmpty())throw new IllegalStateException("Nie istnieje właściciel areny.");
        List<UUID> owners=new ArrayList<>();
        for(int cx=308;cx<=309;cx++)for(int cz=55;cz<=56;cz++) owners.add(api.getCountryAt("world",cx,cz).map(k->k.id()).orElse(null));
        if(!ownership(plan.request().country,owners))throw new IllegalStateException("Wszystkie cztery chunki muszą należeć do KRÓLESTWA.");
        for(Arena a:storage.arenas().values()) {
            if(a.box==null || !"world".equals(a.box.world()))continue;
            boolean overlap=a.box.maxX()>=4933 && a.box.minX()<=4955 && a.box.maxZ()>=892 && a.box.minZ()<=910 && a.box.maxY()>=89 && a.box.minY()<=97;
            if(overlap && !a.id.equals(plan.request().id))throw new IllegalStateException("Obrys przecina inną zarejestrowaną arenę.");
        }
        if(sessions.sessionOn(plan.request().id)!=null)throw new IllegalStateException("Na arenie trwa sesja.");
        Location spawn=world.getSpawnLocation();int radius=Bukkit.getSpawnRadius();
        if(radius>0 && spawn.getBlockX()+radius>=4933 && spawn.getBlockX()-radius<=4955 && spawn.getBlockZ()+radius>=892 && spawn.getBlockZ()-radius<=910)
            throw new IllegalStateException("Obrys przecina ochronę spawnu.");
        Path areas=kraje.getDataFolder().toPath().resolve("protected-areas.json");
        if(Files.exists(areas))try {
            var root=com.google.gson.JsonParser.parseString(Files.readString(areas)).getAsJsonObject();
            if(!root.has("areas") || !root.get("areas").isJsonArray())throw new IllegalStateException("Nieznany format protected-areas.json");
            for(var entry:root.getAsJsonArray("areas")) {
                var a=entry.getAsJsonObject();if(!"world".equals(a.get("world").getAsString()))continue;
                int buffer=a.get("bufferChunks").getAsInt();if(buffer<0 || buffer>16)throw new IllegalStateException("Niepoprawny bufor ochrony.");
                int minx=(a.get("minX").getAsInt()>>4)-buffer,maxx=(a.get("maxX").getAsInt()>>4)+buffer;
                int minz=(a.get("minZ").getAsInt()>>4)-buffer,maxz=(a.get("maxZ").getAsInt()>>4)+buffer;
                if(maxx>=308 && minx<=309 && maxz>=55 && minz<=56)throw new IllegalStateException("Obrys przecina chroniony spawn/obszar Kraje.");
            }
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        var wg=Bukkit.getPluginManager().getPlugin("WorldGuard");
        if(wg!=null){if(!wg.isEnabled())throw new IllegalStateException("WorldGuard niedostępny — odmowa bootstrapu.");BootstrapWorldGuard.validate(world);}
    }
    private Block block(Cell c){return world.getBlockAt(c.x(),c.y(),c.z());}
    private void validate() {
        guards();
        for(var entry:plan.baseline().entrySet()) {
            Block b=block(entry.getKey());
            if(!plan.accepts(entry.getKey(),b.getBlockData().getAsString(),b.getState() instanceof TileState,progress))
                throw new IllegalStateException("Zmieniony/chroniony blok snapshotu: "+entry.getKey()+" / "+b.getBlockData().getAsString());
        }
        if(!world.getNearbyEntities(new BoundingBox(4938,89,897,4951,93,906)).isEmpty())
            throw new IllegalStateException("Encja/gracz/dekoracja w obszarze naprawy — odmowa zmian.");
    }
    private void step() {
        try {
            validate();
            int end=Math.min(plan.changes().size(),cursor+plan.request().blocksPerTick);
            progress.phase=Phase.APPLYING;progress.intentThrough=Math.max(progress.intentThrough,end);
            storage.saveArenaBootstrap(progress); // durable permission to recognize this batch after restart
            while(cursor<end) {
                Change change=plan.changes().get(cursor++);Block b=block(change.cell());
                if(!b.getBlockData().getAsString().equals(change.after()))b.setBlockData(Bukkit.createBlockData(change.after()),false);
            }
            if(cursor==plan.changes().size()) {
                finishedGeometry();world.save();progress.phase=Phase.REGISTERING;storage.saveArenaBootstrap(progress);
                registerArena();close();
            }
        }catch(Exception|LinkageError e){block(e);}
    }
    private void finishedGeometry() {
        for(Change c:plan.changes())if(!block(c.cell()).getBlockData().getAsString().equals(c.after()))throw new IllegalStateException("Niepełna ciągła podłoga; brak rejestracji.");
        for(int x=4938;x<=4950;x++)for(int z=897;z<=905;z++)for(int y=91;y<=92;y++)
            if(!world.getBlockAt(x,y,z).getType().isAir())throw new IllegalStateException("Przeszkoda nad boiskiem; brak rejestracji.");
        if(world.getBlockAt(4936,92,907).getType()!=Material.STONE_BRICKS || !world.getBlockAt(4936,93,907).getType().isAir() || !world.getBlockAt(4936,94,907).getType().isAir())
            throw new IllegalStateException("Widownia niedostępna.");
    }
    private void registerArena() {
        Arena desired=plan.arena(),existing=storage.arenas().get(desired.id);
        if(existing!=null && !JsonFiles.GSON.toJson(existing).equals(JsonFiles.GSON.toJson(desired)))throw new IllegalStateException("Istniejąca arena różni się od bootstrapu; bez nadpisania.");
        storage.arenas().put(desired.id,desired);storage.saveArenas();storage.saveArenasNow();
        progress.phase=Phase.DONE;progress.reason="";storage.saveArenaBootstrap(progress);
        plugin.getLogger().info("Bootstrap areny zakończony: "+desired.id+"; pitch 13×9, TURNIEJ, KIT, bez koni.");
    }
    private void block(Throwable e) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Bootstrap areny wstrzymany; bez rejestracji niepełnej areny",e);
        if(progress==null){progress=new Progress();progress.fingerprint=fingerprint;}
        progress.phase=Phase.BLOCKED;progress.reason=e.toString();
        try{storage.saveArenaBootstrap(progress);}catch(Exception failure){plugin.getLogger().severe("Nie zapisano blokady bootstrapu: "+failure);}
        close();
    }
    @Override public void close(){active=false;if(task!=null)task.cancel();HandlerList.unregisterAll(this);for(int[] c:tickets)world.removePluginChunkTicket(c[0],c[1],plugin);tickets.clear();}
    private boolean locked(Block b){return active && b.getWorld()==world && b.getX()>=4933 && b.getX()<=4955 && b.getZ()>=892 && b.getZ()<=910 && b.getY()>=89 && b.getY()<=97;}
    @EventHandler(priority=EventPriority.HIGHEST) public void breaking(BlockBreakEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void placing(BlockPlaceEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void flow(BlockFromToEvent e){if(locked(e.getBlock())||locked(e.getToBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucket(PlayerBucketEmptyEvent e){if(locked(e.getBlock())||locked(e.getBlockClicked()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucket(PlayerBucketFillEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b)||locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b)||locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::locked);}
    @EventHandler(priority=EventPriority.HIGHEST) public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::locked);}
}
