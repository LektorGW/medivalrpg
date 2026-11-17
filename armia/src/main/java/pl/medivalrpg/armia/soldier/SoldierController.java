// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.TargetSelector;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Runs the soldiers. Per server tick it only moves the visible bodies onto their brains and spawns queued
 * soldiers; target scanning ("thinking") is round-robin within a millisecond budget, and walking/fighting happens in
 * each brain's {@link SoldierGoal} (so unloaded or inactive soldiers cost nothing).
 */
public final class SoldierController {

    private static final double MAX_TARGET_DISTANCE_SQ = 48 * 48;
    /** Farther than this from its post a soldier is moved there directly instead of pathfinding. */
    private static final double FAR_SQ = 64 * 64;

    private final Plugin plugin;
    private final SoldierRegistry registry;
    private final SoldierSpawner spawner;
    private final Keys keys;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Logger logger;
    private final SiegeEngineService siegeEngines;

    private final Deque<UUID> spawnQueue = new ArrayDeque<>();
    private final java.util.Map<UUID, Pos> relocations = new java.util.LinkedHashMap<>();
    private final Set<UUID> queued = new HashSet<>();
    private final Set<String> warnedTypes = new HashSet<>();
    /** Damage multiplier of a country (era bonus + Postep sila-armii); 1 without Postep. */
    private java.util.function.ToDoubleFunction<UUID> damageMultiplier = c -> 1.0;

    public void setDamageMultiplier(final java.util.function.ToDoubleFunction<UUID> m) {
        this.damageMultiplier = m;
    }
    private List<Soldier> cycle = new ArrayList<>();
    private int cursor;
    private long tick;
    private BukkitTask task;
    /** Average think time per tick (ms), for /nk info. */
    private double avgThinkMs;

