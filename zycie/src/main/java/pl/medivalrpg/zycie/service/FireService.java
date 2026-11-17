// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.logic.FireIncident;
import pl.medivalrpg.zycie.logic.FireIncident.Cell;
import pl.medivalrpg.zycie.logic.FireSafety;
import pl.medivalrpg.zycie.logic.FireTactics;
import pl.medivalrpg.zycie.storage.FireStore;

/** Town emergencies: bell, safe evacuation, water-fetching crews and durable recovery requests. Main thread only. */
public final class FireService implements Listener {
    private record Notice(String world, Cell at) { }
    private record WaterSource(Location stand, Cell water) { }
    private static final class Work {
        long scanCursor;
        boolean scanUnknown;
        int citizensCursor;
        int crewCursor;
        int flameCursor;
        long citizensAt;
        List<UUID> citizens = List.of();
        WaterSource water;
        int waterCursor;
        long nextWaterSearch;
    }
    private static final class Duty {
        UUID incident;
        boolean firefighter, water;
        Location source, target;
        Cell sourceWater;
        long nextMove, nextAction, expires;
        long recruitAfter;
        Location progressTarget;
        double progressDistance;
        long progressAt;
        Cell aiming;
        final Map<Cell,Long> blocked=new LinkedHashMap<>();
    }

    private final ZyciePlugin plugin;
    private final CityCache cities;
    private final FireStore store;
    private final NamespacedKey bucketTag;
    private final Map<UUID, FireIncident> incidents = new LinkedHashMap<>();
    private final Map<UUID, Work> work = new HashMap<>();
    private final Map<UUID, Duty> duties = new HashMap<>();
    private final ArrayDeque<Notice> notices = new ArrayDeque<>();
    private boolean enabled = true;
    private int maxIncidents = 8, maxCells = 1024, scans = 512, citizenBudget = 12, moveBudget = 8, crewMaximum = 3;
    private int radius = 48, mergeRadius = 24;
    private long alarmCooldown = 30000, quiet = 20000, recoveryRetry = 30000;
    private long ticks;
    private int buildingCursor, buildingCell, incidentCursor;
    private int moves;
    private int waterProbes,approachProbes,pathProbes;
    private int bucketFlames=5;
    private long fills,extinguished,failedPaths,noWater;
    private boolean dirty;

