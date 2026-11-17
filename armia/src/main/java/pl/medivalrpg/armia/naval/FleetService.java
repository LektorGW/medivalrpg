// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import io.papermc.paper.entity.TeleportFlag;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import pl.medivalrpg.armia.config.*;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.*;
import pl.medivalrpg.kraje.api.LandingSupport;
import pl.medivalrpg.kraje.model.Rank;

/** Player-controlled naval transports. Every mutation and Bukkit operation runs on the main server thread. */
public final class FleetService implements Listener,LandingSupport {
    private record Delivery(UUID country,String world,Set<UUID> players,Set<UUID> soldiers,long at) { }
    private record Shot(UUID attacker,UUID target,Location start,Location end,BlockDisplay visual,int duration,int age) {
        Shot next() {return new Shot(attacker,target,start,end,visual,duration,age+1);}
    }
    private final JavaPlugin plugin;
    private final ArmyService army;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Runnable persistArmy;
    private final FleetStore store;
    private final Map<UUID,ShipRecord> ships=new LinkedHashMap<>();
    private final Map<UUID,FleetVisuals> live=new HashMap<>();
    private final Map<UUID,Delivery> deliveries=new HashMap<>();
    private final Map<UUID,Long> lastDamage=new HashMap<>();
    private final List<Shot> shots=new ArrayList<>();
    private final Set<UUID> adjusting=new HashSet<>(),exiting=new HashSet<>();
    private final Set<UUID> internalPassengerMoves=new HashSet<>();
    private final NamespacedKey shipKey,passengerKey,walkingKey;
    private final Map<UUID,Long> walkCooldown=new HashMap<>();
    private final Map<UUID,Long> passengerVersion=new HashMap<>();
    private final Map<UUID,Long> externalTeleportUntil=new HashMap<>();
    /** AI autopilot courses (NPC landings). Not persisted: a restart ends the operation and the AI unloads the cargo. */
    private static final class Course {
        final double x,z; float detour; int attempt; double bestDistance=Double.MAX_VALUE; long progressTick,detourTick; boolean arrived,stuck;
        Course(final double x,final double z,final long tick){this.x=x;this.z=z;this.progressTick=tick;}
    }
    public enum CourseState { NONE, SAILING, ARRIVED, STUCK, NOT_LIVE }
    private final Map<UUID,Course> courses=new HashMap<>();
    private BukkitTask task;
    private boolean available=true;
    private boolean healthDirty;
    private long ticks;

