// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Bisected;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.SiegeRules;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.visual.ModelCatalog;
import pl.medivalrpg.armia.visual.SiegeVisuals;

/**
 * Engine models, bounded swept artillery projectiles and ram strikes. No vanilla destructive explosion is created.
 * The look comes from {@link SiegeVisuals} (animated BetterModel trebusz/katapulta/taran/armata, synchronised with
 * {@link #fire} and with the ram strike); without BetterModel the old vanilla BlockDisplay model is drawn.
 *
 * <p>Ram ({@code taran}): it never shoots. An order ({@link #orderRam}) names a block of an actively besieged enemy
 * chunk; the crew drives until the model head (≈{@code head-reach} blocks ahead) touches it, faces it and strikes.
 * The strike clip {@code uderzenie} starts on the decision tick and the blocks are hit on its impact frame. Each block
 * of the 3 × 3 face is checked exactly like an artillery impact (same chunk, active enemy siege, KrajeApi.canSiegeBreak,
 * no containers, liquids or unbreakable blocks) and wears down by the configured strength for wood, stone and
 * fortifications; at most {@code blocks-per-strike} blocks break per strike, without drops or block physics.
 */
public final class SiegeEngineService {
    private static final int MAX_FLIGHTS = 64;
    private record Model(String type, List<BlockDisplay> parts) { }
    private static final class Flight {
        final Soldier soldier;
        final SiegeRules.Engine engine;
        final SiegeRules.Ammo ammo;
        final Set<UUID> victims;
        final Location start;
        final Location end;
        final BlockDisplay display;
        final double damage;
        final int duration;
        final LivingEntity victim;
        Location previous;
        int age;
        Flight(final Soldier soldier, final SiegeRules.Engine engine, final Location start, final Location end,
               final BlockDisplay display, final double damage, final LivingEntity victim,
               final SiegeRules.Ammo ammo, final Set<UUID> victims) {
            this.soldier=soldier; this.engine=engine; this.start=start; this.end=end; this.previous=start.clone();
            this.display=display; this.damage=damage; this.victim=victim;
            this.ammo=ammo; this.victims=victims;
            this.duration=Math.max(4,Math.min(45,(int)Math.ceil(start.distance(end)/(ammo==SiegeRules.Ammo.GRAPESHOT?2.8:engine.cannon()?2.2:1.3))));
        }
    }
    private final SoldierController controller;
    private final Services services;
    private final Keys keys;
    private final Map<UUID,Model> models=new HashMap<>();
    private final List<Flight> flights=new ArrayList<>();
    private final SiegeVisuals visuals;

    // ---- ram state (transient: a restart drops orders, the AI re-issues them)
    private static final class RamOrder {
        Location target;
        final long until;
        final SoldierMode mode;
        final UUID commander;
        int depth;
        RamOrder(final Location target, final long until, final SoldierMode mode, final UUID commander) {
            this.target=target; this.until=until; this.mode=mode; this.commander=commander;
        }
    }
    private record Strike(Soldier soldier, RamOrder order, int[] facing, long at, double x, double z) { }
    private record Wear(Material type, double amount, long tick) { }
    private final Map<UUID,RamOrder> ramOrders=new HashMap<>();
    private final List<Strike> strikes=new ArrayList<>();
    private final Map<String,Wear> wear=new HashMap<>();
    private int ramStrikes, ramBroken;
    private long lastRamImpact=-1;

    SiegeEngineService(final SoldierController controller, final Services services, final Keys keys) {
        this.controller=controller; this.services=services; this.keys=keys;
        this.visuals=new SiegeVisuals(keys);
    }

    /** Model layer (diagnostics, tests). */
    public SiegeVisuals visuals() { return this.visuals; }

    public boolean isEngine(final Soldier soldier) { return SiegeRules.engine(soldier.record().type)!=null; }

    /** Catapult/cannon: answers {@code /armia ostrzal} and AI salvoes. */
    public boolean isShooter(final Soldier soldier) { return SiegeRules.shoots(soldier.record().type); }

    public boolean isRam(final Soldier soldier) { return SiegeRules.ram(soldier.record().type); }

    /** Only a currently running enemy siege permits a commander to select terrain. */
    public boolean mayBombard(final UUID country, final Location target) {
        final var api=this.services.kraje();
        final var owner=api.getCountryAt(target.getWorld().getName(),target.getBlockX()>>4,target.getBlockZ()>>4).orElse(null);
        final var siege=api.getSiege(target.getWorld().getName(),target.getBlockX()>>4,target.getBlockZ()>>4).orElse(null);
        return owner!=null && siege!=null && SiegeRules.enemySiege(country,owner.id(),siege.attacker(),siege.defender(),
            api.isAtWar(country,owner.id()),siege.endsAt(),System.currentTimeMillis());
    }

