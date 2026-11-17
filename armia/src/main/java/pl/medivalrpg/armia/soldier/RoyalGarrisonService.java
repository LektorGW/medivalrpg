// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.block.Block;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.logic.RoyalGarrisonRules;
import pl.medivalrpg.armia.logic.SiegeRules;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.service.Services;

/** Narrow main-thread deployment entry point for the authorised royal rebuild, never exposed as a player command. */
public final class RoyalGarrisonService {
    private final ArmyService army;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Runnable save;
    public RoyalGarrisonService(final ArmyService army,final Services services,final Supplier<ArmiaConfig> config,final Runnable save) {
        this.army=army;this.services=services;this.config=config;this.save=save;
    }
    private void mainThread() {if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Ewakuacja gwardii wymaga głównego wątku.");}
    public int begin() {
        this.mainThread();
        if(this.services.kraje().getCountry(RoyalGarrisonRules.COUNTRY).isEmpty()) throw new IllegalArgumentException("KRÓLESTWO nie istnieje.");
        int held=0;
        for(final Soldier soldier:this.army.registry().ofCountry(RoyalGarrisonRules.COUNTRY)) if(soldier.record().transportShip==null) {
            this.army.controller().holdRoyal(soldier);held++;
        }
        this.save.run();return held;
    }
    public int resume() {
        this.mainThread();int resumed=0;
        for(final Soldier soldier:this.army.registry().ofCountry(RoyalGarrisonRules.COUNTRY)) {
            if(!soldier.record().royalEvacuated || soldier.record().transportShip!=null) continue;
            final Pos at=soldier.record().spawnPos();if(at==null || !at.isChunkLoaded() || at.isSurface()) continue;
            final World world=Bukkit.getWorld(at.world());if(world==null) continue;
            final var post=new RoyalGarrisonRules.Post("odzysk_"+soldier.id(),soldier.record().type,at.x(),at.y(),at.z(),soldier.record().radius,at.yaw());
            try {this.validatePost(RoyalGarrisonRules.COUNTRY,world,post);} catch(final IllegalArgumentException unsafe) {continue;}
            soldier.record().royalEvacuated=false;this.army.controller().relocate(soldier,at);resumed++;
        }
        this.army.registry().markDirty();this.save.run();return resumed;
    }
    public int deploy(final String json) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Rozstawienie gwardii wymaga głównego wątku.");
        final var country=this.services.kraje().getCountry(RoyalGarrisonRules.COUNTRY).orElseThrow(()->new IllegalArgumentException("KRÓLESTWO nie istnieje."));
        if(country.npc()) throw new IllegalArgumentException("Projekt królewski wymaga kraju gracza.");
        final ArmiaConfig cfg=this.config.get();final Map<String,Integer> eras=new LinkedHashMap<>();
        cfg.units.forEach((id,unit)->eras.put(id,cfg.unitEra(id).era()));
        final var request=RoyalGarrisonRules.parse(json,country.capitalWorld(),eras);
        final World world=Bukkit.getWorld(request.world());
        if(world==null) throw new IllegalArgumentException("Świat projektu nie jest dostępny.");
        // Validate every point before moving, creating, retyping or assigning any soldier.
        for(final var post:request.posts()) this.validatePost(request.country(),world,post);
        for(final var post:request.siegePosts()) this.validatePost(request.country(),world,post);
        final List<RoyalGarrisonRules.Candidate> candidates=this.army.registry().ofCountry(request.country()).stream().map(s->{
            final var unit=cfg.unit(s.record().type);
            return new RoyalGarrisonRules.Candidate(s.id(),s.country(),s.record().type,cfg.unitEra(s.record().type).era(),
                unit!=null && unit.mounted(),unit==null || SiegeRules.engine(s.record().type)!=null,
                s.record().transportShip!=null,s.record().contractUntil>0,s.record().royalPost);
        }).toList();
        final var plan=RoyalGarrisonRules.plan(request,candidates,this.army.registry().size(),this.army.registry().count(request.country()),
            cfg.globalMax,Math.min(cfg.countryMax,this.army.countryCap(country)));
        final var siegePlan=RoyalGarrisonRules.siegePlan(request,candidates,this.army.registry().size()+plan.creates(),
            this.army.registry().count(request.country())+plan.creates(),cfg.globalMax,Math.min(cfg.countryMax,this.army.countryCap(country)));
        final List<RoyalGarrisonRules.Assignment> all=new ArrayList<>(plan.assignments());all.addAll(siegePlan.assignments());
        RoyalGarrisonRules.requireCoverage(this.army.registry().ofCountry(request.country()).stream().filter(s->s.record().royalEvacuated
            && s.record().transportShip==null).map(Soldier::id).toList(),all);
        final Set<UUID> assigned=new HashSet<>();
        for(final var assignment:all) {
            final var post=assignment.post();final Pos at=new Pos(request.world(),post.x(),post.y(),post.z(),post.yaw());
            Soldier soldier=assignment.soldier()==null?null:this.army.registry().get(assignment.soldier());
            if(soldier==null) soldier=this.army.create(request.country(),cfg.unit(post.type()),at,post.radius(),SoldierMode.GUARD,null,"minecraft:none");
            final var record=soldier.record();record.royalPost=post.id();record.mode=SoldierMode.GUARD;record.commander=null;
            record.post=at;record.last=at;record.radius=post.radius();record.hold=null;record.patrol=new ArrayList<>();record.patrolIndex=0;record.siege=false;
            record.royalEvacuated=false;
            if(soldier.body()!=null && soldier.body().isValid()) soldier.body().clearActiveItem();
            if(soldier.brain()!=null && soldier.brain().isValid()) soldier.brain().clearActiveItem();
            soldier.clearTarget();this.army.controller().relocate(soldier,at);assigned.add(soldier.id());
        }
        for(final Soldier soldier:this.army.registry().ofCountry(request.country())) if(!assigned.contains(soldier.id())
            && soldier.record().transportShip==null && soldier.record().royalPost!=null) soldier.record().royalPost=null;
        this.army.registry().markDirty();this.save.run();return plan.assignments().size();
    }
    private void validatePost(final UUID country,final World world,final RoyalGarrisonRules.Post post) {
        final Location at=new Location(world,post.x(),post.y(),post.z());
        if(!world.getWorldBorder().isInside(at) || post.y()<world.getMinHeight()+1 || post.y()+2>=world.getMaxHeight()
            || !this.services.kraje().getCountryAt(at).map(c->country.equals(c.id())).orElse(false))
            throw new IllegalArgumentException("Posterunek poza własnym krajem lub worldborder: "+post.id());
        final int x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        if(!world.isChunkLoaded(x>>4,z>>4)) throw new IllegalArgumentException("Wczytaj chunk posterunku przed rozstawieniem: "+post.id());
        final Block feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z),floor=world.getBlockAt(x,y-1,z);
        if(!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid() || !floor.getType().isSolid()
            || hazardous(feet.getType()) || hazardous(head.getType()) || hazardous(floor.getType()))
            throw new IllegalArgumentException("Niebezpieczna lub zajęta pozycja posterunku: "+post.id());
    }
    private static boolean hazardous(final Material material) {
        return material==Material.FIRE || material==Material.SOUL_FIRE || material==Material.MAGMA_BLOCK || material==Material.CACTUS
            || material==Material.CAMPFIRE || material==Material.SOUL_CAMPFIRE || material==Material.SWEET_BERRY_BUSH
            || material==Material.WITHER_ROSE || material==Material.POWDER_SNOW || material==Material.LAVA;
    }
}