    public FleetService(final JavaPlugin plugin,final ArmyService army,final Services services,
                        final Supplier<ArmiaConfig> config,final Supplier<Messages> messages,final Runnable persistArmy) {
        this.plugin=plugin;this.army=army;this.services=services;this.config=config;this.messages=messages;this.persistArmy=persistArmy;
        this.shipKey=new NamespacedKey(plugin,"fleet_ship");this.passengerKey=new NamespacedKey(plugin,"fleet_passenger");
        this.walkingKey=new NamespacedKey(plugin,"fleet_walk_deck");
        this.store=new FleetStore(plugin.getDataFolder().toPath(),plugin.getLogger());
        try {for(final ShipRecord record:this.store.load()) this.ships.put(record.id,record);}
        catch(final RuntimeException e) {this.available=false;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Flota wyłączona: błędny zapis, nie nadpisuję danych",e);}
        final List<Soldier> orphanCargo=this.army.registry().all().stream().filter(s->s.record().transportShip!=null
            && !this.ships.containsKey(s.record().transportShip)).toList();
        if(!orphanCargo.isEmpty()) plugin.getLogger().severe("Kwarantanna transportu: "+orphanCargo.size()+" żołnierzy wskazuje brakujący statek. "
            +"Nie tworzę kopii ani nie teleportuję wojska. Administrator powinien odzyskać pasujący fleet.json z fleet.json.bak/kopii zapasowej; "
            +"identyfikatory statków: "+orphanCargo.stream().map(s->s.record().transportShip).distinct().limit(10).toList());
    }
    public void start() {
        for(final World world:Bukkit.getWorlds()) for(final Entity entity:world.getEntities()) this.cleanOrphan(entity);
        this.task=Bukkit.getScheduler().runTaskTimer(this.plugin,this::tick,1,1);
    }
    public void save() {if(this.available) this.store.saveAsync(this.ships.values());}
    private void persist() {this.persistArmy.run();}
    public void stop() {
        if(this.task!=null) this.task.cancel();
        this.save();
        for(final FleetVisuals visual:this.live.values()) {final ShipRecord ship=this.ships.get(this.shipId(visual.boat));for(final Player p:visual.riders())this.emergencyExit(p,ship,visual.boat.getLocation());visual.remove();}
        this.live.clear();this.shots.forEach(s->s.visual().remove());this.shots.clear();this.store.close();
    }
    private void reply(final Player player,final String key) {this.messages.get().send(player,key);}
    private void fail(final Player player,final String reason) {this.messages.get().send(player,"fleet-error",Placeholder.unparsed("reason",reason));}
    private UUID context(final Player player,final Rank rank) {
        if(!this.available) {this.fail(player,"Zapis floty wymaga naprawy przez administratora.");return null;}
        return this.army.context(player,rank).map(c->c.id()).orElse(null);
    }
    private UUID shipId(final Entity entity) {
        final String id=entity.getPersistentDataContainer().get(this.shipKey,PersistentDataType.STRING);
        if(id==null) return null;
        try{return UUID.fromString(id);}catch(final IllegalArgumentException e){return null;}
    }
    private Location location(final ShipRecord record) {return new Location(Bukkit.getWorld(record.world),record.x,record.y,record.z,record.yaw,0);}
    private List<Soldier> cargo(final UUID ship) {return this.army.registry().all().stream().filter(s->ship.equals(s.record().transportShip)).toList();}
    private FleetVisuals nearest(final Player player,final UUID country) {
        return this.live.entrySet().stream().filter(e->country.equals(this.ships.get(e.getKey()).country))
            .map(Map.Entry::getValue).filter(v->v.boat.isValid() && v.boat.getWorld()==player.getWorld()
                && v.boat.getLocation().distanceSquared(player.getLocation())<=12*12)
            .min(Comparator.comparingDouble(v->v.boat.getLocation().distanceSquared(player.getLocation()))).orElse(null);
    }
    private ShipRecord record(final FleetVisuals visual) {return this.ships.get(this.shipId(visual.boat));}
    private boolean own(final UUID country,final Player player){return this.services.kraje().getCountryOf(player.getUniqueId()).map(c->country.equals(c.id())).orElse(false);}
    private static DeckRules.Point point(final Location at){return new DeckRules.Point(at.getX(),at.getY(),at.getZ());}
    private static DeckRules.Frame frame(final ShipRecord ship){return new DeckRules.Frame(ship.x,ship.y,ship.z,ship.yaw);}
    private FleetVisuals aboard(final Player player){
        for(final FleetVisuals v:this.live.values())if(player.getVehicle()==v.helm||v.walkers.contains(player.getUniqueId()))return v;
        return null;
    }
    private void tagWalker(final Player player,final FleetVisuals visual){
        visual.walkers.add(player.getUniqueId());
        player.getPersistentDataContainer().set(this.passengerKey,PersistentDataType.STRING,this.record(visual).id.toString());
        player.getPersistentDataContainer().set(this.walkingKey,PersistentDataType.BYTE,(byte)1);
    }
    private void forget(final Player player){
        for(final FleetVisuals v:this.live.values())v.walkers.remove(player.getUniqueId());
        player.getPersistentDataContainer().remove(this.passengerKey);player.getPersistentDataContainer().remove(this.walkingKey);
        this.passengerVersion.merge(player.getUniqueId(),1L,Long::sum);
    }
    private boolean walkingTeleport(final Player player,final Location to){
        this.internalPassengerMoves.add(player.getUniqueId());
        try{return player.teleport(to,PlayerTeleportEvent.TeleportCause.PLUGIN,TeleportFlag.Relative.VELOCITY_X,TeleportFlag.Relative.VELOCITY_Y,TeleportFlag.Relative.VELOCITY_Z);}
        finally{this.internalPassengerMoves.remove(player.getUniqueId());}
    }
    private boolean deckSpace(final Location at){
        final World w=at.getWorld();if(at.getY()<w.getMinHeight()+1||at.getY()+1.8>=w.getMaxHeight())return false;
        for(double x:new double[]{at.getX()-.3,at.getX()+.3})for(double z:new double[]{at.getZ()-.3,at.getZ()+.3})
            if(!w.getWorldBorder().isInside(new Location(w,x,at.getY(),z))||!w.isChunkLoaded(((int)Math.floor(x))>>4,((int)Math.floor(z))>>4))return false;
        for(int x=(int)Math.floor(at.getX()-.299);x<=Math.floor(at.getX()+.299);x++)for(int z=(int)Math.floor(at.getZ()-.299);z<=Math.floor(at.getZ()+.299);z++)
            for(int y=at.getBlockY();y<=Math.floor(at.getY()+1.799);y++){
                final Block b=w.getBlockAt(x,y,z);if(!b.isPassable()||b.isLiquid()||b.getType()==Material.FIRE||b.getType()==Material.SOUL_FIRE)return false;
            }
        return true;
    }
    private boolean standOnDeck(final Player player,final FleetVisuals v){
        final Location to=v.entryPoint();to.setYaw(player.getYaw());to.setPitch(player.getPitch());
        if(!this.deckSpace(to))return false;
        this.exiting.add(player.getUniqueId());
        try{player.leaveVehicle();if(!this.walkingTeleport(player,to))return false;this.tagWalker(player,v);player.setFallDistance(0);return true;}
        finally{this.exiting.remove(player.getUniqueId());}
    }
    private boolean landAllowed(final UUID country,final Location location) {
        final UUID owner=this.services.kraje().getCountryAt(location).map(c->c.id()).orElse(null);
        return NavalRules.canLandOn(country,owner,owner!=null && this.services.kraje().isAtWar(country,owner));
    }
    private boolean safe(final UUID country,final World world,final int x,final int y,final int z) {
        if(y<world.getMinHeight()+1 || y+2>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4)||!world.getWorldBorder().isInside(new Location(world,x+.5,y,z+.5))) return false;
        final Block feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z),floor=world.getBlockAt(x,y-1,z);
        for(final Block cell:List.of(feet,head)) if(cell.getType()==Material.FIRE || cell.getType()==Material.SOUL_FIRE
            || cell.getType()==Material.SWEET_BERRY_BUSH || cell.getType()==Material.WITHER_ROSE || cell.getType()==Material.POWDER_SNOW) return false;
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            if(!world.isChunkLoaded((x+dx)>>4,(z+dz)>>4)) return false;
            for(int dy=-1;dy<=1;dy++) if(world.getBlockAt(x+dx,y+dy,z+dz).getType()==Material.LAVA) return false;
        }
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid() && floor.getType().isSolid()
            && floor.getType()!=Material.MAGMA_BLOCK && floor.getType()!=Material.CACTUS && floor.getType()!=Material.CAMPFIRE
            && floor.getType()!=Material.SOUL_CAMPFIRE && this.landAllowed(country,feet.getLocation());
    }
    private List<Location> shores(final UUID country,final Location near,final Location ship,final int radius,final int limit) {
        final List<Location> result=new ArrayList<>();final World world=near.getWorld();
        for(int r=0;r<=radius && result.size()<limit;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r || result.size()>=limit) continue;
            final int x=near.getBlockX()+dx,z=near.getBlockZ()+dz;
            final double sx=x+0.5-ship.getX(),sz=z+0.5-ship.getZ();if(sx*sx+sz*sz>24*24) continue;
            for(int dy=7;dy>=-3;dy--) {
                final int y=near.getBlockY()+dy;
                if(this.safe(country,world,x,y,z)) {result.add(new Location(world,x+0.5,y,z+0.5));break;}
            }
        }
        return result;
    }
    /** The full hull must remain over loaded water, with mast/deck clearance. No probing loads chunks. */
    private boolean hullClear(final ShipRecord record,final Location at) {
        final var type=NavalRules.type(record.type);final World world=at.getWorld();
        if(world==null || at.getY()<world.getMinHeight()+2 || at.getY()+12>=world.getMaxHeight()) return false;
        for(final var entry:this.live.entrySet()) if(!entry.getKey().equals(record.id) && entry.getValue().boat.getWorld()==world) {
            final double clearance=type.halfLength()+NavalRules.type(this.ships.get(entry.getKey()).type).halfLength();
            if(entry.getValue().boat.getLocation().distanceSquared(at)<clearance*clearance) return false;
        }
        for(double x=-type.halfWidth();x<=type.halfWidth()+0.01;x+=1) for(double z=-type.halfLength();z<=type.halfLength()+0.01;z+=1) {
            final Location point=FleetVisuals.local(at,x,0,z);final int bx=point.getBlockX(),bz=point.getBlockZ(),y=point.getBlockY();
            if(!world.isChunkLoaded(bx>>4,bz>>4)) return false;
            final Block surface=world.getBlockAt(bx,y,bz);
            if(surface.getType()!=Material.WATER && !(surface.isPassable() && !surface.isLiquid()
                && world.getBlockAt(bx,y-1,bz).getType()==Material.WATER)) return false;
            for(int h=1;h<=11;h++) if(!world.getBlockAt(bx,y+h,bz).isPassable() || world.getBlockAt(bx,y+h,bz).isLiquid()) return false;
        }
        return true;
    }
    public void build(final Player player,final String id) {
        final UUID country=this.context(player,this.config.get().commandRank);if(country==null) return;
        final var type=NavalRules.type(id);
        final int count=(int)this.ships.values().stream().filter(s->country.equals(s.country)).count();
        if(!NavalRules.canBuild(type,this.army.era(country),this.ships.size(),count)) {
            this.fail(player,"Nieznany typ, wymagana epoka albo limit: 3 statki kraju / 24 na serwerze.");return;
        }
        final var hit=player.rayTraceBlocks(12,FluidCollisionMode.ALWAYS);
        if(hit==null || hit.getHitBlock()==null || hit.getHitBlock().getType()!=Material.WATER) {this.fail(player,"Spójrz na szeroką, wolną wodę do 12 bloków od brzegu.");return;}
        final ShipRecord ship=new ShipRecord();ship.id=UUID.randomUUID();ship.country=country;ship.type=id;ship.world=player.getWorld().getName();
        final Location at=hit.getHitBlock().getLocation().add(0.5,0.85,0.5);at.setYaw(player.getLocation().getYaw());
        ship.x=at.getX();ship.y=at.getY();ship.z=at.getZ();ship.yaw=at.getYaw();ship.health=type.health();
        final UUID owner=this.services.kraje().getCountryAt(at).map(c->c.id()).orElse(null);
        if(owner!=null && !country.equals(owner)) {this.fail(player,"Statek można zwodować na własnej ziemi lub w dziczy.");return;}
        if(!this.hullClear(ship,at) || this.live.values().stream().anyMatch(v->v.boat.getWorld()==at.getWorld() && v.boat.getLocation().distanceSquared(at)<12*12)) {
            this.fail(player,"Za mało wolnej wody na cały kadłub/maszty lub drugi statek jest zbyt blisko.");return;
        }
        if(!this.services.skarbiec().withdraw(this.services.treasury(country),type.cost(),"Armia: budowa statku "+id).ok()) {this.fail(player,"Brak pieniędzy w skarbcu kraju.");return;}
        this.ships.put(ship.id,ship);
        try {this.live.put(ship.id,new FleetVisuals(ship,at,this.shipKey));}
        catch(final RuntimeException error) {this.ships.remove(ship.id);this.services.skarbiec().deposit(this.services.treasury(country),type.cost(),"Armia: zwrot nieudanej budowy statku");throw error;}
        this.persist();this.reply(player,"fleet-deck-built");
    }
    public void board(final Player player) {
        final UUID country=this.context(player,Rank.CITIZEN);if(country==null) return;
        final FleetVisuals current=this.aboard(player);
        if(current!=null){if(player.getVehicle()==current.helm)this.standOnDeck(player,current);return;}
        final FleetVisuals visual=this.nearest(player,country);if(visual==null) {this.fail(player,"Podejdź do własnego statku (12 bloków).");return;}
        if(player.isInsideVehicle()) {this.fail(player,"Najpierw opuść obecny pojazd.");return;}
        final ShipRecord ship=this.record(visual);
        if(visual.riders().size()>=NavalRules.type(ship.type).seats()){this.fail(player,"Pokład ma już komplet pasażerów.");return;}
        if(visual.riders().isEmpty() && this.cargo(ship.id).isEmpty()) ship.sailed=0;
        if(this.standOnDeck(player,visual)){this.reply(player,"fleet-deck-boarded");this.persist();}else this.fail(player,"Wejście na pokład jest zablokowane.");
    }
    public void steer(final Player player){
        final UUID country=this.context(player,this.config.get().commandRank);if(country==null)return;
        final FleetVisuals visual=this.aboard(player);
        if(visual==null||!country.equals(this.record(visual).country)){this.fail(player,"Najpierw wejdź na własny statek: /armia flota wejdz.");return;}
        if(visual.captain()==player)return;
        if(visual.captain()!=null){this.fail(player,"Ster jest zajęty.");return;}
        if(player.isInsideVehicle()){this.fail(player,"Najpierw opuść obecny pojazd.");return;}
        final Location helm=visual.helmPoint();helm.setYaw(visual.boat.getYaw());helm.setPitch(player.getPitch());
        if(!this.deckSpace(helm)||!this.walkingTeleport(player,helm)){this.fail(player,"Miejsce sternika jest zablokowane.");return;}
        this.internalPassengerMoves.add(player.getUniqueId());
        try{if(visual.helm.addPassenger(player)){visual.walkers.remove(player.getUniqueId());this.reply(player,"fleet-deck-captain");}}
        finally{this.internalPassengerMoves.remove(player.getUniqueId());}
    }
    public void leave(final Player player) {
        final UUID country=this.context(player,Rank.CITIZEN);if(country==null) return;
        final FleetVisuals visual=this.aboard(player);final UUID id=visual==null?null:this.record(visual).id;
        if(visual==null || !country.equals(this.ships.get(id).country)) {this.fail(player,"Nie jesteś pasażerem własnego statku.");return;}
        final List<Location> shore=this.shores(country,visual.boat.getLocation(),visual.boat.getLocation(),14,1);
        if(NavalRules.exitRoute(!shore.isEmpty())==NavalRules.ExitRoute.WATER) {
            this.emergencyExit(player,this.ships.get(id),visual.boat.getLocation(),true);
            this.reply(player,"fleet-left-water");this.persist();return;
        }
        this.exiting.add(player.getUniqueId());
        try{player.leaveVehicle();if(!player.teleport(shore.getFirst())){this.standOnDeck(player,visual);this.fail(player,"Teleport na brzeg został zablokowany.");return;}this.forget(player);}
        finally{this.exiting.remove(player.getUniqueId());}
        this.delivery(id,Set.of(player.getUniqueId()),Set.of());this.reply(player,"fleet-left");this.persist();
    }
    public void load(final Player player) {
        final UUID country=this.context(player,this.config.get().commandRank);if(country==null) return;
        final FleetVisuals visual=this.nearest(player,country);if(visual==null) {this.fail(player,"Podejdź do własnego statku.");return;}
        final ShipRecord ship=this.record(visual);int count=0,space=NavalRules.type(ship.type).soldiers()-this.cargo(ship.id).size();
        for(final Soldier soldier:this.army.squad(player)) {
            if(space<=0) break;
            if(!soldier.attached() || soldier.record().transportShip!=null || soldier.brain().getWorld()!=visual.boat.getWorld()
                || soldier.brain().getLocation().distanceSquared(visual.boat.getLocation())>12*12 || !soldier.brain().hasLineOfSight(visual.boat)) continue;
            this.army.controller().embark(soldier,ship.id);count++;space--;
        }
        this.messages.get().send(player,"fleet-loaded",Placeholder.unparsed("count",Integer.toString(count)));
        if(count>0) this.persist();
    }
    public void land(final Player player) {
        final UUID country=this.context(player,this.config.get().commandRank);if(country==null) return;
        final FleetVisuals visual=this.nearest(player,country);if(visual==null) {this.fail(player,"Podejdź do własnego statku.");return;}
        final var hit=player.rayTraceBlocks(24);
        if(hit==null || hit.getHitBlock()==null) {this.fail(player,"Spójrz na bezpieczny brzeg w zasięgu 24 bloków.");return;}
        final ShipRecord ship=this.record(visual);final List<Soldier> cargo=this.cargo(ship.id);
        final Location target=hit.getHitBlock().getLocation().add(0,1,0);
        final List<Location> spots=this.shores(country,target,visual.boat.getLocation(),5,cargo.size());
        final Set<UUID> delivered=new HashSet<>();
        for(int i=0;i<Math.min(cargo.size(),spots.size());i++) {final Soldier soldier=cargo.get(i);this.army.controller().disembark(soldier,spots.get(i));delivered.add(soldier.id());}
        this.delivery(ship.id,Set.of(),delivered);
        this.messages.get().send(player,"fleet-landed",Placeholder.unparsed("count",Integer.toString(delivered.size())));
        if(!delivered.isEmpty()) this.persist();
    }
    public void list(final Player player) {
        final UUID country=this.context(player,Rank.CITIZEN);if(country==null) return;
        this.reply(player,"fleet-list");
        for(final ShipRecord ship:this.ships.values()) if(country.equals(ship.country)) this.messages.get().send(player,"fleet-line",
            Placeholder.unparsed("id",ship.id.toString().substring(0,8)),Placeholder.unparsed("type",ship.type),
            Placeholder.unparsed("hp",Integer.toString((int)ship.health)),Placeholder.unparsed("cargo",Integer.toString(this.cargo(ship.id).size())),
            Placeholder.unparsed("pos",ship.world+" "+(int)ship.x+" "+(int)ship.z));
    }
    public void remove(final Player player) {
        final UUID country=this.context(player,Rank.RULER);if(country==null) return;
        final FleetVisuals visual=this.nearest(player,country);
        // A drained or filled harbour can prevent restoration: the ruler can still retire a nearby empty saved hull.
        final ShipRecord ship=visual!=null?this.record(visual):this.ships.values().stream()
            .filter(s->!this.live.containsKey(s.id) && country.equals(s.country) && player.getWorld().getName().equals(s.world)
                && this.location(s).distanceSquared(player.getLocation())<=12*12)
            .min(Comparator.comparingDouble(s->this.location(s).distanceSquared(player.getLocation()))).orElse(null);
        if(ship==null) {this.fail(player,"Podejdź do własnego statku lub jego zapisanej pozycji.");return;}
        if(visual!=null && !visual.riders().isEmpty() || !this.cargo(ship.id).isEmpty()) {this.fail(player,"Najpierw wyładuj żołnierzy i pasażerów.");return;}
        this.live.remove(ship.id);this.ships.remove(ship.id);this.deliveries.remove(ship.id);this.courses.remove(ship.id);if(visual!=null) visual.remove();this.persist();this.reply(player,"fleet-removed");
    }
    private void delivery(final UUID id,final Set<UUID> players,final Set<UUID> soldiers) {
        if(players.isEmpty() && soldiers.isEmpty()) return;
        final ShipRecord ship=this.ships.get(id);if(ship.sailed<24) return;
        final Delivery before=this.deliveries.get(id);final Set<UUID> allPlayers=new HashSet<>(players),allSoldiers=new HashSet<>(soldiers);
        if(before!=null && System.currentTimeMillis()-before.at()<=NavalRules.PROOF_MILLIS) {allPlayers.addAll(before.players());allSoldiers.addAll(before.soldiers());}
        this.deliveries.put(id,new Delivery(ship.country,ship.world,Set.copyOf(allPlayers),Set.copyOf(allSoldiers),System.currentTimeMillis()));
    }
    @Override public boolean canLand(final UUID country,final String world,final int cx,final int cz) {
        if(!this.available || !Bukkit.isPrimaryThread() || country==null || world==null) return false;
        final long now=System.currentTimeMillis();
        for(final var entry:this.live.entrySet()) {
            final ShipRecord ship=this.ships.get(entry.getKey());final FleetVisuals visual=entry.getValue();
            if(ship==null || !visual.boat.isValid() || !country.equals(ship.country) || !world.equals(ship.world)) continue;
            boolean force=this.cargo(ship.id).stream().anyMatch(s->country.equals(s.country())) || visual.riders().stream()
                .anyMatch(p->this.services.kraje().getCountryOf(p.getUniqueId()).map(c->country.equals(c.id())).orElse(false));
            long proofAt=now;final Delivery proof=this.deliveries.get(ship.id);
            if(!force && proof!=null && country.equals(proof.country()) && world.equals(proof.world())) {
                proofAt=proof.at();
                force=proof.players().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).anyMatch(p->p.getWorld().getName().equals(world)
                    && NavalRules.distanceToChunkSq(p.getX(),p.getZ(),cx,cz)<=48*48
                    && this.services.kraje().getCountryOf(p.getUniqueId()).map(c->country.equals(c.id())).orElse(false))
                    || proof.soldiers().stream().map(this.army.registry()::get).filter(Objects::nonNull).anyMatch(s->s.attached() && country.equals(s.country())
                        && s.brain().getWorld().getName().equals(world) && NavalRules.distanceToChunkSq(s.brain().getX(),s.brain().getZ(),cx,cz)<=48*48);
            }
            if(NavalRules.transportProof(true,true,visual.boat.getWorld().isChunkLoaded(visual.boat.getLocation().getBlockX()>>4,visual.boat.getLocation().getBlockZ()>>4),
                NavalRules.distanceToChunkSq(visual.boat.getX(),visual.boat.getZ(),cx,cz),ship.sailed,force,proofAt,now)) return true;
        }
        return false;
    }
    public void volley(final Player player) {
        final UUID country=this.context(player,this.config.get().commandRank);if(country==null) return;
        if(this.army.era(country)<2 || this.services.progress()==null || !this.services.progress().has(country,"proch")) {
            this.fail(player,"Armaty okrętowe wymagają renesansu i odkrycia prochu.");return;
        }
        final FleetVisuals source=this.nearest(player,country);if(source==null || !source.riders().contains(player)) {this.fail(player,"Wejdź na własną karawelę.");return;}
        final ShipRecord ship=this.record(source);final Location eye=player.getEyeLocation();final Vector direction=eye.getDirection();
        FleetVisuals target=null;double nearest=Double.MAX_VALUE;
        for(final var entry:this.live.entrySet()) {
            final ShipRecord enemy=this.ships.get(entry.getKey());final FleetVisuals other=entry.getValue();
            if(other==source || !other.boat.isValid() || other.boat.getWorld()!=eye.getWorld() || !NavalRules.enemy(country,enemy.country,this.services.kraje().isAtWar(country,enemy.country))) continue;
            final Vector aim=other.boat.getLocation().add(0,DeckRules.deckHeight(NavalRules.type(enemy.type))+.7,0).toVector().subtract(eye.toVector());final double distance=aim.lengthSquared();
            if(distance<nearest && distance<=48*48 && aim.normalize().dot(direction)>=0.96) {target=other;nearest=distance;}
        }
        if(target==null || !NavalRules.volley("karawela".equals(ship.type),true,nearest,ship.lastVolleyMillis,System.currentTimeMillis(),this.shots.size())) {
            this.fail(player,"Spójrz na wrogi statek do 48 bloków. Salwa: karawela, wojna, przeładowanie 10 s.");return;
        }
        if(!this.services.skarbiec().withdraw(this.services.treasury(country),NavalRules.VOLLEY_COST,"Armia: morska salwa karaweli").ok()) {this.fail(player,"Salwa kosztuje 30 md ze skarbca.");return;}
        final Location start=source.boat.getLocation().add(0,DeckRules.deckHeight(NavalRules.type(ship.type))+1,0),end=target.boat.getLocation().add(0,DeckRules.deckHeight(NavalRules.type(this.record(target).type))+.7,0);
        final BlockDisplay ball=start.getWorld().spawn(start,BlockDisplay.class,e->{e.setBlock(Material.IRON_BLOCK.createBlockData());e.setPersistent(false);e.setInvulnerable(true);e.setTeleportDuration(2);
            e.setTransformationMatrix(new org.joml.Matrix4f().scale(0.3f));e.getPersistentDataContainer().set(this.shipKey,PersistentDataType.STRING,ship.id.toString());});
        this.shots.add(new Shot(country,this.record(target).id,start,end,ball,Math.max(4,(int)Math.ceil(start.distance(end)/3)),0));
        ship.lastVolleyMillis=System.currentTimeMillis();start.getWorld().playSound(start,Sound.ENTITY_GENERIC_EXPLODE,1.4f,0.7f);
        start.getWorld().spawnParticle(Particle.LARGE_SMOKE,start,20,0.4,0.3,0.4,0.03);this.persist();this.reply(player,"fleet-fired");
    }
    // ------------------------------------------------------------------ NPC AI hooks (main thread)

    private void trackCourse(final ShipRecord ship){
        final Course c=this.courses.get(ship.id);if(c==null||c.arrived||c.stuck)return;
        if(pl.medivalrpg.armia.logic.NavalPlanner.arrived(ship.x,ship.z,c.x,c.z,10)){c.arrived=true;return;}
        final double d=Math.hypot(c.x-ship.x,c.z-ship.z);
        if(d<c.bestDistance-1.5){c.bestDistance=d;c.progressTick=this.ticks;if(c.detour!=0&&this.ticks-c.detourTick>60)c.detour=0;return;}
        if(this.ticks-c.progressTick>100){ // 5 s without progress: try a detour, at most 6 times, then give up
            c.attempt++;c.progressTick=this.ticks;c.detourTick=this.ticks;c.detour=pl.medivalrpg.armia.logic.NavalPlanner.detour(c.attempt);
            if(c.attempt>6)c.stuck=true;
        }
    }
    public boolean aiAvailable(){return this.available;}
    /** Live (simulated) position of a ship, null when its model is not loaded near players. */
    public Location aiShipLocation(final UUID ship){final FleetVisuals v=this.live.get(ship);return v==null||!v.boat.isValid()?null:v.boat.getLocation();}
    public int aiCargo(final UUID ship){return this.cargo(ship).size();}
    public int aiCapacity(final UUID ship){final ShipRecord r=this.ships.get(ship);return r==null?0:Math.max(0,NavalRules.type(r.type).soldiers()-this.cargo(ship).size());}
    public boolean aiExists(final UUID ship){return this.ships.containsKey(ship);}
    public int aiCountryShips(final UUID country){return (int)this.ships.values().stream().filter(s->country.equals(s.country)).count();}
    public boolean aiCanBuild(final UUID country,final String type){return this.available&&NavalRules.canBuild(NavalRules.type(type),this.army.era(country),this.ships.size(),this.aiCountryShips(country));}
    /** Idle own live ship (no players, no course) within {@code radius} of {@code near}. */
    public UUID aiIdleShip(final UUID country,final Location near,final double radius){
        UUID best=null;double bestD=radius*radius;
        for(final var e:this.live.entrySet()){final ShipRecord r=this.ships.get(e.getKey());final FleetVisuals v=e.getValue();
            if(r==null||!country.equals(r.country)||!v.boat.isValid()||v.boat.getWorld()!=near.getWorld()||!v.riders().isEmpty()||this.courses.containsKey(r.id))continue;
            final double d=v.boat.getLocation().distanceSquared(near);if(d<=bestD){bestD=d;best=r.id;}}
        return best;
    }
    /** Whether a hull of {@code type} fits at {@code at} (loaded water, clearance, no ship nearby). No side effects. */
    public boolean aiHullFits(final UUID country,final String type,final Location at){
        if(NavalRules.type(type)==null||at==null||at.getWorld()==null)return false;
        final ShipRecord probe=new ShipRecord();probe.id=new UUID(0,0);probe.country=country;probe.type=type;
        return this.hullClear(probe,at)&&this.live.values().stream().noneMatch(v->v.boat.getWorld()==at.getWorld()&&v.boat.getLocation().distanceSquared(at)<12*12);
    }
    /** Launches a ship for the AI (same rules and price as /armia flota zbuduj, paid from the treasury). Null when impossible. */
    public UUID aiBuild(final UUID country,final String id,final Location water,final float yaw){
        final var type=NavalRules.type(id);
        if(!this.aiCanBuild(country,id)||water==null||water.getWorld()==null)return null;
        final ShipRecord ship=new ShipRecord();ship.id=UUID.randomUUID();ship.country=country;ship.type=id;ship.world=water.getWorld().getName();
        final Location at=water.clone();at.setYaw(yaw);ship.x=at.getX();ship.y=at.getY();ship.z=at.getZ();ship.yaw=yaw;ship.health=type.health();
        final UUID owner=this.services.kraje().getCountryAt(at).map(c->c.id()).orElse(null);
        if(owner!=null&&!country.equals(owner))return null;
        if(!this.aiHullFits(country,id,at))return null;
        if(!this.services.skarbiec().withdraw(this.services.treasury(country),type.cost(),"Armia NPC: budowa statku "+id).ok())return null;
        this.ships.put(ship.id,ship);
        try{this.live.put(ship.id,new FleetVisuals(ship,at,this.shipKey));}
        catch(final RuntimeException error){this.ships.remove(ship.id);this.services.skarbiec().deposit(this.services.treasury(country),type.cost(),"Armia NPC: zwrot nieudanej budowy statku");return null;}
        this.persist();return ship.id;
    }
    /** Embarks one soldier of the ship's country: loaded, within 12 blocks and in sight of the hull, with free capacity. */
    public boolean aiEmbark(final UUID ship,final Soldier soldier){
        final ShipRecord r=this.ships.get(ship);final FleetVisuals v=this.live.get(ship);
        if(r==null||v==null||!r.country.equals(soldier.country())||!soldier.attached()||soldier.record().transportShip!=null||this.aiCapacity(ship)<=0)return false;
        if(soldier.brain().getWorld()!=v.boat.getWorld()||soldier.brain().getLocation().distanceSquared(v.boat.getLocation())>12*12||!soldier.brain().hasLineOfSight(v.boat))return false;
        if(this.cargo(ship).isEmpty()&&v.riders().isEmpty())r.sailed=0; // the landing proof counts this voyage only
        this.army.controller().embark(soldier,ship);this.persist();return true;
    }
    /** A safe shore spot near the live ship (soldiers gather there to embark / are unloaded there), null when none. */
    public Location aiShoreNear(final UUID ship,final int radius){
        final ShipRecord r=this.ships.get(ship);final FleetVisuals v=this.live.get(ship);if(r==null||v==null)return null;
        final List<Location> s=this.shores(r.country,v.boat.getLocation(),v.boat.getLocation(),Math.max(2,Math.min(14,radius)),1);
        return s.isEmpty()?null:s.getFirst();
    }
    public void aiSail(final UUID ship,final double x,final double z){this.courses.put(ship,new Course(x,z,this.ticks));}
    public void aiStop(final UUID ship){this.courses.remove(ship);final FleetVisuals v=this.live.get(ship);if(v!=null)v.boat.setVelocity(new Vector(0,v.boat.getVelocity().getY(),0));}
    public CourseState aiCourse(final UUID ship){
        final Course c=this.courses.get(ship);if(c==null)return CourseState.NONE;
        if(!this.live.containsKey(ship))return CourseState.NOT_LIVE;
        return c.arrived?CourseState.ARRIVED:c.stuck?CourseState.STUCK:CourseState.SAILING;
    }
    /**
     * Unloads the cargo on safe dry spots around {@code target} (own land, wilderness or land of a country at war — never
     * allies or neutrals) within 24 blocks of the ship, and records the landing proof for Kraje. Returns delivered ids.
     */
    public List<UUID> aiLand(final UUID ship,final Location target,final int radius){
        return this.aiLand(ship,target,radius,false);
    }
    /** {@code openSky}: only spots under the open sky (never inside a building) — long-range landings on player coasts. */
    public List<UUID> aiLand(final UUID ship,final Location target,final int radius,final boolean openSky){
        final ShipRecord r=this.ships.get(ship);final FleetVisuals v=this.live.get(ship);if(r==null||v==null||target==null)return List.of();
        final List<Soldier> cargo=this.cargo(ship);if(cargo.isEmpty())return List.of();
        final List<Location> spots=new ArrayList<>(this.shores(r.country,target,v.boat.getLocation(),Math.max(2,Math.min(8,radius)),openSky?cargo.size()*4:cargo.size()));
        if(openSky){
            spots.removeIf(l->l.getWorld().getHighestBlockYAt(l.getBlockX(),l.getBlockZ(),HeightMap.MOTION_BLOCKING_NO_LEAVES)>=l.getBlockY());
            while(spots.size()>cargo.size())spots.removeLast();
        }
        final Set<UUID> delivered=new HashSet<>();
        for(int i=0;i<Math.min(cargo.size(),spots.size());i++){this.army.controller().disembark(cargo.get(i),spots.get(i));delivered.add(cargo.get(i).id());}
        this.delivery(ship,Set.of(),delivered);if(!delivered.isEmpty())this.persist();
        return List.copyOf(delivered);
    }
    /**
     * Long-range landing: the expedition ship (already paid at departure) appears off the enemy coast after its voyage in
     * the background. Same hull/limit rules as a built ship; the water must be wilderness, own or of an enemy at war.
     * {@code sailed} is the real voyage distance (Kraje's landing proof needs at least 24 sailed blocks). Null when impossible.
     */
    public UUID aiMaterialize(final UUID country,final String id,final Location water,final float yaw,final double sailed){
        final var type=NavalRules.type(id);
        if(!this.available||type==null||water==null||water.getWorld()==null) return null;
        if(this.aiFreeSlots(country)<=0) return null; // era was checked when the ship was paid for at departure
        final Location at=water.clone();at.setYaw(yaw);
        if(!this.landAllowed(country,at)||!this.aiHullFits(country,id,at)) return null;
        final ShipRecord ship=new ShipRecord();ship.id=UUID.randomUUID();ship.country=country;ship.type=id;ship.world=at.getWorld().getName();
        ship.x=at.getX();ship.y=at.getY();ship.z=at.getZ();ship.yaw=yaw;ship.health=type.health();
        ship.sailed=Math.max(0,Math.min(100_000,Double.isFinite(sailed)?sailed:0));
        this.ships.put(ship.id,ship);
        try{this.live.put(ship.id,new FleetVisuals(ship,at,this.shipKey));}
        catch(final RuntimeException error){this.ships.remove(ship.id);return null;}
        at.getWorld().spawnParticle(Particle.SPLASH,at,30,2,0.3,3,0.1);
        this.persist();return ship.id;
    }
    /** Expedition ship sails home (background): only an empty hull; riders are put ashore/in the water first. */
    public boolean aiDismiss(final UUID ship){
        final ShipRecord r=this.ships.get(ship);if(r==null)return false;
        if(!this.cargo(ship).isEmpty())return false;
        final FleetVisuals v=this.live.remove(ship);
        if(v!=null){final Location at=v.boat.getLocation();for(final Player p:v.riders())this.emergencyExit(p,r,at);v.remove();
            at.getWorld().spawnParticle(Particle.SPLASH,at,30,2,0.3,3,0.1);}
        this.ships.remove(ship);this.deliveries.remove(ship);this.courses.remove(ship);this.persist();return true;
    }
    /** Hull of {@code type} fits at {@code at} for the expedition of {@code country} (water ownership included). */
    public boolean aiAnchorage(final UUID country,final String type,final Location at){
        return at!=null&&at.getWorld()!=null&&this.landAllowed(country,at)&&this.aiHullFits(country,type,at);
    }
    /** Ships that a country may still add (country/global fleet limits). */
    public int aiFreeSlots(final UUID country){
        return Math.max(0,Math.min(NavalRules.COUNTRY_MAX-this.aiCountryShips(country),NavalRules.GLOBAL_MAX-this.ships.size()));
    }
    /** Live ships (coastal defence): id, country, location and people/soldiers aboard. */
    public record LiveShip(UUID id,UUID country,Location at,int aboard){ }
    public List<LiveShip> aiLiveShips(){
        final List<LiveShip> out=new ArrayList<>();
        for(final var e:this.live.entrySet()){final ShipRecord r=this.ships.get(e.getKey());final FleetVisuals v=e.getValue();
            if(r!=null&&v.boat.isValid())out.add(new LiveShip(r.id,r.country,v.boat.getLocation(),this.cargo(r.id).size()+v.riders().size()));}
        return out;
    }

    private void tick() {
        if(!this.available) return;this.ticks++;
        if(this.ticks%20==0) for(final ShipRecord ship:new ArrayList<>(this.ships.values())) {
            if(this.services.kraje().getCountry(ship.country).isEmpty()) {
                final FleetVisuals orphan=this.live.remove(ship.id);
                if(orphan!=null) {for(final Player rider:orphan.riders()) this.emergencyExit(rider,ship,orphan.boat.getLocation());orphan.remove();}
                for(final Soldier soldier:this.cargo(ship.id)) this.army.handleDeath(soldier,null);
                this.ships.remove(ship.id);this.deliveries.remove(ship.id);this.courses.remove(ship.id);this.persist();continue;
            }
            final World world=Bukkit.getWorld(ship.world);
            if(this.live.containsKey(ship.id) || world==null || !world.isChunkLoaded(((int)Math.floor(ship.x))>>4,((int)Math.floor(ship.z))>>4)) continue;
            final Location at=this.location(ship);
            if(world.getPlayers().stream().anyMatch(p->p.getLocation().distanceSquared(at)<96*96) && this.hullClear(ship,at)) this.live.put(ship.id,new FleetVisuals(ship,at,this.shipKey));
        }
        for(final var entry:new ArrayList<>(this.live.entrySet())) {
            final ShipRecord ship=this.ships.get(entry.getKey());final FleetVisuals visual=entry.getValue();
            if(!visual.valid()) {this.live.remove(entry.getKey());for(final Player p:visual.riders())this.emergencyExit(p,ship,visual.boat.getLocation());visual.remove();continue;}
            final Location at=visual.boat.getLocation();
            final DeckRules.Frame before=frame(ship),after=visual.frame();
            final double moved=Math.hypot(at.getX()-ship.x,at.getZ()-ship.z);
            if(moved>3 || at.getWorld()!=Bukkit.getWorld(ship.world)) {this.rollback(ship,visual);continue;}
            if(moved>0.001 && (!visual.riders().isEmpty() || !this.cargo(ship.id).isEmpty())) ship.sailed=Math.min(100_000,ship.sailed+moved);
            ship.x=at.getX();ship.y=at.getY();ship.z=at.getZ();ship.yaw=at.getYaw();
            this.trackCourse(ship);
            final List<Player> movingRiders=visual.riders();movingRiders.forEach(p->this.internalPassengerMoves.add(p.getUniqueId()));
            final boolean changed=moved>.00001||Math.abs(at.getY()-before.y())>.00001||Math.abs(DeckRules.yawDelta(before.yaw(),after.yaw()))>.0001;
            try {if(changed||this.ticks%20==0)visual.sync(this.cargo(ship.id).size());} finally {movingRiders.forEach(p->this.internalPassengerMoves.remove(p.getUniqueId()));}
            this.carryWalkers(ship,visual,before,after,changed);
            for(final Player player:visual.riders()) {
                final boolean own=this.services.kraje().getCountryOf(player.getUniqueId()).map(c->ship.country.equals(c.id())).orElse(false);
                final boolean captain=player.getVehicle()==visual.helm;
                final boolean rank=this.services.kraje().getRank(player.getUniqueId()).orElse(Rank.CITIZEN).atLeast(this.config.get().commandRank);
                if(!own || captain && !rank) this.emergencyExit(player,ship,at);
                else player.getPersistentDataContainer().set(this.passengerKey,PersistentDataType.STRING,ship.id.toString());
            }
            this.admitWalkers(ship,visual,after);this.steerBoat(visual);
            if(this.ticks%20==0) for(final Soldier soldier:this.cargo(ship.id)) soldier.record().last=Pos.of(at);
        }
        this.tickShots();
        if(this.ticks%100==0 && this.healthDirty) {this.save();this.healthDirty=false;}
        if(this.ticks%600==0) {this.deliveries.values().removeIf(d->System.currentTimeMillis()-d.at()>NavalRules.PROOF_MILLIS);this.persist();}
        if(this.ticks%200==0){this.walkCooldown.entrySet().removeIf(e->e.getValue()<this.ticks);this.externalTeleportUntil.entrySet().removeIf(e->e.getValue()<this.ticks);}
    }
    private void carryWalkers(final ShipRecord ship,final FleetVisuals v,final DeckRules.Frame before,final DeckRules.Frame after,final boolean moved){
        final var type=NavalRules.type(ship.type);
        for(final UUID id:new ArrayList<>(v.walkers)){
            final Player p=Bukkit.getPlayer(id);
            if(p==null||!p.isOnline()||p.isDead()){v.walkers.remove(id);continue;}
            if(p.getWorld()!=v.boat.getWorld()||p.isInsideVehicle()||!DeckRules.onDeck(type,before,point(p.getLocation()))){
                final boolean shore=p.getWorld()==v.boat.getWorld()&&!p.isInsideVehicle()&&p.isOnGround()
                    &&this.safe(ship.country,p.getWorld(),p.getLocation().getBlockX(),p.getLocation().getBlockY(),p.getLocation().getBlockZ());
                this.forget(p);this.walkCooldown.put(id,this.ticks+30);if(shore)this.delivery(ship.id,Set.of(id),Set.of());continue;
            }
            if(!moved)continue;
            final Location old=p.getLocation();final DeckRules.Point carried=DeckRules.carry(before,after,point(old));
            final Location to=new Location(old.getWorld(),carried.x(),carried.y(),carried.z(),old.getYaw()+DeckRules.yawDelta(before.yaw(),after.yaw()),old.getPitch());
            if(!this.deckSpace(to)||!this.walkingTeleport(p,to)){this.forget(p);this.walkCooldown.put(id,this.ticks+30);}
        }
    }
    private void admitWalkers(final ShipRecord ship,final FleetVisuals v,final DeckRules.Frame at){
        final var type=NavalRules.type(ship.type);
        for(final Player p:v.boat.getWorld().getPlayers()){
            if(p.isDead()||p.isInsideVehicle()||v.walkers.contains(p.getUniqueId())||this.walkCooldown.getOrDefault(p.getUniqueId(),0L)>this.ticks)continue;
            if(!DeckRules.onDeck(type,at,point(p.getLocation())))continue;
            if(!this.own(ship.country,p)||v.riders().size()>=type.seats()){
                this.emergencyExit(p,ship,v.boat.getLocation(),true);this.walkCooldown.put(p.getUniqueId(),this.ticks+60);continue;
            }
            if(v.riders().isEmpty()&&this.cargo(ship.id).isEmpty())ship.sailed=0;this.tagWalker(p,v);
        }
    }
    private void steerBoat(final FleetVisuals v){
        final Player captain=v.captain();final Vector old=v.boat.getVelocity();
        final org.bukkit.Input input=captain==null?null:captain.getCurrentInput();
        final ShipRecord rec=this.record(v);final Course course=captain==null&&rec!=null?this.courses.get(rec.id):null;
        final HelmRules.Motion motion;
        if(course!=null && !course.arrived && !course.stuck){
            final boolean[] keys=pl.medivalrpg.armia.logic.NavalPlanner.helm(v.boat.getYaw(),v.boat.getX(),v.boat.getZ(),course.x,course.z,course.detour);
            motion=HelmRules.step(v.boat.getYaw(),true,keys[2],false,keys[0],keys[1],old.getX(),old.getZ());
        } else motion=HelmRules.step(v.boat.getYaw(),captain!=null,input!=null&&input.isForward(),input!=null&&input.isBackward(),
            input!=null&&input.isLeft(),input!=null&&input.isRight(),old.getX(),old.getZ());
        if(Math.abs(DeckRules.yawDelta(v.boat.getYaw(),motion.yaw()))<.001&&Math.hypot(motion.x(),motion.z())<.0001){v.boat.setVelocity(new Vector(0,old.getY(),0));return;}
        final Location turned=v.boat.getLocation();turned.setYaw(motion.yaw());
        if(!this.hullClear(this.record(v),turned)){v.boat.setVelocity(new Vector(0,old.getY(),0));return;}
        v.boat.setRotation(motion.yaw(),0);v.boat.setVelocity(new Vector(motion.x(),old.getY(),motion.z()));
    }
    private void rollback(final ShipRecord ship,final FleetVisuals visual) {
        final List<Player> movingRiders=visual.riders();movingRiders.forEach(p->this.internalPassengerMoves.add(p.getUniqueId()));
        this.adjusting.add(ship.id);
        try {visual.boat.setVelocity(new Vector());visual.boat.teleport(this.location(ship),TeleportFlag.EntityState.RETAIN_PASSENGERS);}
        finally {this.adjusting.remove(ship.id);movingRiders.forEach(p->this.internalPassengerMoves.remove(p.getUniqueId()));}
    }
    private void tickShots() {
        final Iterator<Shot> iterator=this.shots.iterator();final List<Shot> later=new ArrayList<>();
        while(iterator.hasNext()) {
            final Shot old=iterator.next();iterator.remove();final Shot shot=old.next();final double t=Math.min(1,shot.age()/(double)shot.duration());
            final Location before=shot.start().clone().add(shot.end().toVector().subtract(shot.start().toVector()).multiply(old.age()/(double)old.duration()));
            final Location next=shot.start().clone().add(shot.end().toVector().subtract(shot.start().toVector()).multiply(t));final World world=next.getWorld();
            if(!world.isChunkLoaded(next.getBlockX()>>4,next.getBlockZ()>>4)) {shot.visual().remove();continue;}
            final Vector step=next.toVector().subtract(before.toVector());final double length=step.length();boolean loaded=true;
            for(double d=0;d<=length;d+=0.5) {final Location check=before.clone().add(step.clone().multiply(length<0.001?0:d/length));if(!world.isChunkLoaded(check.getBlockX()>>4,check.getBlockZ()>>4)) {loaded=false;break;}}
            if(!loaded || !shot.visual().isValid()) {shot.visual().remove();continue;}
            final var collision=length<0.001?null:world.rayTraceBlocks(before,step.clone().normalize(),length,FluidCollisionMode.NEVER,true);
            if(collision!=null) {world.spawnParticle(Particle.SMOKE,next,8,0.2,0.2,0.2,0.01);shot.visual().remove();continue;}
            if(t>=1) {
                final ShipRecord target=this.ships.get(shot.target());final FleetVisuals visual=this.live.get(shot.target());
                if(target!=null && visual!=null && visual.boat.getWorld()==world && visual.boat.getLocation().distanceSquared(next)<=16
                    && NavalRules.enemy(shot.attacker(),target.country,this.services.kraje().isAtWar(shot.attacker(),target.country))) this.damage(target,30);
                world.spawnParticle(Particle.EXPLOSION,next,1);world.playSound(next,Sound.ENTITY_GENERIC_EXPLODE,1,1);shot.visual().remove();
            } else {shot.visual().teleport(next);world.spawnParticle(Particle.SMOKE,next,1);later.add(shot);}
        }
        this.shots.addAll(later);
    }
    private void damage(final ShipRecord ship,final double amount) {
        ship.health-=amount;
        if(ship.health>0) {this.healthDirty=true;return;}
        final FleetVisuals visual=this.live.remove(ship.id);final Location at=visual==null?this.location(ship):visual.boat.getLocation();
        if(visual!=null) for(final Player player:visual.riders()) this.emergencyExit(player,ship,at);
        int lost=0;final List<Soldier> cargo=this.cargo(ship.id);final List<Location> shore=this.shores(ship.country,at,at,16,cargo.size());
        for(int n=0;n<cargo.size();n++) {
            final Soldier soldier=cargo.get(n);
            if(n>=shore.size()) {this.army.handleDeath(soldier,null);lost++;} else this.army.controller().disembark(soldier,shore.get(n));
        }
        if(visual!=null) visual.remove();this.ships.remove(ship.id);this.deliveries.remove(ship.id);
        at.getWorld().spawnParticle(Particle.SPLASH,at,40,2,0.3,3,0.1);
        this.army.notifyCountry(ship.country,"fleet-sunk",Placeholder.unparsed("lost",Integer.toString(lost)));this.persist();
    }
    private void emergencyExit(final Player player,final ShipRecord ship,final Location at) {
        this.emergencyExit(player,ship,at,false);
    }
    private void emergencyExit(final Player player,final ShipRecord ship,final Location at,final boolean waterOnly) {
        final UUID country=this.services.kraje().getCountryOf(player.getUniqueId()).map(c->c.id()).orElse(ship.country);
        this.exiting.add(player.getUniqueId());
        try{player.leaveVehicle();this.forget(player);this.walkCooldown.put(player.getUniqueId(),this.ticks+40);
            final List<Location> shores=waterOnly?List.of():this.shores(country,at,at,16,1);
            player.teleport(shores.isEmpty()?this.waterExit(ship,at):shores.getFirst());player.setFallDistance(0);this.waterProtection(player);
        }finally{this.exiting.remove(player.getUniqueId());}
    }
    private void waterProtection(final Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,600,0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,400,0));player.setFallDistance(0);
    }
    private Location waterExit(final ShipRecord ship,final Location at) {
        final var type=NavalRules.type(ship.type);final World world=at.getWorld();
        final double w=type.halfWidth()+1,l=type.halfLength()+1;
        for(final double[] side:new double[][]{{-w,0},{w,0},{0,-l},{0,l},{0,0}}) {
            final Location beside=FleetVisuals.local(at,side[0],0,side[1]);
            if(!world.getWorldBorder().isInside(beside) || !world.isChunkLoaded(beside.getBlockX()>>4,beside.getBlockZ()>>4)) continue;
            for(int dy=1;dy>=-1;dy--) {
                final int y=at.getBlockY()+dy;
                if(y<world.getMinHeight() || y+2>=world.getMaxHeight()) continue;
                final Block below=world.getBlockAt(beside.getBlockX(),y,beside.getBlockZ());
                final Block feet=below.getRelative(0,1,0),head=below.getRelative(0,2,0);
                if(below.getType()!=Material.WATER || !feet.isPassable() || !head.isPassable()
                    || feet.getType()==Material.LAVA || head.getType()==Material.LAVA || feet.getType()==Material.FIRE
                    || head.getType()==Material.FIRE || feet.getType()==Material.SOUL_FIRE || head.getType()==Material.SOUL_FIRE) continue;
                beside.setY(y+1.05);return beside;
            }
        }
        // Even a changed/blocked harbour cannot trap a rider: the boat centre was its last occupied location.
        return at.clone().add(0,1,0);
    }
    private void cleanOrphan(final Entity entity) {if(entity.getPersistentDataContainer().has(this.shipKey,PersistentDataType.STRING)) entity.remove();}
    @EventHandler public void loaded(final EntitiesLoadEvent event) {event.getEntities().forEach(this::cleanOrphan);}
    @EventHandler public void unloading(final EntitiesUnloadEvent event) {
        final Set<UUID> affected=new HashSet<>();
        for(final Entity entity:event.getEntities()){
            final UUID id=this.shipId(entity);final FleetVisuals visual=id==null?null:this.live.get(id);
            if(visual!=null&&visual.contains(entity))affected.add(id);
        }
        for(final UUID id:affected){final FleetVisuals visual=this.live.remove(id);
            if(visual!=null) {final ShipRecord ship=this.ships.get(id);for(final Player p:visual.riders())this.emergencyExit(p,ship,visual.boat.getLocation());visual.remove();this.save();}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entering(final VehicleEnterEvent event) {
        final UUID id=this.shipId(event.getVehicle());if(id==null) return;
        event.setCancelled(true); // The hull has no player seats; steering uses the separate helm.
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void moving(final VehicleMoveEvent event) {
        final UUID id=this.shipId(event.getVehicle());if(id==null || this.adjusting.contains(id)) return;
        final ShipRecord ship=this.ships.get(id);final FleetVisuals visual=this.live.get(id);if(ship==null || visual==null) return;
        if(event.getTo().getWorld()!=event.getFrom().getWorld() || !this.hullClear(ship,event.getTo()) || event.getTo().distanceSquared(event.getFrom())>9) this.rollback(ship,visual);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void dismount(final EntityDismountEvent event) {
        final UUID id=this.shipId(event.getDismounted());if(id==null || !(event.getEntity() instanceof Player player) || player.isDead() || this.exiting.contains(player.getUniqueId())||this.internalPassengerMoves.contains(player.getUniqueId())||this.externalTeleportUntil.getOrDefault(player.getUniqueId(),0L)>this.ticks) return;
        final ShipRecord ship=this.ships.get(id);if(ship==null) return;
        final FleetVisuals visual=this.live.get(id);if(visual==null||event.getDismounted()!=visual.helm)return;
        final long version=this.passengerVersion.getOrDefault(player.getUniqueId(),0L);
        Bukkit.getScheduler().runTask(this.plugin,()->{
            if(!player.isOnline()||player.isDead()||this.live.get(id)!=visual||player.getWorld()!=visual.boat.getWorld()
                ||this.passengerVersion.getOrDefault(player.getUniqueId(),0L)!=version||player.isInsideVehicle())return;
            if(player.getLocation().distanceSquared(visual.helmPoint())>16*16)return;
            if(this.standOnDeck(player,visual))this.reply(player,"fleet-helm-released");
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleporting(final PlayerTeleportEvent event) {
        final Player player=event.getPlayer();final UUID id=player.getUniqueId();
        final FleetVisuals ship=this.aboard(player);
        if(!NavalRules.releaseForTeleport(ship!=null,player.getPersistentDataContainer().has(this.passengerKey,PersistentDataType.STRING),
            this.internalPassengerMoves.contains(id),this.exiting.contains(id))) return;
        this.forget(player);this.walkCooldown.put(id,this.ticks+40);this.externalTeleportUntil.put(id,this.ticks+2);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void died(final PlayerDeathEvent event) {
        final Player player=event.getEntity();this.exiting.add(player.getUniqueId());
        this.forget(player);player.leaveVehicle();
    }
    @EventHandler public void respawned(final PlayerRespawnEvent event) {
        this.exiting.remove(event.getPlayer().getUniqueId());this.forget(event.getPlayer());
    }
    @EventHandler public void worldChanged(final PlayerChangedWorldEvent event){this.forget(event.getPlayer());this.walkCooldown.put(event.getPlayer().getUniqueId(),this.ticks+40);}
    @EventHandler public void quit(final PlayerQuitEvent event) {final Player p=event.getPlayer();this.exiting.add(p.getUniqueId());for(final FleetVisuals v:this.live.values())v.walkers.remove(p.getUniqueId());this.passengerVersion.merge(p.getUniqueId(),1L,Long::sum);}
    @EventHandler public void joined(final PlayerJoinEvent event) {
        this.exiting.remove(event.getPlayer().getUniqueId());
        // Restore support before gravity can drop a returning deck walker into the sea.
        final Player returning=event.getPlayer();
        final String saved=returning.getPersistentDataContainer().get(this.passengerKey,PersistentDataType.STRING);
        if(this.available&&saved!=null)try{
            final UUID id=UUID.fromString(saved);final ShipRecord record=this.ships.get(id);
            if(record!=null&&this.own(record.country,returning)&&returning.getWorld().getName().equals(record.world)
                &&returning.getLocation().distanceSquared(this.location(record))<=12*12){
                FleetVisuals restored=this.live.get(id);
                if(restored==null&&this.hullClear(record,this.location(record))){restored=new FleetVisuals(record,this.location(record),this.shipKey);this.live.put(id,restored);}
                if(restored!=null&&DeckRules.onDeck(NavalRules.type(record.type),restored.frame(),point(returning.getLocation())))this.tagWalker(returning,restored);
            }
        }catch(IllegalArgumentException ignored){ }
        Bukkit.getScheduler().runTaskLater(this.plugin,()->{
            final Player player=event.getPlayer();if(!player.isOnline() || player.isInsideVehicle()) return;
            final String id=player.getPersistentDataContainer().get(this.passengerKey,PersistentDataType.STRING);
            if(id==null) return;
            FleetVisuals ship=null;try{ship=this.live.get(UUID.fromString(id));}catch(IllegalArgumentException ignored){ }
            if(ship!=null&&this.own(this.record(ship).country,player)&&player.getWorld()==ship.boat.getWorld()){
                final boolean legacy=!player.getPersistentDataContainer().has(this.walkingKey,PersistentDataType.BYTE);
                if(DeckRules.onDeck(NavalRules.type(this.record(ship).type),ship.frame(),point(player.getLocation())))this.tagWalker(player,ship);
                else if(legacy&&player.getLocation().distanceSquared(ship.boat.getLocation())<=12*12)this.standOnDeck(player,ship);
                else this.forget(player);
            }else this.forget(player);
        },30);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hit(final VehicleDamageEvent event) {
        final UUID id=this.shipId(event.getVehicle());if(id==null) return;event.setCancelled(true);
        Entity source=event.getAttacker();if(source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source=shooter;
        final UUID attacker=source instanceof Player player?this.services.kraje().getCountryOf(player.getUniqueId()).map(c->c.id()).orElse(null)
            :source==null?null:this.army.controller().soldierOf(source).map(Soldier::country).orElse(null);
        final ShipRecord ship=this.ships.get(id);final long now=System.currentTimeMillis();
        if(ship!=null && NavalRules.enemy(attacker,ship.country,attacker!=null && this.services.kraje().isAtWar(attacker,ship.country))
            && now-this.lastDamage.getOrDefault(id,0L)>=500) {this.lastDamage.put(id,now);this.damage(ship,Math.max(1,Math.min(10,event.getDamage())));}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void destroy(final VehicleDestroyEvent event) {if(this.shipId(event.getVehicle())!=null) event.setCancelled(true);}
}