    /** Return false without consuming ammunition when range, reload, protection or treasury prevents the shot. */
    public boolean fire(final Soldier soldier, final UnitType unit, final Location target, final LivingEntity victim,
                        final double damage) {
        final var engine=SiegeRules.engine(soldier.record().type);
        final var ammo=SiegeRules.ammunition(soldier.record().type,soldier.record().siegeAmmo);
        if (engine==null || engine.ram() || ammo==null || !soldier.attached() || soldier.brain.getWorld()!=target.getWorld()) return false;
        if (victim==null ? !this.mayBombard(soldier.country(),target) : !this.controller.orderable(soldier,victim)) return false;
        if (victim==null && ammo.breaksBlocks() && !this.services.kraje().canSiegeBreak(soldier.country(),target.getWorld().getName(),
            target.getBlockX(),target.getBlockY(),target.getBlockZ())) return false;
        if (ammo==SiegeRules.Ammo.GRAPESHOT && victim!=null && !this.enemy(soldier,victim)) return false;
        final Location start=soldier.brain.getLocation().add(0,1.2,0);
        final double distance=start.distanceSquared(target);
        if (!SiegeRules.ready(this.controller.tick(),soldier.nextAttackTick,this.flights.size(),MAX_FLIGHTS,
            true,distance,Math.min(unit.range(),ammo.range())) || !SiegeRules.fitsVolley(this.flights.size(),MAX_FLIGHTS,ammo)) return false;
        final World world=start.getWorld();
        if (!world.isChunkLoaded(target.getBlockX()>>4,target.getBlockZ()>>4)) return false;
        if (!this.services.skarbiec().withdraw(this.services.treasury(soldier.country()),ammo.cost(),
            "Armia: amunicja "+soldier.record().type+" / "+ammo.id()).ok()) return false;
        final Vector facing=target.toVector().subtract(start.toVector()).setY(0);
        if (facing.lengthSquared()>0.001) {
            start.setDirection(facing);
            soldier.brain.setRotation(start.getYaw(),0);
            start.add(facing.normalize().multiply(1.5));
        }
        final Set<UUID> victims=new HashSet<>();
        final Vector forward=target.toVector().subtract(start.toVector()).normalize();
        Vector right=forward.clone().crossProduct(new Vector(0,1,0));
        if (right.lengthSquared()<0.0001) right=new Vector(1,0,0); else right.normalize();
        final Vector up=right.clone().crossProduct(forward).normalize();
        final double spread=start.distance(target)*0.14;
        for (int pellet=0;pellet<ammo.pellets();pellet++) {
            final Location end=target.clone();
            if (ammo==SiegeRules.Ammo.GRAPESHOT && pellet>0) {
                final double angle=(pellet-1)*Math.PI/2;
                end.add(right.clone().multiply(Math.cos(angle)*spread)).add(up.clone().multiply(Math.sin(angle)*spread));
            }
            final BlockDisplay display=world.spawn(start,BlockDisplay.class,e->{
                e.setBlock((ammo==SiegeRules.Ammo.STONE?Material.COBBLESTONE:Material.IRON_BLOCK).createBlockData());
                e.setTransformationMatrix(new Matrix4f().scale(ammo==SiegeRules.Ammo.GRAPESHOT?0.12f:engine.cannon()?0.27f:0.42f));
                e.setPersistent(false); e.setInvulnerable(true); e.setTeleportDuration(1);
                this.keys.tag(e,soldier.id(),soldier.country(),Keys.PART_SIEGE_SHOT);
            });
            this.flights.add(new Flight(soldier,engine,start.clone(),end,display,damage*ammo.damageShare(),victim,ammo,victims));
        }
        soldier.nextAttackTick=this.controller.tick()+Math.max(engine.reloadTicks(),unit.cooldownTicks());
        // The shot clip releases on its first frames: the model throws on the same tick as the projectile starts.
        this.visuals.shot(soldier,this.controller.tick(),soldier.nextAttackTick-this.controller.tick());
        world.playSound(start,engine.cannon()?Sound.ENTITY_GENERIC_EXPLODE:Sound.BLOCK_WOOD_BREAK,1.2f,engine.cannon()?0.8f:0.6f);
        world.spawnParticle(engine.cannon()?Particle.LARGE_SMOKE:Particle.CLOUD,start,12,0.25,0.2,0.25,0.03);
        return true;
    }