    public SoldierController(final Plugin plugin, final SoldierRegistry registry, final SoldierSpawner spawner, final Keys keys,
                             final Services services, final Supplier<ArmiaConfig> config) {
        this.plugin = plugin;
        this.registry = registry;
        this.spawner = spawner;
        this.keys = keys;
        this.services = services;
        this.config = config;
        this.logger = plugin.getLogger();
        this.siegeEngines = new SiegeEngineService(this, services, keys);
        spawner.controller(this);
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickAll, 1L, 1L);
    }

    public void stop() {
        this.siegeEngines.stop();
        if (this.task != null) {
            this.task.cancel();
        }
        for (final Soldier s : this.registry.all()) {
            this.detach(s, true);
        }
    }

    public long tick() {
        return this.tick;
    }

    public double avgThinkMs() {
        return this.avgThinkMs;
    }

    boolean owns(final Soldier s) {
        return this.registry.get(s.id()) == s;
    }

    boolean ownsId(final UUID id) { return this.registry.get(id) != null; }

    public SiegeEngineService siegeEngines() { return this.siegeEngines; }

    ArmiaConfig cfg() { return this.config.get(); }

    UnitType unit(final Soldier s) {
        final ArmiaConfig cfg = this.config.get();
        final UnitType u = cfg.soldierUnit(s.rec.type);
        if (u != null) {
            return u;
        }
        if (this.warnedTypes.add(s.rec.type)) {
            this.logger.warning("Typ jednostki '" + s.rec.type + "' nie istnieje już w configu — żołnierze tego typu używają pierwszego typu z listy");
        }
        return cfg.units.isEmpty() ? null : cfg.units.values().iterator().next();
    }

    public UnitType unitOf(final Soldier s) {
        return this.unit(s);
    }

    // ------------------------------------------------------------------ main loop

    private void tickAll() {
        this.tick++;
        this.processRelocations();
        this.processSpawnQueue();
        for (final Soldier s : this.registry.all()) {
            if (s.brain != null) {
                if (s.brain.isValid()) {
                    this.syncBody(s);
                } else if (!s.brain.isDead()) {
                    // unloaded without an event (should not happen) → treat as detached
                    this.detach(s, false);
                }
            }
        }
        this.thinkWithBudget();
        this.siegeEngines.tick(this.registry.all());
        if (this.tick % (20L * this.config.get().repairSeconds) == 0) {
            this.repair();
        }
        if (this.tick % 1200 == 0) {
            this.regen();
        }
        if (this.tick % (20L * this.config.get().barracks.healIntervalSeconds()) == 0) {
            this.garrisonHeal();
        }
    }

    private void thinkWithBudget() {
        final long start = System.nanoTime();
        final long budget = (long) (this.config.get().thinkBudgetMs * 1_000_000L);
        int visited = 0;
        if (this.cursor >= this.cycle.size()) {
            this.cycle = new ArrayList<>(this.registry.all());
            this.cursor = 0;
        }
        while (this.cursor < this.cycle.size() && visited < this.cycle.size()) {
            final Soldier s = this.cycle.get(this.cursor++);
            visited++;
            if (!this.owns(s) || !s.attached() || this.tick < s.nextThinkTick) {
                continue;
            }
            s.nextThinkTick = this.tick + this.config.get().thinkIntervalTicks + ThreadLocalRandom.current().nextInt(4);
            try {
                this.think(s);
            } catch (final RuntimeException e) {
                this.logger.log(java.util.logging.Level.WARNING, "Błąd AI żołnierza " + s.id(), e);
            }
            if (System.nanoTime() - start > budget) {
                break;
            }
        }
        final double ms = (System.nanoTime() - start) / 1_000_000.0;
        this.avgThinkMs = this.avgThinkMs * 0.98 + ms * 0.02;
    }

    // ------------------------------------------------------------------ spawning / attaching

    /** Moves a soldier far away outside its goal tick (entities are not removed while they tick). */
    void requestRelocate(final Soldier s, final Pos target) {
        this.relocations.putIfAbsent(s.id(), target);
    }

    private void processRelocations() {
        if (this.relocations.isEmpty()) {
            return;
        }
        final java.util.Map<UUID, Pos> todo = new java.util.LinkedHashMap<>(this.relocations);
        this.relocations.clear();
        for (final java.util.Map.Entry<UUID, Pos> e : todo.entrySet()) {
            final Soldier s = this.registry.get(e.getKey());
            if (s != null) {
                this.relocate(s, e.getValue());
            }
        }
    }

    /**
     * Puts the soldier at {@code target}: teleport when that chunk is loaded, otherwise the entities are removed and
     * the soldier re-appears there when the chunk loads (it "marched" while nobody watched).
     */
    public void relocate(final Soldier s, final Pos target) {
        if (target == null || s.rec.suspended()) {
            return;
        }
        if (target.isChunkLoaded()) {
            final Location l = target.toLocation();
            if (l != null && s.attached()) {
                this.teleportNear(s, l);
                return;
            }
        }
        if (s.brain != null) {
            this.spawner.leaveTeam(s.brain);
            s.brain.eject();
            s.brain.remove();
        }
        if (s.body != null) {
            this.spawner.leaveTeam(s.body);
            s.body.remove();
        }
        s.brain = null;
        s.body = null;
        s.clearTarget();
        this.registry.setBrain(s, null);
        s.rec.last = target;
        if (target.isChunkLoaded()) {
            this.queueSpawn(s);
        }
    }

    /** Queues a registry-only soldier for spawning at its spawn position (when that chunk is loaded). */
    public void queueSpawn(final Soldier s) {
        if (s.rec.suspended()) return;
        if (this.queued.add(s.id())) {
            this.spawnQueue.add(s.id());
        }
    }

    private void processSpawnQueue() {
        int budget = this.config.get().maxSpawnsPerTick;
        int guard = this.spawnQueue.size();
        while (budget > 0 && guard-- > 0 && !this.spawnQueue.isEmpty()) {
            final UUID id = this.spawnQueue.poll();
            this.queued.remove(id);
            final Soldier s = this.registry.get(id);
            if (s == null || s.attached()) {
                continue;
            }
            final Pos pos = s.rec.spawnPos();
            if (pos == null || !pos.isChunkLoaded()) {
                continue;
            }
            final Location loc = pos.toLocation();
            if (loc == null) {
                continue;
            }
            this.materialize(s, loc);
            budget--;
        }
    }

    /** Spawns the soldier's entities at {@code loc} (chunk must be loaded). */
    public void materialize(final Soldier s, final Location loc) {
        if (s.rec.suspended()) return;
        final UnitType u = this.unit(s);
        if (u == null || loc.getWorld() == null) {
            return;
        }
        if (!loc.isChunkLoaded()) {
            // Spread spawns (±1 block) can cross into an unloaded neighbour chunk: never spawn into it, appear on load.
            s.rec.last = Pos.of(loc);
            this.registry.markDirty();
            this.queueSpawn(s);
            return;
        }
        final KrajInfo country = this.services.kraje().getCountry(s.country()).orElse(null);
        if (s.rec.post != null && s.rec.post.isSurface() && s.rec.post.isChunkLoaded()) {
            final Location resolved = s.rec.post.toLocation();
            if (resolved != null) {
                s.rec.post = s.rec.post.resolved(resolved);
            }
        }
        final Mob brain = this.spawner.spawnBrain(s, u, loc, country);
        s.brain = brain;
        s.missingChecks = 0;
        this.registry.setBrain(s, brain.getUniqueId());
        s.rec.last = Pos.of(loc);
        s.nextThinkTick = this.tick + 2;
        this.syncBody(s);
    }

    /** A brain entity was loaded (chunk load or startup scan). */
    public void onBrainLoaded(final Mob mob) {
        final UUID id = this.keys.soldierId(mob);
        final Soldier s = this.registry.get(id);
        if (s == null) {
            this.logger.fine("Usuwam osieroconego żołnierza " + mob.getUniqueId());
            mob.remove();
            return;
        }
        if(s.rec.retiredRoyalBrain(mob.getUniqueId())) {mob.remove();return;}
        if (s.rec.suspended()) {mob.remove();this.registry.setBrain(s,null);return;}
        if (s.rec.brain != null && !s.rec.brain.equals(mob.getUniqueId())) {
            // A replacement was spawned while this one was missing → duplicate.
            if (s.attached() && s.brain != mob) {
                mob.remove();
                return;
            }
        }
        if (s.attached() && s.brain == mob) {
            return;
        }
        final UnitType u = this.unit(s);
        if (u == null) {
            return;
        }
        s.brain = mob;
        s.missingChecks = 0;
        this.registry.setBrain(s, mob.getUniqueId());
        this.spawner.configureBrain(s, u, mob, this.services.kraje().getCountry(s.country()).orElse(null), false);
        s.nextThinkTick = this.tick + 2 + ThreadLocalRandom.current().nextInt(10);
    }

    /** Queues registry-only soldiers whose spawn position lies in this freshly loaded chunk. */
    public void onChunkLoaded(final Chunk chunk) {
        final String world = chunk.getWorld().getName();
        for (final Soldier s : this.registry.all()) {
            if (s.rec.brain != null) {
                continue;
            }
            final Pos p = s.rec.spawnPos();
            if (p != null && p.world().equals(world) && p.chunkX() == chunk.getX() && p.chunkZ() == chunk.getZ()) {
                this.queueSpawn(s);
            }
        }
    }

    /** Brain unloaded/removed: remember where and how healthy it was, drop the body. */
    public void detach(final Soldier s, final boolean removeBody) {
        this.siegeEngines.removeModel(s.id());
        if (s.brain != null) {
            if (s.brain.isValid() || !s.brain.isDead()) {
                s.rec.last = Pos.of(s.brain.getLocation());
                if (s.brain.getHealth() > 0) {
                    s.rec.health = s.brain.getHealth();
                }
            }
            this.registry.markDirty();
        }
        s.brain = null;
        s.clearTarget();
        if (s.body != null) {
            this.spawner.leaveTeam(s.body);
            if (removeBody || s.body.isValid()) {
                s.body.remove();
            }
            s.body = null;
        }
    }

    /** Fleet has already checked proximity and ownership; keep the one soldier record and remove live bodies. */
    public void embark(final Soldier s,final UUID ship) {
        if (!s.attached() || s.rec.suspended()) return;
        final Mob brain=s.brain;
        this.detach(s,true);
        this.spawner.leaveTeam(brain);brain.eject();brain.remove();
        this.registry.setBrain(s,null);s.rec.transportShip=ship;
        this.relocations.remove(s.id());this.registry.markDirty();
    }

    public void disembark(final Soldier s,final Location target) {
        if (s.rec.transportShip==null) return;
        s.rec.transportShip=null;s.rec.mode=SoldierMode.GUARD;s.rec.commander=null;
        s.rec.post=Pos.of(target);s.rec.last=s.rec.post;s.rec.hold=null;s.clearTarget();
        this.relocate(s,s.rec.post);this.registry.markDirty();
    }

    /**
     * Background campaign (ai.conquest): the soldier leaves with an abstract offensive. A live soldier (no player near, as
     * checked by the caller) keeps its record, HP and last position; only its entities are removed. Idempotent.
     */
    public void march(final Soldier s, final String campaign) {
        if (s.rec.transportShip != null || s.rec.royalEvacuated || campaign == null) return;
        s.rec.campaign = campaign;
        final Mob brain = s.brain;
        if (brain != null) {
            this.detach(s, true);
            this.spawner.leaveTeam(brain);brain.eject();brain.remove();
            this.registry.setBrain(s, null);
        }
        this.relocations.remove(s.id());this.registry.markDirty();
    }

    /**
     * Long-range landing: a soldier at sea (campaign) goes aboard the expedition ship that appeared off the enemy coast.
     * The one registry record changes its suspension cause only (campaign → cargo); nothing is spawned or copied.
     */
    public void stow(final Soldier s, final UUID ship) {
        if (ship == null || s.rec.transportShip != null || s.rec.royalEvacuated) return;
        s.rec.campaign = null;
        if (s.attached()) {
            this.embark(s, ship);
            this.registry.markDirty();
            return;
        }
        s.rec.transportShip = ship;
        this.relocations.remove(s.id());
        this.registry.markDirty();
    }

    /** Long-range landing, way home: cargo of the expedition ship (or a soldier ashore) sails back in the background. */
    public void unstow(final Soldier s, final String campaign) {
        if (campaign == null || s.rec.royalEvacuated) return;
        if (s.rec.transportShip != null) {
            s.rec.transportShip = null;
            s.rec.campaign = campaign;
            this.relocations.remove(s.id());
            this.registry.markDirty();
            return;
        }
        this.march(s, campaign);
    }

    /** Back from a background campaign: the soldier reappears at its last position when that chunk is loaded. */
    public void returnFromMarch(final Soldier s) {
        if (s.rec.campaign == null) return;
        s.rec.campaign = null;s.rec.siege = false;s.missingChecks = 0;
        this.registry.markDirty();
    }

    /** Idempotent construction shelter: preserve the record and current HP while removing only live bodies. */
    public void holdRoyal(final Soldier s) {
        if(s.rec.transportShip!=null) return;
        s.rec.royalEvacuated=true;
        s.rec.retireRoyalBrain(s.rec.brain);
        final Mob brain=s.brain;
        if(brain!=null) s.rec.retireRoyalBrain(brain.getUniqueId());
        final Entity detached=brain==null && s.rec.brain!=null?Bukkit.getEntity(s.rec.brain):null;
        this.detach(s,true);
        if(brain!=null) {this.spawner.leaveTeam(brain);brain.eject();brain.remove();}
        if(detached!=null) {this.spawner.leaveTeam(detached);detached.remove();}
        this.registry.setBrain(s,null);this.relocations.remove(s.id());this.registry.markDirty();
    }

    /**
     * Rearm: the soldier keeps its id, post, mode and commander but becomes {@code type}; a loaded one is respawned in
     * place with the new equipment, an unloaded one spawns as the new type when its chunk loads.
     */
    public void retype(final Soldier s, final UnitType type) {
        if (s.rec.suspended()) {s.rec.type=type.id();this.registry.retyped();return;}
        final Location at = s.brain != null && s.brain.isValid() ? s.brain.getLocation() : null;
        if (s.brain != null) {
            this.spawner.leaveTeam(s.brain);
            s.brain.eject();
            s.brain.remove();
        } else if (s.rec.brain != null) {
            final Entity e = Bukkit.getEntity(s.rec.brain);
            if (e != null) {
                e.remove();
            }
        }
        if (s.body != null) {
            this.spawner.leaveTeam(s.body);
            s.body.remove();
        }
        s.brain = null;
        s.body = null;
        this.registry.setBrain(s, null);
        s.rec.type = type.id();
        s.rec.health = 0; // full health of the new type
        this.registry.retyped();
        s.clearTarget();
        this.registry.markDirty();
        if (at != null) {
            this.materialize(s, at);
        }
    }

    /** Removes the soldier's entities and forgets it. */
    public void destroy(final Soldier s) {
        if (s.brain != null) {
            this.spawner.leaveTeam(s.brain);
            s.brain.eject();
            s.brain.remove();
        } else if (s.rec.brain != null) {
            final Entity e = Bukkit.getEntity(s.rec.brain);
            if (e != null) {
                e.remove();
            }
        }
        if (s.body != null) {
            this.spawner.leaveTeam(s.body);
            s.body.remove();
        }
        s.brain = null;
        s.body = null;
        this.registry.remove(s);
    }

    private void syncBody(final Soldier s) {
        final UnitType u = this.unit(s);
        if (u == null) {
            return;
        }
        final boolean needsBody = this.config.get().visual == ArmiaConfig.Visual.MANNEQUIN || u.mounted();
        if (!needsBody) {
            return;
        }
        final Mob b = s.brain;
        final Location bl = b.getLocation();
        if (s.body == null || !s.body.isValid()) {
            if (this.tick >= s.nextBodyRespawnTick && bl.isChunkLoaded()) {
                s.nextBodyRespawnTick = this.tick + 40;
                if (s.body != null) {
                    s.body.remove();
                }
                s.body = this.spawner.spawnBody(s, u, bl, this.services.kraje().getCountry(s.country()).orElse(null));
                s.anim = null; // a new body: its work animation is set again
                s.stillSinceTick = this.tick;
                if (s.body != null && u.mounted()) {
                    b.addPassenger(s.body);
                }
            }
            return;
        }
        final LivingEntity body = s.body;
        if (u.mounted()) {
            if (!b.getPassengers().contains(body)) {
                if (body.getWorld() != b.getWorld() || body.getLocation().distanceSquared(bl) > 9) {
                    body.teleport(bl);
                }
                b.addPassenger(body);
            }
            body.setRotation(bl.getYaw(), bl.getPitch());
            return;
        }
        final Location cl = body.getLocation();
        if (cl.getWorld() != bl.getWorld() || cl.distanceSquared(bl) > 1.0E-4
            || Math.abs(cl.getYaw() - bl.getYaw()) > 0.5f || Math.abs(cl.getPitch() - bl.getPitch()) > 0.5f) {
            body.teleport(bl);
            if (cl.getWorld() != bl.getWorld() || cl.distanceSquared(bl) > 0.0025) {
                s.stillSinceTick = this.tick; // really walking (not just turning the head)
            }
        }
        this.guardPose(s, body);
    }

    /**
     * Interakcje work animation (optional, reflection — see interakcje/INTEGRATION-NPC.md): a foot soldier standing
     * still on his post (guard / hold, no target, no move for 1 s) shows "straz"; walking or fighting — none (vanilla
     * Mannequin limbs). Changed only on a state change; a refusal (Interakcje missing) is retried every 5 s.
     */
    private void guardPose(final Soldier s, final LivingEntity body) {
        final SoldierMode mode = s.rec.mode;
        final boolean onDuty = (mode == SoldierMode.GUARD || mode == SoldierMode.HOLD) && s.target == null
            && this.tick - s.lastCombatTick > 40 && this.tick - s.stillSinceTick >= 20;
        final String want = onDuty ? "straz" : null;
        if (java.util.Objects.equals(want, s.anim)) {
            return;
        }
        if (want == null) {
            s.anim = null;
            pl.medivalrpg.armia.visual.WorkAnim.stop(body);
            return;
        }
        if (this.tick < s.animRetryTick) {
            return;
        }
        if (pl.medivalrpg.armia.visual.WorkAnim.play(body, want, true)) {
            s.anim = want;
        } else {
            s.animRetryTick = this.tick + 100;
        }
    }

    private void repair() {
        for (final Soldier s : new ArrayList<>(this.registry.all())) {
            if (s.rec.suspended()) continue;
            if (s.attached()) {
                continue;
            }
            final Pos pos = s.rec.spawnPos();
            if (pos == null || !pos.isChunkLoaded()) {
                s.missingChecks = 0;
                continue;
            }
            if (s.rec.brain == null) {
                this.queueSpawn(s);
                continue;
            }
            final Entity e = Bukkit.getEntity(s.rec.brain);
            if (e instanceof Mob mob && e.isValid()) {
                this.onBrainLoaded(mob);
                continue;
            }
            // Chunk of the last position is loaded but the brain is nowhere: it was removed while we did not look.
            if (++s.missingChecks >= 2) {
                this.logger.fine("Żołnierz " + s.id() + " zaginął — tworzę go ponownie");
                this.registry.setBrain(s, null);
                this.queueSpawn(s);
            }
        }
    }

    private void regen() {
        final double amount = this.config.get().regenPerMinute;
        if (amount <= 0) {
            return;
        }
        for (final Soldier s : this.registry.all()) {
            if (!s.attached() || this.tick - s.lastCombatTick < 200) {
                continue;
            }
            final double max = maxHealth(s.brain);
            if (s.brain.getHealth() < max) {
                s.brain.setHealth(Math.min(max, s.brain.getHealth() + amount));
                s.rec.health = s.brain.getHealth();
            }
        }
    }

    /**
     * Garrison healing: wounded soldiers resting near a working barracks of their own country recover faster. Only
     * loaded (attached) soldiers out of combat; healthy ones are skipped before anything is looked up, and the
     * barracks list is Ludnosc's snapshot cached per country — no world access, no chunk loads.
     */
    private void garrisonHeal() {
        final ArmiaConfig cfg = this.config.get();
        final pl.medivalrpg.armia.logic.BarracksRules.Settings b = cfg.barracks;
        if (!b.healEnabled() || !this.services.population().present()) {
            return;
        }
        final double amount = b.healPerPass();
        final java.util.Map<UUID, List<pl.medivalrpg.armia.logic.BarracksRules.Barracks>> byCountry = new java.util.HashMap<>();
        for (final Soldier s : this.registry.all()) {
            if (!s.attached() || s.brain.isDead() || this.tick - s.lastCombatTick < 200
                || (s.lastAttackedTick > 0 && this.tick - s.lastAttackedTick < 200)) {
                continue;
            }
            final double max = maxHealth(s.brain);
            final double health = s.brain.getHealth();
            if (health >= max || health <= 0) {
                continue;
            }
            final List<pl.medivalrpg.armia.logic.BarracksRules.Barracks> list = byCountry.computeIfAbsent(s.country(),
                id -> this.services.barracks(id, cfg.barracksBuilding, b));
            if (list.isEmpty()) {
                continue;
            }
            final Location l = s.brain.getLocation();
            if (!pl.medivalrpg.armia.logic.BarracksRules.garrisoned(list, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), b.healRadius())) {
                continue;
            }
            s.brain.setHealth(Math.min(max, health + amount));
            s.rec.health = s.brain.getHealth();
            if (b.healParticles()) {
                l.getWorld().spawnParticle(org.bukkit.Particle.HEART, l.add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
            }
        }
    }

    static double maxHealth(final LivingEntity e) {
        final var a = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    // ------------------------------------------------------------------ thinking (target selection)

    private void think(final Soldier s) {
        final Mob b = s.brain;
        final Location loc = b.getLocation();
        s.rec.last = Pos.of(loc);
        s.rec.health = b.getHealth();
        if (s.body != null && s.body.isValid() && b.isInvisible()) {
            SoldierListener.copyArmor(s.body, b); // the hidden brain takes the hits, so it wears the body's armour value
        }
        if (s.target != null) {
            if (!s.target.isValid() || s.target.isDead() || s.target.getWorld() != b.getWorld()) {
                s.clearTarget();
            } else if (!s.ordered && !this.hostile(s, s.target)) {
                s.clearTarget();
            } else if (!s.ordered && s.target != null && this.lastAttackerEntity(s) instanceof LivingEntity attacker
                && attacker != s.target && this.hostile(s, attacker)) {
                s.target = attacker; // fight back against whoever hits us
            }
            if (s.target != null) {
                return;
            }
        }
        if (this.siegeEngines.isRam(s)) {
            return; // the ram crew stays under its roof: no hunting of living targets, only ordered strikes
        }
        final ArmiaConfig cfg = this.config.get();
        double radius = cfg.detectRadius;
        if (s.rec.mode == SoldierMode.GUARD) {
            radius = Math.max(radius, s.rec.radius + 4);
        }
        final List<TargetSelector.Candidate> candidates = new ArrayList<>();
        for (final Entity e : b.getNearbyEntities(radius, 8, radius)) {
            if (e instanceof LivingEntity le && e != b && e != s.body) {
                final TargetSelector.Candidate c = this.classify(s, le, loc);
                if (c != null) {
                    candidates.add(c);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        TargetSelector.choose(s.country(), candidates, this::stance, cfg.targetRules, radius * radius)
            .ifPresent(c -> {
                s.target = (LivingEntity) c.handle();
                s.ordered = false;
            });
    }

    private Entity lastAttackerEntity(final Soldier s) {
        if (s.lastAttacker == null || this.tick - s.lastAttackedTick > 100) {
            return null;
        }
        return Bukkit.getEntity(s.lastAttacker);
    }

    TargetSelector.Stance stance(final UUID own, final UUID other) {
        if (own.equals(other)) {
            return TargetSelector.Stance.OWN;
        }
        final Relation r = this.services.kraje().getRelation(own, other);
        return switch (r) {
            case WAR -> TargetSelector.Stance.ENEMY;
            case ALLIANCE -> TargetSelector.Stance.ALLY;
            default -> TargetSelector.Stance.NEUTRAL;
        };
    }

    /** Candidate for the selector; null for entities that are never relevant (bodies, rulers, armour stands...). */
    TargetSelector.Candidate classify(final Soldier s, final LivingEntity e, final Location from) {
        final double d2 = from.getWorld() == e.getWorld() ? from.distanceSquared(e.getLocation()) : Double.MAX_VALUE;
        final boolean attackedUs = e.getUniqueId().equals(s.lastAttacker) && this.tick - s.lastAttackedTick < 200;
        if (e instanceof Player p) {
            final boolean exempt = p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.hasPermission("armia.bypass");
            final UUID country = this.services.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null);
            return new TargetSelector.Candidate(p, TargetSelector.Kind.PLAYER, country, d2, false, attackedUs, exempt);
        }
        final String part = this.keys.part(e);
        if (part != null) {
            if (!Keys.PART_BRAIN.equals(part)) {
                return null;
            }
            final Soldier other = this.registry.byBrain(e.getUniqueId());
            if (other == null) {
                return null;
            }
            return new TargetSelector.Candidate(e, TargetSelector.Kind.SOLDIER, other.country(), d2, false, attackedUs, e.isDead());
        }
        if (e instanceof Enemy) {
            final boolean own = this.services.kraje().getCountryAt(e.getLocation()).map(k -> k.id().equals(s.country())).orElse(false);
            return new TargetSelector.Candidate(e, TargetSelector.Kind.MONSTER, null, d2, own, attackedUs, e.isDead());
        }
        return null;
    }

    boolean hostile(final Soldier s, final LivingEntity e) {
        final TargetSelector.Candidate c = this.classify(s, e, s.brain.getLocation());
        return c != null && TargetSelector.isHostile(s.country(), c, this::stance, this.config.get().targetRules);
    }

    /** May the commander order an attack on {@code e}? */
    public boolean orderable(final Soldier s, final LivingEntity e) {
        final Location from = s.brain != null ? s.brain.getLocation() : e.getLocation();
        final TargetSelector.Candidate c = this.classify(s, e, from);
        return c != null && TargetSelector.isOrderable(s.country(), c, this::stance, this.config.get().targetRules);
    }

    /** Commander's order: attack {@code e} (resolved to the soldier brain when {@code e} is a soldier body). */
    public void orderAttack(final Soldier s, final LivingEntity e) {
        s.target = e;
        s.ordered = true;
        s.nextRepathTick = 0;
    }

    /** Resolves a soldier body to its brain (what can actually be damaged). */
    public LivingEntity resolveTarget(final LivingEntity e) {
        if (Keys.PART_BODY.equals(this.keys.part(e))) {
            final Soldier other = this.registry.get(this.keys.soldierId(e));
            if (other != null && other.attached()) {
                return other.brain;
            }
        }
        return e;
    }

    void noteAttacked(final Soldier s, final Entity attacker) {
        if (attacker == null) {
            return;
        }
        s.lastAttacker = attacker.getUniqueId();
        s.lastAttackedTick = this.tick;
        s.lastCombatTick = this.tick;
        s.nextThinkTick = Math.min(s.nextThinkTick, this.tick + 2);
    }

    // ------------------------------------------------------------------ acting (called from the goal)

    void tickSoldier(final Soldier s) {
        final Mob b = s.brain;
        if (b == null || !b.isValid()) {
            return;
        }
        final UnitType u = this.unit(s);
        if (u == null) {
            return;
        }
        LivingEntity t = s.target;
        if (t != null && (!t.isValid() || t.isDead() || t.getWorld() != b.getWorld() || exemptPlayer(t))) {
            s.clearTarget();
            t = null;
        }
        if (t != null) {
            final double d2 = b.getLocation().distanceSquared(t.getLocation());
            if (d2 > MAX_TARGET_DISTANCE_SQ || (!s.ordered && this.leashBroken(s, t))) {
                s.clearTarget();
                this.visual(s, false);
            } else {
                this.fight(s, u, t, d2);
                return;
            }
        }
        this.visual(s, false);
        if (this.siegeEngines.driveRam(s, u)) {
            return;
        }
        this.behave(s, u);
    }

    private static boolean exemptPlayer(final LivingEntity t) {
        return t instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || !p.isOnline());
    }

    private boolean leashBroken(final Soldier s, final LivingEntity t) {
        final Location anchor = this.anchor(s);
        if (anchor == null || anchor.getWorld() != t.getWorld()) {
            return false;
        }
        final ArmiaConfig cfg = this.config.get();
        final double leash = (s.rec.mode == SoldierMode.GUARD ? s.rec.radius : cfg.detectRadius) + cfg.leashExtra;
        return anchor.distanceSquared(t.getLocation()) > leash * leash;
    }

    private void fight(final Soldier s, final UnitType u, final LivingEntity t, final double d2) {
        final Mob b = s.brain;
        s.lastCombatTick = this.tick;
        b.lookAt(t);
        this.visual(s, true);
        if (u.ranged()) {
            final var ammo=pl.medivalrpg.armia.logic.SiegeRules.ammunition(s.rec.type,s.rec.siegeAmmo);
            final double r = ammo==null?u.range():Math.min(u.range(),ammo.range());
            final boolean inRange = d2 <= r * r;
            if (inRange && (this.tick % 10 != 0 || b.hasLineOfSight(t))) {
                b.getPathfinder().stopPathfinding();
                if (!s.aiming) {
                    this.startAim(s);
                }
                if (this.tick >= s.nextAttackTick && b.hasLineOfSight(t)) {
                    this.shoot(s, u, t);
                    s.nextAttackTick = Math.max(s.nextAttackTick, this.tick + u.cooldownTicks());
                }
            } else {
                this.moveTo(s, t.getLocation(), this.config.get().chaseSpeed, 10);
            }
            return;
        }
        final double reach = u.range() + t.getWidth() / 2.0 + (u.mounted() ? 0.6 : 0);
        final double dy = Math.abs(b.getLocation().getY() - t.getLocation().getY());
        if (d2 <= reach * reach && dy < 2.5) {
            if (d2 < 1.2) {
                b.getPathfinder().stopPathfinding();
            } else {
                this.moveTo(s, t.getLocation(), 1.0, 10);
            }
            if (this.tick >= s.nextAttackTick) {
                this.melee(s, u, t);
                s.nextAttackTick = this.tick + u.cooldownTicks();
            }
        } else {
            this.moveTo(s, t.getLocation(), this.config.get().chaseSpeed, 8);
        }
    }

    private void melee(final Soldier s, final UnitType u, final LivingEntity t) {
        final DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK)
            .withCausingEntity(s.brain)
            .withDirectEntity(s.brain)
            .build();
        t.damage(u.damage() * this.damageMultiplier.applyAsDouble(s.country()), src);
        final LivingEntity visual = this.visualEntity(s);
        visual.swingMainHand();
    }

    private void startAim(final Soldier s) {
        s.aiming = true;
        final LivingEntity visual = this.visualEntity(s);
        final ItemStack hand = visual.getEquipment() == null ? null : visual.getEquipment().getItemInMainHand();
        if (hand != null && (hand.getType() == Material.BOW || hand.getType() == Material.CROSSBOW)) {
            visual.startUsingItem(EquipmentSlot.HAND);
        }
    }

    private void shoot(final Soldier s, final UnitType u, final LivingEntity t) {
        if (this.siegeEngines.isEngine(s)) {
            this.siegeEngines.fire(s,u,t.getLocation().add(0,t.getHeight()*0.6,0),t,
                u.damage()*this.damageMultiplier.applyAsDouble(s.country()));
            return;
        }
        final Mob b = s.brain;
        final Location eye = b.getEyeLocation();
        final Location aim = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        final Vector d = aim.toVector().subtract(eye.toVector());
        final double horizontal = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
        final double speed = u.projectileSpeed();
        final double comp = 0.2 * (1.6 / speed) * (1.6 / speed);
        d.setY(d.getY() + horizontal * Math.min(0.6, comp));
        if (d.lengthSquared() < 1.0E-6) {
            return;
        }
        d.normalize();
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        d.add(new Vector(rnd.nextGaussian() * u.spread(), rnd.nextGaussian() * u.spread(), rnd.nextGaussian() * u.spread()));
        d.normalize().multiply(speed);
        final Arrow arrow = b.launchProjectile(Arrow.class, d);
        arrow.setDamage(u.damage() * this.damageMultiplier.applyAsDouble(s.country()) / speed);
        arrow.setCritical(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setPersistent(false);
        this.keys.tag(arrow, s.id(), s.country(), Keys.PART_ARROW);
        final LivingEntity visual = this.visualEntity(s);
        visual.clearActiveItem();
        s.aiming = false;
        b.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1f, 1f / (rnd.nextFloat() * 0.4f + 0.8f));
    }

    private LivingEntity visualEntity(final Soldier s) {
        return s.body != null && s.body.isValid() ? s.body : s.brain;
    }

    private void visual(final Soldier s, final boolean fighting) {
        if (s.brain.isAggressive() != fighting) {
            s.brain.setAggressive(fighting);
        }
        if (!fighting && s.aiming) {
            this.visualEntity(s).clearActiveItem();
            s.aiming = false;
        }
    }

    private void behave(final Soldier s, final UnitType u) {
        final Mob b = s.brain;
        final Location here = b.getLocation();
        final ArmiaConfig cfg = this.config.get();
        switch (s.rec.mode) {
            case FOLLOW -> {
                final Player commander = s.rec.commander == null ? null : Bukkit.getPlayer(s.rec.commander);
                if (commander == null || !commander.isOnline() || commander.isDead()) {
                    // Commander left: hold where we are.
                    s.rec.mode = SoldierMode.HOLD;
                    s.rec.hold = Pos.of(here);
                    this.registry.markDirty();
                    return;
                }
                final Location cl = commander.getLocation();
                if (cl.getWorld() != here.getWorld() || cl.distanceSquared(here) > cfg.followTeleportDistance * cfg.followTeleportDistance) {
                    this.teleportNear(s, cl);
                    return;
                }
                final Location spot = this.formation(s, cl, cfg.followDistance);
                if (spot.distanceSquared(here) > 2.25) {
                    final double speed = cl.distanceSquared(here) > 100 ? cfg.chaseSpeed : 1.0;
                    this.moveTo(s, spot, speed, 10);
                } else if (b.getPathfinder().hasPath() && cl.distanceSquared(here) < cfg.followDistance * cfg.followDistance) {
                    b.getPathfinder().stopPathfinding();
                }
                if (this.tick % 20 == 0) {
                    b.lookAt(commander);
                }
            }
            case HOLD -> {
                if (s.rec.hold != null && s.rec.commander == null && s.rec.hold.distanceSq(here) > FAR_SQ) {
                    this.requestRelocate(s, s.rec.hold);
                    return;
                }
                final Location hold = s.rec.hold == null ? null : s.rec.hold.toLocation();
                if (hold == null) {
                    return;
                }
                final Location spot = this.formation(s, hold, 1.5);
                if (spot.getWorld() == here.getWorld() && spot.distanceSquared(here) > 2.25) {
                    this.moveTo(s, spot, 1.0, 20);
                }
            }
            case PATROL -> {
                if (s.rec.patrol.isEmpty()) {
                    s.rec.mode = SoldierMode.GUARD;
                    return;
                }
                s.rec.patrolIndex = Math.floorMod(s.rec.patrolIndex, s.rec.patrol.size());
                final Location point = s.rec.patrol.get(s.rec.patrolIndex).toLocation();
                if (point == null || point.getWorld() != here.getWorld()) {
                    s.rec.patrolIndex++;
                    return;
                }
                if (point.distanceSquared(here) < 4) {
                    s.rec.patrolIndex = (s.rec.patrolIndex + 1) % s.rec.patrol.size();
                    this.registry.markDirty();
                } else {
                    this.moveTo(s, point, 0.9, 20);
                }
            }
            case GUARD -> {
                if (s.rec.post != null && s.rec.post.distanceSq(here) > FAR_SQ) {
                    this.requestRelocate(s, s.rec.post); // post far away (AI border post): march "off-screen"
                    return;
                }
                final Location post = this.anchor(s);
                if (post == null || post.getWorld() != here.getWorld()) {
                    return;
                }
                final double r = Math.max(1, s.rec.radius);
                if (post.distanceSquared(here) > r * r) {
                    s.wander = null;
                    this.moveTo(s, post, 1.0, 20);
                    return;
                }
                if (s.wander != null) {
                    if (s.wander.getWorld() != here.getWorld() || s.wander.distanceSquared(here) < 2.25) {
                        s.wander = null;
                    } else {
                        this.moveTo(s, s.wander, 0.7, 30);
                    }
                } else if (ThreadLocalRandom.current().nextInt(400) == 0) {
                    final ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    final double a = rnd.nextDouble() * Math.PI * 2;
                    final double d = rnd.nextDouble() * r * 0.7;
                    s.wander = post.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
                }
            }
        }
    }

    /** Where the soldier belongs: guard post, hold position, commander. */
    Location anchor(final Soldier s) {
        return switch (s.rec.mode) {
            case GUARD -> {
                if (s.rec.post == null) {
                    yield null;
                }
                final Location l = s.rec.post.toLocation();
                if (l != null && s.rec.post.isSurface()) {
                    s.rec.post = s.rec.post.resolved(l);
                }
                yield l;
            }
            case PATROL -> s.rec.patrol.isEmpty() ? null : s.rec.patrol.get(Math.floorMod(s.rec.patrolIndex, s.rec.patrol.size())).toLocation();
            case HOLD -> s.rec.hold == null ? null : s.rec.hold.toLocation();
            case FOLLOW -> {
                final Player p = s.rec.commander == null ? null : Bukkit.getPlayer(s.rec.commander);
                yield p == null ? null : p.getLocation();
            }
        };
    }

    /** Spot of this soldier around a centre, so a squad spreads instead of stacking. */
    private Location formation(final Soldier s, final Location centre, final double base) {
        final int h = s.id().hashCode();
        final double angle = (h & 0xFFFF) / 65536.0 * Math.PI * 2;
        final double r = base + ((h >>> 16) % 3) * 0.9;
        return centre.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
    }

    void moveTo(final Soldier s, final Location target, final double speed, final int repathTicks) {
        if (this.tick < s.nextRepathTick && s.brain.getPathfinder().hasPath()) {
            return;
        }
        s.nextRepathTick = this.tick + repathTicks + ThreadLocalRandom.current().nextInt(5);
        s.brain.getPathfinder().moveTo(target, speed);
    }

    /** Pulls a soldier to its commander (large distance or other world). */
    public void teleportNear(final Soldier s, final Location where) {
        final Mob b = s.brain;
        if (b == null || where.getWorld() == null) {
            return;
        }
        final Location to = landing(this.formation(s, where, 1.5), where);
        b.eject();
        b.getPathfinder().stopPathfinding();
        b.teleport(to);
        if (s.body != null && s.body.isValid()) {
            s.body.teleport(to);
        }
        s.nextRepathTick = 0;
    }

    /**
     * Target of {@link #teleportNear}: the formation spot on the ground when the ground there is within 3 blocks of
     * {@code where}, otherwise {@code where} itself. The formation spot may lie up to ~3.3 blocks away, i.e. in a
     * neighbouring chunk: when that chunk is not loaded, {@code where} is used (no synchronous chunk load by the height
     * probe, no teleport into an unloaded chunk). {@code formation} is modified and returned.
     */
    static Location landing(final Location formation, final Location where) {
        final World w = where.getWorld();
        final boolean loaded = w.isChunkLoaded(formation.getBlockX() >> 4, formation.getBlockZ() >> 4);
        final int top = loaded ? w.getHighestBlockYAt(formation.getBlockX(), formation.getBlockZ()) : 0;
        if (!loaded || Math.abs(top + 1 - where.getY()) > 3) {
            formation.setX(where.getX());
            formation.setY(where.getY());
            formation.setZ(where.getZ());
        } else {
            formation.setY(top + 1.0);
        }
        return formation;
    }

    // ------------------------------------------------------------------ lookups used by listeners / services

    public Optional<Soldier> soldierOf(final Entity e) {
        if (e == null) {
            return Optional.empty();
        }
        final String part = this.keys.part(e);
        if (part == null || Keys.PART_RULER.equals(part) || Keys.PART_ARROW.equals(part)) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.registry.get(this.keys.soldierId(e)));
    }

    /** Strength of {@code country}'s soldiers standing in a chunk (siege). */
    public double presence(final UUID country, final String world, final int cx, final int cz) {
        double sum = 0;
        for (final Soldier s : this.registry.ofCountry(country)) {
            if (!s.attached()) {
                continue;
            }
            final Location l = s.brain.getLocation();
            if (l.getWorld().getName().equals(world) && (l.getBlockX() >> 4) == cx && (l.getBlockZ() >> 4) == cz) {
                final UnitType u = this.unit(s);
                sum += u == null ? 1.0 : u.siegeStrength();
            }
        }
        return sum;
    }
}