    public FireService(final ZyciePlugin plugin, final CityCache cities) {
        this.plugin = plugin; this.cities = cities;
        this.bucketTag = new NamespacedKey(plugin, "fire_bucket");
        this.store = new FireStore(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    public void configure() {
        final var c = this.plugin.getConfig();
        this.enabled = c.getBoolean("pozary.wlaczone", true);
        this.maxIncidents = Math.clamp(c.getInt("pozary.max-incydentow", 8), 1, 32);
        this.maxCells = Math.clamp(c.getInt("pozary.max-plomieni", 1024), 32, 4096);
        this.scans = Math.clamp(c.getInt("pozary.skan-blokow-na-ture", 512), 32, 2048);
        this.citizenBudget = Math.clamp(c.getInt("pozary.mieszkancow-na-ture", 12), 1, 64);
        this.moveBudget = Math.clamp(c.getInt("pozary.ruchow-na-ture", 8), 1, 32);
        this.crewMaximum = Math.clamp(c.getInt("pozary.gaszacych-na-pozar", 3), 0, 8);
        this.radius = Math.clamp(c.getInt("pozary.promien-reakcji", 48), 16, 96);
        this.bucketFlames=Math.clamp(c.getInt("pozary.plomieni-na-wiadro",5),1,8);
        this.alarmCooldown = Math.max(5, c.getInt("pozary.alarm-co-sekund", 30)) * 1000L;
        this.quiet = Math.max(10, c.getInt("pozary.cisza-przed-odbudowa-sekund", 20)) * 1000L;
        this.recoveryRetry = Math.max(10, c.getInt("pozary.ponowienie-odbudowy-sekund", 30)) * 1000L;
        if (!this.enabled) this.releaseAll();
    }

    public void load() {
        for (final FireIncident i : this.store.load()) {
            if (i.recoveredAt == 0 && this.incidents.size() < 128) this.incidents.put(i.id, i);
        }
        for (final World world : Bukkit.getWorlds()) {
            for (final Villager v : world.getEntitiesByClass(Villager.class)) this.cleanBucket(v);
        }
    }

    /** LifeService must not send an emergency citizen back to work while this service steers them. */
    public boolean hasPriority(final UUID citizen, final long now) {
        final Duty duty = this.duties.get(citizen);
        return this.enabled && duty != null && duty.expires > now;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) { this.notice(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        if (fire(event.getNewState().getType())) this.notice(event.getBlock());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) { this.notice(event.getBlock()); }
    @EventHandler public void onEntities(final EntitiesLoadEvent event) {
        for (final Entity entity : event.getEntities()) if (entity instanceof Villager v && !this.duties.containsKey(v.getUniqueId())) this.cleanBucket(v);
    }

    private void notice(final Block block) {
        if (this.enabled && this.notices.size() < 512) this.notices.addLast(new Notice(block.getWorld().getName(),
            new Cell(block.getX(), block.getY(), block.getZ())));
    }

    private static boolean fire(final Material material) { return material == Material.FIRE || material == Material.SOUL_FIRE; }
    private static boolean loaded(final World world, final int x, final int z) { return world.isChunkLoaded(x >> 4, z >> 4); }
    private boolean own(final UUID country, final World world, final int x, final int z) {
        return this.plugin.kraje().getCountryAt(world.getName(), x >> 4, z >> 4).map(k -> country.equals(k.id())).orElse(false);
    }

    /** Fixed period of ten ticks. Probe, citizen and movement caps are global, not multiplied by country count. */
    public void tick() {
        if (!this.enabled) return;
        this.ticks += 10; this.moves = 0; this.waterProbes=256;this.approachProbes=256;this.pathProbes=4;
        final long now = System.currentTimeMillis();
        for(final UUID id:new ArrayList<>(this.duties.keySet())) {
            final Duty duty=this.duties.get(id);final FireIncident incident=this.incidents.get(duty.incident);
            if(duty.expires<now||incident==null||incident.fires.isEmpty()||incident.recoveredAt>0
                ||!(Bukkit.getEntity(id) instanceof Villager v)||!v.isValid())this.release(id);
        }
        for (int n = 0; n < 16 && !this.notices.isEmpty(); n++) {
            final Notice notice = this.notices.pollFirst(); final World world = Bukkit.getWorld(notice.world());
            if (world == null || !this.plugin.cfg().worldEnabled(world.getName())) continue;
            for (final int[] d : new int[][] {{0,0,0},{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}) {
                this.observe(world, new Cell(notice.at().x()+d[0],notice.at().y()+d[1],notice.at().z()+d[2]), now, true);
            }
        }
        final List<FireIncident> active = new ArrayList<>(this.incidents.values());
        if (!active.isEmpty()) {
            final int start = Math.floorMod(this.incidentCursor++, active.size());
            final int probes = Math.max(16, this.scans / active.size());
            int citizensLeft = this.citizenBudget;
            for (int n = 0; n < active.size(); n++) {
                final FireIncident incident = active.get((start+n)%active.size());
                final World world = Bukkit.getWorld(incident.world);
                if (this.plugin.kraje().getCountry(incident.country).isEmpty()) { incident.recoveredAt=now; this.dirty=true; continue; }
                if (world == null || !this.plugin.cfg().worldEnabled(world.getName())) continue;
                final Work runtime = this.work.computeIfAbsent(incident.id, k -> new Work());
                this.scan(incident, runtime, world, probes, now);
                if (!incident.fires.isEmpty()) {
                    final int visited = this.people(incident, runtime, world, now,
                        Math.min(Math.max(4,this.citizenBudget/active.size()),citizensLeft));
                    citizensLeft -= visited;
                    if (incident.alarmDue(now, this.alarmCooldown)) this.alarm(incident, runtime, world, now);
                }
                if (incident.recoveryDue(now, this.quiet)) this.recover(incident, now);
            }
        }
        this.discover(now);
        final List<UUID> released = this.duties.entrySet().stream().filter(e -> e.getValue().expires < now
            || !this.incidents.containsKey(e.getValue().incident) || this.incidents.get(e.getValue().incident).recoveredAt > 0
            || this.incidents.get(e.getValue().incident).fires.isEmpty())
            .map(Map.Entry::getKey).toList();
        for (final UUID id : released) this.release(id);
        this.incidents.values().removeIf(i -> i.recoveredAt > 0 && now - i.recoveredAt > 60000);
        this.work.keySet().retainAll(this.incidents.keySet());
        if (this.ticks % 200 == 0 && this.dirty) { this.store.save(this.incidents.values()); this.dirty = false; }
    }

    private void observe(final World world, final Cell cell, final long now, final boolean create) {
        if (!this.plugin.cfg().worldEnabled(world.getName()) || !loaded(world,cell.x(),cell.z()) || cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight()
            || !fire(world.getBlockAt(cell.x(),cell.y(),cell.z()).getType())) return;
        // a fireplace (eternal fire on netherrack, soul sand...) is decoration: no alarm, no firefighters, no rebuild
        if (cell.y() > world.getMinHeight()
            && pl.medivalrpg.zycie.logic.FireSafety.eternalBase(world.getBlockAt(cell.x(),cell.y()-1,cell.z()).getType().name())) return;
        final KrajInfo country = this.plugin.kraje().getCountryAt(world.getName(),cell.x()>>4,cell.z()>>4).orElse(null);
        if (country == null) return;
        FireIncident incident = this.incidents.values().stream()
            .filter(i -> i.accepts(country.id(),world.getName(),cell,this.mergeRadius)).findFirst().orElse(null);
        if (incident == null) {
            if (!create || this.incidents.size() >= this.maxIncidents || !this.townNearby(country.id(), world, cell)) return;
            incident = new FireIncident(country.id(),world.getName(),cell,now); this.incidents.put(incident.id,incident);
            this.plugin.getLogger().info("Pożar w kraju " + country.name() + " przy " + cell.x()+" "+cell.y()+" "+cell.z());
        }
        final int oldMinX=incident.minX,oldMaxX=incident.maxX,oldMinY=incident.minY,oldMaxY=incident.maxY,oldMinZ=incident.minZ,oldMaxZ=incident.maxZ;
        incident.observe(cell,now,this.maxCells);
        if (oldMinX!=incident.minX||oldMaxX!=incident.maxX||oldMinY!=incident.minY||oldMaxY!=incident.maxY||oldMinZ!=incident.minZ||oldMaxZ!=incident.maxZ) {
            final Work runtime=this.work.get(incident.id); if (runtime!=null) { runtime.scanCursor=0; runtime.scanUnknown=false; }
        }
        this.dirty=true;
    }

    private boolean townNearby(final UUID country, final World world, final Cell at) {
        for (final CityCache.Spot s : this.cities.get(country).spots()) {
            if (s.world().equals(world.getName()) && Math.abs(s.x()-at.x())<=40 && Math.abs(s.z()-at.z())<=40) return true;
        }
        final Location center=new Location(world,at.x()+0.5,at.y(),at.z()+0.5);
        return world.getNearbyEntitiesByType(Villager.class,center,16,16,16).stream()
            .anyMatch(v -> this.plugin.ludnosc().countryOf(v).map(country::equals).orElse(false));
    }

    private void scan(final FireIncident i, final Work runtime, final World world, final int budget, final long now) {
        // Verify known flames before sweeping. Unloaded flames stay pending; no chunk is force-loaded.
        final List<Cell> known=new ArrayList<>(i.fires);
        for (int checked=0;checked<Math.min(64,known.size());checked++) {
            final Cell cell=known.get(Math.floorMod(runtime.flameCursor++,known.size()));
            if (!loaded(world,cell.x(),cell.z())) continue;
            if (!this.own(i.country,world,cell.x(),cell.z()) || !fire(world.getBlockAt(cell.x(),cell.y(),cell.z()).getType())) { i.fires.remove(cell); this.dirty=true; }
            else i.lastFireAt=now;
        }
        final int x0=i.minX-4,z0=i.minZ-4,y0=Math.max(world.getMinHeight(),i.minY-4);
        final int width=i.maxX-i.minX+9,depth=i.maxZ-i.minZ+9,height=Math.min(world.getMaxHeight()-1,i.maxY+8)-y0+1;
        final long volume=(long)width*depth*height;
        for (int n=0;n<budget;n++) {
            final long cursor=runtime.scanCursor++;
            final int x=x0+(int)(cursor%width),z=z0+(int)(cursor/width%depth),y=y0+(int)(cursor/((long)width*depth));
            if (!loaded(world,x,z)) runtime.scanUnknown=true;
            else if (this.own(i.country,world,x,z)) this.observe(world,new Cell(x,y,z),now,false);
            if (runtime.scanCursor>=volume) {
                if (!runtime.scanUnknown) { i.lastSweepAt=now; this.dirty=true; }
                runtime.scanCursor=0; runtime.scanUnknown=false; break;
            }
        }
    }

    /** Fallback finds already burning towns after plugin/server start, with a small rotating block budget. */
    private void discover(final long now) {
        if (this.ticks%40!=0) return;
        final List<CityCache.Spot> spots=new ArrayList<>();
        for (final KrajInfo country:this.plugin.kraje().getCountries()) spots.addAll(this.cities.get(country.id()).spots());
        if (spots.isEmpty()) return;
        final CityCache.Spot spot=spots.get(Math.floorMod(this.buildingCursor,spots.size()));
        final World world=Bukkit.getWorld(spot.world());
        if (world==null) { this.buildingCursor++; this.buildingCell=0; return; }
        final int side=21,height=25,volume=side*side*height;
        for (int n=0;n<Math.min(256,this.scans);n++) {
            final int cursor=this.buildingCell++;
            final int x=spot.x()-10+cursor%side,z=spot.z()-10+cursor/side%side,y=spot.y()-3+cursor/(side*side);
            this.observe(world,new Cell(x,y,z),now,true);
            if (this.buildingCell>=volume) { this.buildingCell=0; this.buildingCursor++; break; }
        }
    }

    private boolean busy(final Villager v) { return v.isSleeping()||v.isTrading()||v.isInsideVehicle()||v.isLeashed(); }
    private int people(final FireIncident i, final Work runtime, final World world, final long now, final int maximum) {
        if (maximum<=0) return 0;
        if (now-runtime.citizensAt>5000) {
            runtime.citizens=this.plugin.ludnosc().citizens(i.country).stream().filter(id -> {
                final Entity entity=Bukkit.getEntity(id);
                return entity instanceof Villager v && v.getWorld()==world
                    &&Math.abs(v.getLocation().getX()-i.x)<=this.radius&&Math.abs(v.getLocation().getZ()-i.z)<=this.radius;
            }).toList();
            runtime.citizensAt=now;
        }
        final Set<UUID> visit=new java.util.LinkedHashSet<>();
        // Working crews are refreshed each turn; a large country's citizen rotation must not stall bucket trips.
        final List<UUID> crews=this.duties.entrySet().stream().filter(e->i.id.equals(e.getValue().incident)&&e.getValue().firefighter)
            .map(Map.Entry::getKey).toList();
        final int crewSlots=maximum==1 ? (this.ticks%20==0?1:0) : maximum-1;
        for(int n=0;n<Math.min(crewSlots,crews.size());n++)visit.add(crews.get(Math.floorMod(runtime.crewCursor++,crews.size())));
        for(int n=0;n<Math.min(maximum*2,runtime.citizens.size())&&visit.size()<maximum;n++)
            visit.add(runtime.citizens.get(Math.floorMod(runtime.citizensCursor++,runtime.citizens.size())));
        int processed=0;
        final long deadline=System.nanoTime()+1_500_000L;
        for (final UUID id:visit) {
            if (!(Bukkit.getEntity(id) instanceof Villager v)||v.getWorld()!=world||!v.isValid()) continue;
            if (Math.abs(v.getLocation().getX()-i.x)>this.radius||Math.abs(v.getLocation().getZ()-i.z)>this.radius) continue;
            processed++;
            Duty duty=this.duties.get(id);
            if(duty!=null && !duty.incident.equals(i.id)) {
                final FireIncident old=this.incidents.get(duty.incident);
                if(!FireTactics.reassign(old!=null&&!old.fires.isEmpty()&&old.recoveredAt==0,duty.firefighter,
                    old==null?Double.MAX_VALUE:this.nearestFire(old,v.getLocation()),this.nearestFire(i,v.getLocation())))continue;
                this.release(id);duty=null;
            }
            if(duty==null){duty=new Duty();duty.incident=i.id;this.duties.put(id,duty);}
            duty.expires=now+30000;
            final double nearest=this.nearestFire(i,v.getLocation());
            if(FireSafety.wakeSleeper(v.isSleeping(),nearest,v.getFireTicks()>0))v.wakeup();
            if (this.busy(v)) continue;
            // The actual bell still rings. Do not let its vanilla HIDE activity send people into burning homes.
            v.setMemory(MemoryKey.HEARD_BELL_TIME,null);
            v.setMemory(MemoryKey.HIDING_PLACE,null);
            if (!duty.firefighter && now>=duty.recruitAfter && FireSafety.mayFight(v.isAdult(),false,v.getEquipment().getItemInMainHand().getType().isAir(),
                (int)this.duties.values().stream().filter(d -> i.id.equals(d.incident)&&d.firefighter).count(),this.crewMaximum)
                && !this.plugin.sieges().isMilitia(id)) {
                if(runtime.water!=null&&(!loaded(world,runtime.water.water().x(),runtime.water.water().z())
                    ||world.getBlockAt(runtime.water.water().x(),runtime.water.water().y(),runtime.water.water().z()).getType()!=Material.WATER)) runtime.water=null;
                if(runtime.water==null&&now>=runtime.nextWaterSearch)runtime.water=this.waterSource(i,runtime,world,v.getLocation(),now);
                final WaterSource source=runtime.water;
                if (source!=null) { duty.source=source.stand(); duty.sourceWater=source.water(); duty.firefighter=true;duty.target=null; this.equip(v,Material.BUCKET); }
            }
            if (duty.firefighter) this.fight(i,duty,v,now);
            else {
                if (duty.target==null||!this.safe(i,duty.target,12)) duty.target=this.shelter(i,v);
                this.move(v,duty,duty.target,now);
            }
            if (System.nanoTime()>deadline) break;
        }
        return processed;
    }

    private void equip(final Villager v,final Material type) {
        v.getEquipment().setItemInMainHand(new ItemStack(type)); v.getEquipment().setItemInMainHandDropChance(0f);
        v.getPersistentDataContainer().set(this.bucketTag,PersistentDataType.BYTE,(byte)1);
    }
    private void cleanBucket(final Villager v) {
        if (!v.getPersistentDataContainer().has(this.bucketTag,PersistentDataType.BYTE)) return;
        final Material material=v.getEquipment().getItemInMainHand().getType();
        if (material==Material.BUCKET||material==Material.WATER_BUCKET) v.getEquipment().setItemInMainHand(null);
        v.getPersistentDataContainer().remove(this.bucketTag);
    }

    private double nearestFire(final FireIncident i,final Location at) {
        double nearest=Double.MAX_VALUE;
        for(final Cell cell:i.fires) {
            final double dx=at.getX()-cell.x()-0.5,dy=at.getY()-cell.y(),dz=at.getZ()-cell.z()-0.5;
            nearest=Math.min(nearest,dx*dx+dy*dy+dz*dz);
        }
        return nearest;
    }

    private void retireCrew(final Duty duty,final Villager v,final long now) {
        duty.firefighter=false;duty.water=false;duty.aiming=null;duty.target=null;duty.source=null;duty.recruitAfter=now+10000;
        this.cleanBucket(v);
    }

    private void animateWater(final Villager v,final Location at,final boolean fill) {
        v.lookAt(at,90,90);v.swingMainHand();
        final Location hand=v.getEyeLocation().add(0,-0.35,0);
        final var delta=at.toVector().subtract(hand.toVector());
        for(int step=1;step<=8;step++) {
            final Location point=hand.clone().add(delta.clone().multiply(step/8.0));
            v.getWorld().spawnParticle(Particle.SPLASH,point,3,0.06,0.06,0.06,0);
        }
        v.getWorld().spawnParticle(Particle.SPLASH,at,12,0.25,0.25,0.25,0);
        v.getWorld().playSound(at,fill?Sound.ITEM_BUCKET_FILL:Sound.ITEM_BUCKET_EMPTY,0.9f,1);
    }

    private void fight(final FireIncident i,final Duty duty,final Villager v,final long now) {
        if (!duty.water) {
            if (duty.source==null || duty.sourceWater==null || !loaded(v.getWorld(),duty.source.getBlockX(),duty.source.getBlockZ())
                || !loaded(v.getWorld(),duty.sourceWater.x(),duty.sourceWater.z())
                || v.getWorld().getBlockAt(duty.sourceWater.x(),duty.sourceWater.y(),duty.sourceWater.z()).getType()!=Material.WATER
                || !this.safe(i,duty.source,2)) { this.retireCrew(duty,v,now);return; }
            final Location water=new Location(v.getWorld(),duty.sourceWater.x()+0.5,duty.sourceWater.y()+0.8,duty.sourceWater.z()+0.5);
            if (v.getLocation().distanceSquared(duty.source)<=2.25 && v.getLocation().distanceSquared(water)<=36
                    && this.lineClear(v.getEyeLocation(),water) && now>=duty.nextAction) {
                duty.water=true;duty.nextAction=now+1000;duty.target=null;duty.aiming=null;
                this.equip(v,Material.WATER_BUCKET);this.animateWater(v,water,true);this.fills++;
            } else if(!this.move(v,duty,duty.source,now)) {
                this.retireCrew(duty,v,now);
            }
            return;
        }
        duty.blocked.entrySet().removeIf(e->e.getValue()<=now);
        final List<Cell> candidates=i.fires.stream().filter(c->!duty.blocked.containsKey(c)&&loaded(v.getWorld(),c.x(),c.z()))
            .sorted(java.util.Comparator.comparingDouble(c->v.getLocation().distanceSquared(new Location(v.getWorld(),c.x()+0.5,c.y(),c.z()+0.5))))
            .limit(12).toList();
        for(final Cell target:candidates) {
            final Block flame=v.getWorld().getBlockAt(target.x(),target.y(),target.z());
            if(!fire(flame.getType())||!this.own(i.country,v.getWorld(),target.x(),target.z()))continue;
            final Location flameCenter=flame.getLocation().add(0.5,0.5,0.5);
            final double distance=v.getLocation().distanceSquared(flameCenter);
            if (FireSafety.mayExtinguish(distance,true,true,this.lineClear(v.getEyeLocation(),flameCenter)) && now>=duty.nextAction) {
                final List<Cell> splash=FireTactics.splash(target,i.fires,this.bucketFlames,c -> {
                    if(!loaded(v.getWorld(),c.x(),c.z())||!this.own(i.country,v.getWorld(),c.x(),c.z()))return false;
                    final Location at=new Location(v.getWorld(),c.x()+0.5,c.y()+0.5,c.z()+0.5);
                    return FireSafety.mayExtinguish(v.getLocation().distanceSquared(at),true,
                        fire(v.getWorld().getBlockAt(c.x(),c.y(),c.z()).getType()),this.lineClear(v.getEyeLocation(),at));
                });
                this.animateWater(v,flameCenter,false);
                for(final Cell cell:splash) {
                    v.getWorld().getBlockAt(cell.x(),cell.y(),cell.z()).setType(Material.AIR,false);
                    i.fires.remove(cell);this.extinguished++;
                    v.getWorld().spawnParticle(Particle.SPLASH,new Location(v.getWorld(),cell.x()+0.5,cell.y()+0.5,cell.z()+0.5),8,0.3,0.3,0.3,0);
                }
                v.getWorld().playSound(flameCenter,Sound.BLOCK_FIRE_EXTINGUISH,1,1);
                v.setFireTicks(0);this.dirty=true;duty.water=false;duty.nextAction=now+1000;
                duty.aiming=null;duty.target=null;this.equip(v,Material.BUCKET);return;
            }
        }
        if(duty.aiming!=null && (!i.fires.contains(duty.aiming)||duty.target==null||!this.safe(i,duty.target,1.5))) {
            duty.aiming=null;duty.target=null;
        }
        if(duty.aiming==null) for(final Cell cell:candidates) {
            if(this.approachProbes<=0)break;
            final Block flame=v.getWorld().getBlockAt(cell.x(),cell.y(),cell.z());
            if(!fire(flame.getType()))continue;
            duty.target=this.approach(i,flame.getLocation(),v.getLocation(),false);
            if(duty.target!=null){duty.aiming=cell;break;}
            if(this.approachProbes>0)duty.blocked.put(cell,now+10000);
        }
        if(duty.aiming!=null && !this.move(v,duty,duty.target,now)) {
            duty.blocked.put(duty.aiming,now+10000);duty.aiming=null;duty.target=null;
        }
        while(duty.blocked.size()>32)duty.blocked.remove(duty.blocked.keySet().iterator().next());
    }

    private boolean move(final Villager v,final Duty duty,final Location target,final long now) {
        if(target==null||this.busy(v))return false;
        final double distance=v.getLocation().distanceSquared(target);
        if(duty.progressTarget==null||duty.progressTarget.distanceSquared(target)>1) {
            duty.progressTarget=target.clone();duty.progressDistance=distance;duty.progressAt=now;
        } else if(distance+0.25<duty.progressDistance) {duty.progressDistance=distance;duty.progressAt=now;}
        else if(distance>2.25 && now-duty.progressAt>8000) {this.failedPaths++;duty.progressTarget=null;return false;}
        if(this.moves>=this.moveBudget||now<duty.nextMove||this.pathProbes<=0)return true;
        duty.nextMove=now+500;this.moves++;
        if(distance<=0.36)return true;
        this.pathProbes--;
        final var path=v.getPathfinder().findPath(target);
        if(path==null||(!path.canReachFinalPoint() && (path.getFinalPoint()==null||path.getFinalPoint().distanceSquared(target)>2.25))) {
            this.failedPaths++;return false;
        }
        final boolean moving=v.getPathfinder().moveTo(path,1.2);
        if(!moving)this.failedPaths++;
        return moving;
    }

    private boolean standable(final World world,final int x,final int y,final int z) {
        if (!loaded(world,x,z)||y<=world.getMinHeight()||y+1>=world.getMaxHeight()) return false;
        final Block feet=world.getBlockAt(x,y,z),head=feet.getRelative(0,1,0),floor=feet.getRelative(0,-1,0);
        return feet.isPassable()&&!feet.isLiquid()&&!fire(feet.getType())&&head.isPassable()&&!head.isLiquid()
            && !fire(head.getType())&&floor.isSolid()&&floor.getType()!=Material.MAGMA_BLOCK
            && floor.getType()!=Material.CAMPFIRE&&floor.getType()!=Material.SOUL_CAMPFIRE&&floor.getType()!=Material.CACTUS;
    }

    private boolean safe(final FireIncident i,final Location at,final double minimum) {
        final World world=at.getWorld();
        if (world==null) return false;
        double nearest=Double.MAX_VALUE;
        for(final FireIncident other:this.incidents.values()) if(other.recoveredAt==0&&other.country.equals(i.country)&&other.world.equals(i.world))
            nearest=Math.min(nearest,this.nearestFire(other,at));
        return FireSafety.shelter(nearest,this.own(i.country,world,at.getBlockX(),at.getBlockZ()),
            loaded(world,at.getBlockX(),at.getBlockZ()),this.standable(world,at.getBlockX(),at.getBlockY(),at.getBlockZ()),false,minimum);
    }

    private Location ground(final FireIncident i,final World world,final int x,final int y,final int z,final double clearance) {
        if (!loaded(world,x,z)||!this.own(i.country,world,x,z)) return null;
        for (int d=0;d<=8;d++) for (final int sign:new int[]{1,-1}) {
            final Location at=new Location(world,x+0.5,y+d*sign,z+0.5);
            if (this.safe(i,at,clearance)) return at;
        }
        return null;
    }

    private Location shelter(final FireIncident i,final Villager v) {
        final Location home=v.getMemory(MemoryKey.HOME);
        if (home!=null&&home.getWorld()==v.getWorld()) {
            final Location safe=this.ground(i,v.getWorld(),home.getBlockX(),home.getBlockY(),home.getBlockZ(),16);
            if (safe!=null) return safe;
        }
        Location best=null; double distance=Double.MAX_VALUE;
        for (final int r:new int[]{20,28,36}) for(final int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}}) {
            final Location at=this.ground(i,v.getWorld(),i.x+d[0]*r,v.getLocation().getBlockY(),i.z+d[1]*r,16);
            if (at!=null&&v.getLocation().distanceSquared(at)<distance) { best=at; distance=v.getLocation().distanceSquared(at); }
        }
        return best;
    }

    private Location approach(final FireIncident i,final Location target,final Location from,final boolean water) {
        Location best=null;double score=Double.MAX_VALUE;
        final int[] levels={from.getBlockY(),target.getBlockY()+1,target.getBlockY()+2,target.getBlockY()+3,
            target.getBlockY()+4,from.getBlockY()-1,from.getBlockY()+1,from.getBlockY()-2,from.getBlockY()+2};
        for(final int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2},{2,2},{-2,2},{2,-2},{-2,-2},{3,0},{-3,0},{0,3},{0,-3}}) {
            for(final int y:levels) {
                if(this.approachProbes--<=0)return best;
                final Location at=new Location(target.getWorld(),target.getBlockX()+d[0]+0.5,y,target.getBlockZ()+d[1]+0.5);
                final Location centre=target.clone().add(0.5,water?0.8:0.5,0.5);
                if(at.distanceSquared(centre)>(water?25:15) || !this.safe(i,at,water?2:1.5))continue;
                final double distance=from.distanceSquared(at);
                if(distance<score&&this.lineClear(at.clone().add(0,1.5,0),centre)){best=at;score=distance;}
            }
        }
        return best;
    }