    void tick(final Iterable<Soldier> soldiers) {
        for (final Soldier soldier:soldiers) {
            if (soldier.attached() && this.isEngine(soldier)) {
                if (this.controller.tick()%3==0) this.sync(soldier);
            }
            else this.removeModel(soldier.id());
        }
        // Death/dismissal removes registry entries before the following tick.
        this.visuals.retain(this.controller::ownsId);
        this.ramOrders.keySet().removeIf(id->!this.controller.ownsId(id));
        this.tickStrikes();
        this.models.keySet().removeIf(id->{
            final Model model=this.models.get(id);
            if (this.controller.ownsId(id)) return false;
            model.parts().forEach(BlockDisplay::remove); return true;
        });
        final Iterator<Flight> iterator=this.flights.iterator();
        while (iterator.hasNext()) {
            final Flight f=iterator.next();
            if (!f.soldier.attached() || !f.display.isValid()) { f.display.remove(); iterator.remove(); continue; }
            final double t=Math.min(1,++f.age/(double)f.duration);
            final Location next=f.start.clone().add(f.end.toVector().subtract(f.start.toVector()).multiply(t));
            next.add(0,SiegeRules.arc(t,f.ammo==SiegeRules.Ammo.GRAPESHOT?0.05:f.engine.cannon()?0.5:Math.min(12,f.start.distance(f.end)*0.22)),0);
            final World world=next.getWorld();
            if (next.getY()<world.getMinHeight() || next.getY()>=world.getMaxHeight()
                || !world.isChunkLoaded(next.getBlockX()>>4,next.getBlockZ()>>4)) {
                f.display.remove(); iterator.remove(); continue;
            }
            final Vector step=next.toVector().subtract(f.previous.toVector());
            final double length=step.length();
            boolean loaded=true;
            for (double d=0;d<=length;d+=0.5) {
                final Location check=f.previous.clone().add(step.clone().multiply(length<0.0001?0:d/length));
                if (!world.isChunkLoaded(check.getBlockX()>>4,check.getBlockZ()>>4)) {loaded=false;break;}
            }
            if (!loaded) {f.display.remove();iterator.remove();continue;}
            final var collision=length<0.0001?null:world.rayTraceBlocks(f.previous,step.clone().normalize(),length,
                FluidCollisionMode.ALWAYS,true);
            if (f.ammo==SiegeRules.Ammo.GRAPESHOT && length>0.0001) {
                final double unobstructed=collision==null?length:f.previous.toVector().distance(collision.getHitPosition());
                final var entityHit=unobstructed<0.0001?null:world.rayTraceEntities(f.previous,step.clone().normalize(),unobstructed,0.16,
                    e->e instanceof LivingEntity living && this.enemy(f.soldier,living)
                        && !f.victims.contains(this.controller.resolveTarget(living).getUniqueId()));
                if (entityHit!=null && entityHit.getHitEntity() instanceof LivingEntity living) {
                    final LivingEntity enemy=this.controller.resolveTarget(living);
                    if (SiegeRules.registerVictim(f.victims,enemy.getUniqueId())) this.hurt(f,enemy);
                    world.spawnParticle(Particle.CRIT,entityHit.getHitPosition().toLocation(world),5,0.1,0.1,0.1,0.02);
                    f.display.remove();iterator.remove();continue;
                }
            }
            if (collision!=null || t>=1) {
                final Location impact=collision==null?next:collision.getHitPosition().toLocation(world);
                this.impact(f,impact,collision==null?null:collision.getHitBlock());
                f.display.remove(); iterator.remove();
            } else {
                f.display.teleport(next); f.previous=next;
                if (f.age%2==0) world.spawnParticle(f.ammo==SiegeRules.Ammo.GRAPESHOT?Particle.CRIT:
                    f.ammo==SiegeRules.Ammo.STONE?Particle.CLOUD:Particle.SMOKE,next,1,0,0,0,0);
            }
        }
    }

    private void impact(final Flight flight, final Location point, final Block direct) {
        final World world=point.getWorld();
        if (flight.ammo==SiegeRules.Ammo.GRAPESHOT) {
            world.spawnParticle(Particle.CRIT,point,5,0.1,0.1,0.1,0.02);
            return; // Grapeshot can never enter the terrain destruction branch.
        }
        world.playSound(point,Sound.ENTITY_GENERIC_EXPLODE,1.2f,1.1f);
        world.spawnParticle(Particle.EXPLOSION,point,1);
        // Only the intended living target can take projectile damage; no neutral splash damage or player knockback.
        if (flight.victim!=null && flight.victim.isValid() && flight.victim.getWorld()==world
            && flight.victim.getLocation().distanceSquared(point)<=9 && this.controller.orderable(flight.soldier,flight.victim)) {
            this.hurt(flight,flight.victim);
        }
        // Current ownership, siege, war, spawn and excluded block policy are checked again at impact and per block.
        // A west/north face hit lies exactly on the neighbouring chunk's coordinate: anchor on the actual hit block.
        final Location center=direct==null?point:direct.getLocation().add(0.5,0.5,0.5);
        if (!this.mayBombard(flight.soldier.country(),center)) return;
        int removed=0;
        final int radius=(int)Math.ceil(flight.engine.radius());
        final List<Block> candidates=new ArrayList<>();
        if (direct!=null) candidates.add(direct);
        for (int y=-radius;y<=radius;y++) for (int x=-radius;x<=radius;x++) for (int z=-radius;z<=radius;z++) {
            if (x*x+y*y+z*z>flight.engine.radius()*flight.engine.radius()) continue;
            final int bx=center.getBlockX()+x,bz=center.getBlockZ()+z,by=center.getBlockY()+y;
            if (by<world.getMinHeight() || by>=world.getMaxHeight()) continue;
            if (!SiegeRules.sameImpactChunk(center.getBlockX(),center.getBlockZ(),bx,bz)
                || !world.isChunkLoaded(bx>>4,bz>>4)) continue;
            candidates.add(world.getBlockAt(bx,by,bz));
        }
        for (final Block block:candidates) {
            if (removed>=flight.engine.blockLimit()) break;
            if (!SiegeRules.sameImpactChunk(center.getBlockX(),center.getBlockZ(),block.getX(),block.getZ()) || block.isEmpty() || block.isLiquid()
                || block.getType().getHardness()<0 || block.getState(false) instanceof Container
                || !this.services.kraje().canSiegeBreak(flight.soldier.country(),world.getName(),block.getX(),block.getY(),block.getZ())) continue;
            block.setType(Material.AIR,false); removed++;
        }
    }

    private boolean enemy(final Soldier soldier, final LivingEntity entity) {
        final LivingEntity resolved=this.controller.resolveTarget(entity);
        final UUID country=resolved instanceof Player player
            ?this.services.kraje().getCountryOf(player.getUniqueId()).map(c->c.id()).orElse(null)
            :this.controller.soldierOf(resolved).map(Soldier::country).orElse(null);
        return SiegeRules.enemyCountry(soldier.country(),country,country!=null && this.services.kraje().isAtWar(soldier.country(),country))
            && this.controller.orderable(soldier,resolved);
    }

    private void hurt(final Flight flight, final LivingEntity enemy) {
        enemy.damage(flight.damage,DamageSource.builder(DamageType.MOB_PROJECTILE)
            .withCausingEntity(flight.soldier.brain).withDirectEntity(flight.display).build());
    }

    private void sync(final Soldier soldier) {
        if (this.visuals.sync(soldier,this.controller.tick())) {
            final Model blocks=this.models.remove(soldier.id());
            if (blocks!=null) blocks.parts().forEach(BlockDisplay::remove);
            return;
        }
        final Location base=soldier.brain.getLocation();
        // Models are cosmetic: never spend thirteen entities on an engine nobody can see.
        if (base.getWorld().getPlayers().stream().noneMatch(p->p.getLocation().distanceSquared(base)<=64*64)) {
            this.removeModel(soldier.id());return;
        }
        Model model=this.models.get(soldier.id());
        if (model==null && this.models.size()>=96) return;
        final boolean cannon=SiegeRules.engine(soldier.record().type).cannon();
        if (SiegeRules.ram(soldier.record().type)) {this.ramBlocks(soldier,base,model);return;}
        final Material[] materials={Material.SPRUCE_PLANKS,Material.STRIPPED_SPRUCE_LOG,
            Material.DARK_OAK_LOG,Material.DARK_OAK_LOG,Material.IRON_BLOCK,Material.IRON_BLOCK,
            Material.STRIPPED_SPRUCE_LOG,Material.STRIPPED_SPRUCE_LOG,Material.SPRUCE_PLANKS,
            cannon?Material.POLISHED_BLACKSTONE:Material.STRIPPED_SPRUCE_LOG,
            cannon?Material.POLISHED_BLACKSTONE:Material.COBBLESTONE,
            cannon?Material.BLACK_CONCRETE:Material.CAULDRON,
            cannon?Material.IRON_BLOCK:Material.SPRUCE_FENCE};
        model=this.blockModel(soldier,model,materials);
        base.setPitch(0);
        final float recoil=(float)Math.max(0,(soldier.nextAttackTick-this.controller.tick()-(cannon?88:145))/15.0);
        final float[][] pieces=cannon?new float[][]{
            {-0.7f,0.15f,0.1f,1.4f,0.3f,1.9f}, {-1,0.45f,0.7f,2,0.16f,0.16f},
            {-1,-0.05f,0.3f,0.22f,1.1f,1.1f}, {0.78f,-0.05f,0.3f,0.22f,1.1f,1.1f},
            {-1.04f,0.33f,0.7f,0.08f,0.3f,0.3f}, {0.96f,0.33f,0.7f,0.08f,0.3f,0.3f},
            {-0.6f,0.45f,0.5f,0.2f,0.6f,0.5f}, {0.4f,0.45f,0.5f,0.2f,0.6f,0.5f},
            {-0.65f,0.65f,0.7f,1.3f,0.18f,0.18f}, {-0.32f,0.82f,-0.1f,0.64f,0.64f,2.2f},
            {-0.4f,0.73f,-0.3f,0.8f,0.8f,0.35f}, {-0.27f,0.87f,2.1f,0.54f,0.54f,0.025f},
            {-0.36f,0.78f,1.3f,0.72f,0.72f,0.1f}
        }:new float[][]{
            {-0.75f,0.15f,0.1f,1.5f,0.3f,2.1f}, {-1,0.4f,0.7f,2,0.15f,0.15f},
            {-1,-0.05f,0.25f,0.22f,1,1}, {0.78f,-0.05f,0.25f,0.22f,1,1},
            {-1.04f,0.25f,0.6f,0.08f,0.3f,0.3f}, {0.96f,0.25f,0.6f,0.08f,0.3f,0.3f},
            {-0.6f,0.4f,0.65f,0.25f,1.3f,0.3f}, {0.35f,0.4f,0.65f,0.25f,1.3f,0.3f},
            {-0.65f,1.4f,0.65f,1.3f,0.2f,0.2f}, {-0.1f,1.5f,0.75f,0.2f,0.2f,2.4f},
            {-0.35f,0.7f,0.1f,0.7f,0.7f,0.6f}, {-0.35f,1.5f,2.85f,0.7f,0.5f,0.7f},
            {-0.04f,0.5f,0.15f,0.08f,1.05f,0.08f}
        };
        for (int i=0;i<pieces.length;i++) {
            final BlockDisplay part=model.parts().get(i); final float[] p=pieces[i];
            part.teleport(base);
            final Matrix4f transform=new Matrix4f();
            if (!cannon && (i==9 || i==11)) transform.translate(0,1.5f,0.75f)
                .rotateX(-0.15f-recoil*0.9f).translate(0,-1.5f,-0.75f);
            transform.translate(p[0],p[1],p[2]+(cannon && i>=9?recoil*0.3f:0));
            part.setTransformationMatrix(transform.scale(p[3],p[4],p[5]));
        }
    }