    /** Short ray with loaded-column checks, so buckets do not extinguish through walls or load missing chunks. */
    private boolean lineClear(final Location from,final Location to) {
        if(from.getWorld()!=to.getWorld())return false;
        final org.bukkit.util.Vector delta=to.toVector().subtract(from.toVector());
        final double distance=delta.length();
        if(distance<0.001)return true;
        final org.bukkit.util.Vector direction=delta.clone().normalize();
        for(double d=0;d<=distance;d+=0.5) {
            final Location at=from.clone().add(direction.clone().multiply(d));
            if(!loaded(from.getWorld(),at.getBlockX(),at.getBlockZ()))return false;
        }
        return from.getWorld().rayTraceBlocks(from,direction,distance,org.bukkit.FluidCollisionMode.NEVER,true)==null;
    }

    private WaterSource waterSource(final FireIncident i,final Work runtime,final World world,final Location from,final long now) {
        final List<CityCache.Spot> wells=this.cities.get(i.country).spots().stream().filter(s ->
            s.world().equals(world.getName())&&Math.abs(s.x()-i.x)<=this.radius+24&&Math.abs(s.z()-i.z)<=this.radius+24
                &&s.tokens().stream().anyMatch(t->t.contains("studnia")||t.contains("fontanna")))
            .sorted(java.util.Comparator.comparingLong(s->{final long dx=(long)s.x()-i.x,dz=(long)s.z()-i.z;return dx*dx+dz*dz;})).limit(4).toList();
        final int wellCells=wells.size()*FireTactics.wellProbeCount();
        final int total=wellCells+289;
        for(int n=0;n<64&&this.waterProbes>0&&this.approachProbes>0;n++) {
            this.waterProbes--;
            final int cursor=Math.floorMod(runtime.waterCursor++,total);
            final int x,y,z;
            if(cursor<wellCells) {
                final CityCache.Spot spot=wells.get(cursor/FireTactics.wellProbeCount());
                final FireTactics.Probe probe=FireTactics.wellProbe(cursor%FireTactics.wellProbeCount());
                x=spot.x()+probe.x();y=spot.y()+probe.y();z=spot.z()+probe.z();
            } else {
                final int index=cursor-wellCells;
                x=i.x-24+index%17*3;z=i.z-24+index/17*3;
                if(!loaded(world,x,z))continue;
                y=world.getHighestBlockYAt(x,z);
            }
            if(!loaded(world,x,z)||!this.own(i.country,world,x,z)||y<world.getMinHeight()||y+1>=world.getMaxHeight()
                ||world.getBlockAt(x,y,z).getType()!=Material.WATER||world.getBlockAt(x,y+1,z).getType()==Material.WATER)continue;
            final Location at=this.approach(i,new Location(world,x,y,z),from,true);
            if(at!=null)return new WaterSource(at,new Cell(x,y,z));
        }
        runtime.nextWaterSearch=now+500;
        this.noWater++;
        return null;
    }