    private Model blockModel(final Soldier soldier, Model model, final Material[] materials) {
        if (model==null || !model.type().equals(soldier.record().type) || model.parts().stream().anyMatch(e->!e.isValid())) {
            this.removeModel(soldier.id());
            final List<BlockDisplay> parts=new ArrayList<>();
            for (final Material material:materials) {
                parts.add(soldier.brain.getWorld().spawn(soldier.brain.getLocation(),BlockDisplay.class,e->{
                    final var data=material.createBlockData();
                    if (material==Material.DARK_OAK_LOG && data instanceof org.bukkit.block.data.Orientable orientable)
                        orientable.setAxis(org.bukkit.Axis.X);
                    if (material==Material.STRIPPED_DARK_OAK_LOG && data instanceof org.bukkit.block.data.Orientable orientable)
                        orientable.setAxis(org.bukkit.Axis.Z);
                    e.setBlock(data); e.setPersistent(false); e.setInvulnerable(true); e.setTeleportDuration(2);
                    this.keys.tag(e,soldier.id(),soldier.country(),Keys.PART_SIEGE_MODEL);
                }));
            }
            model=new Model(soldier.record().type,parts); this.models.put(soldier.id(),model);
        }
        return model;
    }

    /** Vanilla fallback of the ram (no BetterModel): wheeled frame, hide roof, slung beam with an iron head. */
    private void ramBlocks(final Soldier soldier, final Location base, final Model existing) {
        final Material[] materials={Material.SPRUCE_PLANKS,Material.SPRUCE_PLANKS,
            Material.DARK_OAK_LOG,Material.DARK_OAK_LOG,Material.DARK_OAK_LOG,Material.DARK_OAK_LOG,
            Material.STRIPPED_SPRUCE_LOG,Material.STRIPPED_SPRUCE_LOG,Material.STRIPPED_SPRUCE_LOG,Material.STRIPPED_SPRUCE_LOG,
            Material.BROWN_WOOL,Material.STRIPPED_DARK_OAK_LOG,Material.IRON_BLOCK};
        final Model model=this.blockModel(soldier,existing,materials);
        base.setPitch(0);
        // swing: thrust on the impact frame, then pulled back for the reload (same timing as the "uderzenie" clip)
        final long since=this.controller.tick()-(soldier.nextAttackTick-Math.max(60,this.cooldown(soldier)));
        final float swing=since<0 || since>200?0:since<=3?0.35f:since<20?-0.5f*(since-3)/17f:-0.5f;
        final float[][] pieces={
            {-1.1f,0.55f,0.25f,0.25f,0.3f,5.2f}, {0.85f,0.55f,0.25f,0.25f,0.3f,5.2f},
            {-1.35f,0,0.8f,0.22f,1,1}, {1.13f,0,0.8f,0.22f,1,1}, {-1.35f,0,4.2f,0.22f,1,1}, {1.13f,0,4.2f,0.22f,1,1},
            {-1.1f,0.85f,0.6f,0.25f,2.3f,0.25f}, {0.85f,0.85f,0.6f,0.25f,2.3f,0.25f},
            {-1.1f,0.85f,4.8f,0.25f,2.3f,0.25f}, {0.85f,0.85f,4.8f,0.25f,2.3f,0.25f},
            {-1.4f,3.15f,0.3f,2.8f,0.25f,5.6f}, {-0.3f,1.6f,0.6f,0.6f,0.6f,5.9f}, {-0.4f,1.5f,6.1f,0.8f,0.8f,0.4f}
        };
        for (int i=0;i<pieces.length;i++) {
            final BlockDisplay part=model.parts().get(i); final float[] p=pieces[i];
            part.teleport(base);
            part.setTransformationMatrix(new Matrix4f().translate(p[0],p[1],p[2]+(i>=11?swing:0)).scale(p[3],p[4],p[5]));
        }
    }