    private void alarm(final FireIncident i,final Work runtime,final World world,final long now) {
        i.lastAlarmAt=now; this.dirty=true;
        Location bell=null; Villager speaker=null;
        for(final UUID id:runtime.citizens) {
            if(Bukkit.getEntity(id) instanceof Villager v&&v.getWorld()==world&&Math.abs(v.getLocation().getX()-i.x)<=this.radius
                &&Math.abs(v.getLocation().getZ()-i.z)<=this.radius) {
                if(speaker==null)speaker=v;
                final Location at=v.getMemory(MemoryKey.MEETING_POINT);
                if(at!=null&&at.getWorld()==world&&loaded(world,at.getBlockX(),at.getBlockZ())
                    &&this.own(i.country,world,at.getBlockX(),at.getBlockZ())) { bell=at; break; }
            }
        }
        boolean rung=false;
        if(bell==null)bell=this.findBell(i,world);
        if(bell!=null&&bell.getBlock().getState() instanceof Bell b)rung=b.ring();
        if(!rung)world.playSound(new Location(world,i.x+0.5,i.y,i.z+0.5),Sound.BLOCK_BELL_USE,3,0.9f);
        if(speaker!=null)this.plugin.bubbles().show(speaker,Component.text(this.plugin.getConfig().getString("pozary.tekst-alarmu","Pożar! Ratować mieszkańców!")),80);
    }