    private int cooldown(final Soldier soldier) {
        final UnitType unit=this.controller.unitOf(soldier);
        return unit==null?60:unit.cooldownTicks();
    }

    // ------------------------------------------------------------------ ram

    public enum RamOrderResult { OK, NOT_RAM, FORBIDDEN, TOO_HIGH }

    /**
     * Sends a ram to strike {@code block} (and the blocks around it in the same plane). Only a block of an actively
     * besieged enemy chunk is accepted; the head reaches from the ground up to two blocks above it.
     */
    public RamOrderResult orderRam(final Soldier soldier, final Block block) {
        if (!this.isRam(soldier) || !soldier.attached()) return RamOrderResult.NOT_RAM;
        if (block.getWorld()!=soldier.brain.getWorld()) return RamOrderResult.FORBIDDEN;
        final Location centre=block.getLocation().add(0.5,0.5,0.5);
        if (!this.mayBombard(soldier.country(),centre)) return RamOrderResult.FORBIDDEN;
        final Location here=soldier.brain.getLocation();
        final int[] f=SiegeRules.ramFacing(centre.getX()-here.getX(),centre.getZ()-here.getZ());
        final double reach=this.controller.cfg().ram.headReach();
        final int sx=(int)Math.floor(centre.getX()-f[0]*reach),sz=(int)Math.floor(centre.getZ()-f[1]*reach);
        if (block.getWorld().isChunkLoaded(sx>>4,sz>>4)) {
            final int ground=block.getWorld().getHighestBlockYAt(sx,sz)+1;
            if (block.getY()-ground<-1 || block.getY()-ground>3) return RamOrderResult.TOO_HIGH;
        }
        soldier.clearTarget();
        this.ramOrders.put(soldier.id(),new RamOrder(block.getLocation(),this.controller.tick()+this.controller.cfg().ram.orderTicks(),
            soldier.rec.mode,soldier.rec.commander));
        return RamOrderResult.OK;
    }

    public boolean hasRamOrder(final UUID soldier) { return this.ramOrders.containsKey(soldier); }

    public void cancelRam(final UUID soldier) { this.ramOrders.remove(soldier); }

    /** Diagnostics/tests: strikes landed and blocks broken since start; current order target. */
    public int ramStrikes() { return this.ramStrikes; }
    public int ramBroken() { return this.ramBroken; }
    public long lastRamImpact() { return this.lastRamImpact; }
    public Location ramTarget(final UUID soldier) {
        final RamOrder o=this.ramOrders.get(soldier);
        return o==null?null:o.target.clone();
    }

    /** Called instead of the normal behaviour while a ram order is active; false = no order (behave normally). */
    boolean driveRam(final Soldier s, final UnitType unit) {
        final RamOrder o=this.ramOrders.get(s.id());
        if (o==null) return false;
        final long now=this.controller.tick();
        if (!s.attached() || now>o.until || s.rec.mode!=o.mode || !java.util.Objects.equals(s.rec.commander,o.commander)
            || o.target.getWorld()!=s.brain.getWorld()) {
            this.ramOrders.remove(s.id()); return false;
        }
        for (final Strike strike:this.strikes) if (strike.soldier()==s) return true; // the beam is swinging
        final Location centre=o.target.clone().add(0.5,0.5,0.5);
        if (now%20==0 && !this.mayBombard(s.country(),centre)) {this.ramOrders.remove(s.id());return false;}
        final Location here=s.brain.getLocation();
        final double reach=this.controller.cfg().ram.headReach();
        final int[] f=SiegeRules.ramFacing(centre.getX()-here.getX(),centre.getZ()-here.getZ());
        final double feet=Math.floor(here.getY()+0.01);
        final var place=SiegeRules.ramPlace(centre.getX()-here.getX(),centre.getZ()-here.getZ(),o.target.getBlockY()-feet,f,reach);
        final Location spot=new Location(here.getWorld(),centre.getX()-f[0]*reach,o.target.getY(),centre.getZ()-f[1]*reach);
        if (place==SiegeRules.RamPlace.IN_POSITION) {
            s.brain.getPathfinder().stopPathfinding();
            final float yaw=f[0]!=0?(f[0]>0?-90f:90f):(f[1]>0?0f:180f);
            if (Math.abs(here.getYaw()-yaw)>0.5f) s.brain.setRotation(yaw,0);
            if (now<s.nextAttackTick) return true;
            final List<Block> face=this.face(s,o.target,f,(int)feet);
            if (face.isEmpty()) {
                // breached this plane: go one block deeper while the wall continues (thick walls), then done
                final Block next=o.target.getBlock().getRelative(f[0],0,f[1]);
                if (o.depth<2 && !this.face(s,next.getLocation(),f,(int)feet).isEmpty()) {
                    o.target=next.getLocation(); o.depth++; return true;
                }
                this.ramOrders.remove(s.id()); return false;
            }
            this.strike(s,unit,o,f);
            return true;
        }
        final double dx=spot.getX()-here.getX(),dz=spot.getZ()-here.getZ();
        if (place==SiegeRules.RamPlace.UNREACHABLE && dx*dx+dz*dz<2.25) {this.ramOrders.remove(s.id());return false;}
        this.controller.moveTo(s,spot,1.0,20);
        return true;
    }