    private Location findBell(final FireIncident i,final World world) {
        int centres=0;
        for(final CityCache.Spot spot:this.cities.get(i.country).spots()) {
            if(!spot.world().equals(world.getName())||Math.abs(spot.x()-i.x)>this.radius||Math.abs(spot.z()-i.z)>this.radius
                ||spot.tokens().stream().noneMatch(t->t.contains("studnia")||t.contains("ratusz")||t.contains("rynek")))continue;
            if(centres++>=3)break;
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
                final int x=spot.x()+dx,z=spot.z()+dz;
                if(!loaded(world,x,z)||!this.own(i.country,world,x,z))continue;
                for(int y=Math.max(world.getMinHeight(),spot.y()-2);y<=Math.min(world.getMaxHeight()-1,spot.y()+5);y++)
                    if(world.getBlockAt(x,y,z).getType()==Material.BELL)return new Location(world,x,y,z);
            }
        }
        return null;
    }

    private void recover(final FireIncident incident,final long now) {
        int result=-1;
        try {
            final Method method=this.plugin.ludnosc().getClass().getMethod("requestFireRecovery",UUID.class,String.class,int.class,int.class,int.class,int.class);
            if(!method.isDefault())result=((Number)method.invoke(this.plugin.ludnosc(),incident.country,incident.world,
                incident.minX-8,incident.minZ-8,incident.maxX+8,incident.maxZ+8)).intValue();
        } catch(final ReflectiveOperationException|RuntimeException e) {
            this.plugin.getLogger().fine("Odbudowa po pożarze: "+e.getClass().getSimpleName());
        }
        incident.recoveryResult(result,now,this.recoveryRetry); this.dirty=true;
        if(result>=0)this.plugin.getLogger().info("Pożar ugaszony: zlecono przegląd i odbudowę "+result+" budynków.");
    }

    private void release(final UUID id) {
        this.duties.remove(id);
        if(Bukkit.getEntity(id) instanceof Villager v) { v.getPathfinder().stopPathfinding(); this.cleanBucket(v); }
    }
    public void releaseAll() { for(final UUID id:new ArrayList<>(this.duties.keySet()))this.release(id); }
    public String diagnostics() {
        final long active=this.incidents.values().stream().filter(i->i.recoveredAt==0&&!i.fires.isEmpty()).count();
        final long flames=this.incidents.values().stream().mapToLong(i->i.fires.size()).sum();
        final long crews=this.duties.values().stream().filter(d->d.firefighter).count();
        final long full=this.duties.values().stream().filter(d->d.firefighter&&d.water).count();
        final long sources=this.work.values().stream().filter(w->w.water!=null).count();
        return "Pożary: "+active+" • płomienie: "+flames+" • strażacy: "+crews+" (pełne wiadra: "+full+") • źródła: "+sources
            +" • pobrania: "+this.fills+" • ugaszone: "+this.extinguished+" • nieudane ścieżki: "+this.failedPaths+" • próby bez źródła: "+this.noWater;
    }
    public void close() { this.releaseAll(); this.store.close(this.incidents.values()); }
}