    /** Blocks of the 3 x 3 face in the plane of {@code target} that one strike may hit (protection checked). */
    private List<Block> face(final Soldier s, final Location target, final int[] f, final int feet) {
        final List<Block> out=new ArrayList<>();
        final World world=target.getWorld();
        for (final int[] o:SiegeRules.ramFace()) {
            final int x=target.getBlockX()+o[0]*-f[1],z=target.getBlockZ()+o[0]*f[0],y=feet+o[1];
            if (y<world.getMinHeight() || y>=world.getMaxHeight()) continue;
            if (!SiegeRules.sameImpactChunk(target.getBlockX(),target.getBlockZ(),x,z) || !world.isChunkLoaded(x>>4,z>>4)) continue;
            final Block block=world.getBlockAt(x,y,z);
            if (block.isEmpty() || block.isLiquid() || block.getType().getHardness()<0 || block.getState(false) instanceof Container) continue;
            if (!this.services.kraje().canSiegeBreak(s.country(),world.getName(),x,y,z)) continue;
            out.add(block);
        }
        return out;
    }

    private void strike(final Soldier s, final UnitType unit, final RamOrder o, final int[] f) {
        final long now=this.controller.tick();
        final var engine=SiegeRules.engine(s.rec.type);
        final long reload=Math.max(engine.reloadTicks(),unit==null?0:unit.cooldownTicks());
        s.nextAttackTick=now+reload;
        // The strike clip starts now; the head reaches the gate on its impact frame (ModelCatalog release).
        this.visuals.shot(s,now,reload);
        final var model=ModelCatalog.engine(ModelCatalog.TARAN);
        final int delay=SiegeRules.ramImpactDelay(model==null?0.12:model.release());
        final Location here=s.brain.getLocation();
        this.strikes.add(new Strike(s,o,f,now+delay,here.getX(),here.getZ()));
        here.getWorld().playSound(here,Sound.BLOCK_WOOD_HIT,1f,0.6f);
    }

    private void tickStrikes() {
        if (this.strikes.isEmpty()) return;
        final long now=this.controller.tick();
        final Iterator<Strike> it=this.strikes.iterator();
        final List<Strike> due=new ArrayList<>();
        while (it.hasNext()) {
            final Strike strike=it.next();
            if (strike.at()>now) continue;
            it.remove(); due.add(strike);
        }
        for (final Strike strike:due) {
            final Soldier s=strike.soldier();
            if (!s.attached() || this.ramOrders.get(s.id())!=strike.order()) continue;
            final Location here=s.brain.getLocation();
            if (Math.hypot(here.getX()-strike.x(),here.getZ()-strike.z())>0.6) continue; // pushed away mid-swing: miss
            this.ramImpact(s,strike.order(),strike.facing(),(int)Math.floor(here.getY()+0.01));
        }
    }

    private void ramImpact(final Soldier s, final RamOrder o, final int[] f, final int feet) {
        final Location centre=o.target.clone().add(0.5,0.5,0.5);
        // ownership, siege, war and protection are checked again on the impact frame, then per block
        if (!this.mayBombard(s.country(),centre)) {this.ramOrders.remove(s.id());return;}
        final var settings=this.controller.cfg().ram;
        final int limit=Math.min(settings.blocksPerStrike(),SiegeRules.engine(s.rec.type).blockLimit());
        final World world=centre.getWorld();
        final long now=this.controller.tick();
        int broken=0;
        boolean fortified=false;
        for (final Block block:this.face(s,o.target,f,feet)) {
            if (block.isEmpty()) continue; // the other half of a door broken a moment ago
            final Material type=block.getType();
            final var cls=SiegeRules.ramClass(type.name(),Tag.MINEABLE_AXE.isTagged(type),type.getHardness(),settings.fortificationBlocks());
            if (cls==null) continue;
            if (cls!=SiegeRules.RamClass.WOOD) fortified=true;
            final String key=world.getName()+":"+block.getX()+":"+block.getY()+":"+block.getZ();
            final Wear old=this.wear.get(key);
            final double amount=SiegeRules.ramDamage(old==null || old.type()!=type?0:old.amount(),cls,settings);
            world.spawnParticle(Particle.BLOCK,block.getLocation().add(0.5,0.5,0.5),8,0.3,0.3,0.3,0,block.getBlockData());
            if (SiegeRules.ramBreaks(amount) && broken<limit) {
                this.breakBlock(s,block);
                this.wear.remove(key);
                broken++;
            } else {
                this.wear.put(key,new Wear(type,Math.min(amount,0.999),now));
            }
        }
        this.ramStrikes++; this.ramBroken+=broken; this.lastRamImpact=now;
        world.playSound(centre,fortified?Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR:Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,1.3f,0.7f);
        if (broken>0) world.playSound(centre,Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,1.2f,0.8f);
        if (this.wear.size()>4096 || now%1200==0) this.wear.values().removeIf(w->now-w.tick()>6000 || now<w.tick());
    }

    /** No drop and no block physics (no falling chains); a door or other two-block piece goes as a whole. */
    private void breakBlock(final Soldier s, final Block block) {
        if (block.getBlockData() instanceof Bisected half) {
            final Block other=block.getRelative(half.getHalf()==Bisected.Half.BOTTOM?org.bukkit.block.BlockFace.UP:org.bukkit.block.BlockFace.DOWN);
            if (other.getType()==block.getType() && SiegeRules.sameImpactChunk(block.getX(),block.getZ(),other.getX(),other.getZ())
                && this.services.kraje().canSiegeBreak(s.country(),other.getWorld().getName(),other.getX(),other.getY(),other.getZ()))
                other.setType(Material.AIR,false);
        }
        block.setType(Material.AIR,false);
    }

    /**
     * AI: the best block for a ram in a besieged chunk — gates and doors first, then the nearest wall block whose
     * outer side (towards the ram) is open, from the ram's ground up to two blocks; null when nothing fits.
     */
    public Block pickRamTarget(final Soldier s, final World world, final int cx, final int cz, final Location avoid) {
        if (!s.attached() || s.brain.getWorld()!=world || !world.isChunkLoaded(cx,cz)) return null;
        final Location here=s.brain.getLocation();
        final int feet=(int)Math.floor(here.getY()+0.01);
        final List<Block> candidates=new ArrayList<>();
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) for (int up=0;up<=2;up++) {
            final int y=feet+up;
            if (y<world.getMinHeight() || y>=world.getMaxHeight()) continue;
            final Block block=world.getBlockAt((cx<<4)+x,y,(cz<<4)+z);
            if (block.isEmpty() || block.isLiquid() || block.getType().getHardness()<0 || block.getState(false) instanceof Container) continue;
            if (avoid!=null && avoid.getBlockX()==block.getX() && avoid.getBlockY()==block.getY() && avoid.getBlockZ()==block.getZ()) continue;
            final int[] f=SiegeRules.ramFacing(block.getX()+0.5-here.getX(),block.getZ()+0.5-here.getZ());
            // the outer side of an edge block lies in the neighbouring chunk: never load it just to look
            if (!world.isChunkLoaded((block.getX()-f[0])>>4,(block.getZ()-f[1])>>4)) continue;
            if (!block.getRelative(-f[0],0,-f[1]).isPassable()) continue; // inner block: not facing the ram
            candidates.add(block);
        }
        candidates.sort(java.util.Comparator.comparingDouble(b->{
            final double dx=b.getX()+0.5-here.getX(),dz=b.getZ()+0.5-here.getZ();
            final String n=b.getType().name();
            final boolean gate=n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE") || n.endsWith("_TRAPDOOR") || n.equals("IRON_BARS");
            return Math.sqrt(dx*dx+dz*dz)-(gate?12:0)+(b.getY()==feet+1?0:0.5);
        }));
        int checked=0;
        for (final Block block:candidates) {
            if (++checked>12) break;
            if (this.services.kraje().canSiegeBreak(s.country(),world.getName(),block.getX(),block.getY(),block.getZ())) return block;
        }
        return null;
    }

    void removeModel(final UUID id) { final Model model=this.models.remove(id); if(model!=null) model.parts().forEach(BlockDisplay::remove);
        this.visuals.remove(id); }
    void stop() { this.models.values().forEach(m->m.parts().forEach(BlockDisplay::remove)); this.models.clear();
        this.ramOrders.clear(); this.strikes.clear(); this.wear.clear();
        this.flights.forEach(f->f.display.remove()); this.flights.clear(); this.visuals.stop(); }
}
