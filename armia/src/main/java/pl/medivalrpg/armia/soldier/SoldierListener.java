// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Entity events of soldiers: (re)attaching on chunk load, protection and damage forwarding (body → brain),
 * friendly fire, and switching off vanilla behaviour (burning, conversions, natural targets, trading, riding).
 */
public final class SoldierListener implements Listener {

    private final Plugin plugin;
    private final Keys keys;
    private final SoldierRegistry registry;
    private final SoldierController controller;
    private final ArmyService army;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Map<UUID, Long> warned = new HashMap<>();

    public SoldierListener(final Plugin plugin, final Keys keys, final SoldierRegistry registry, final SoldierController controller,
                           final ArmyService army, final Services services, final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.keys = keys;
        this.registry = registry;
        this.controller = controller;
        this.army = army;
        this.services = services;
        this.config = config;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ loading

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            this.handleLoaded(e);
        }
    }

    /** Also used by the startup scan. */
    public void handleLoaded(final Entity e) {
        final String part = this.keys.part(e);
        if (part == null) {
            return;
        }
        switch (part) {
            case Keys.PART_BRAIN -> {
                if (e instanceof Mob mob) {
                    this.controller.onBrainLoaded(mob);
                } else {
                    e.remove();
                }
            }
            case Keys.PART_BODY -> {
                // Bodies are never saved; one found on disk is an orphan from a crash.
                final Soldier s = this.registry.get(this.keys.soldierId(e));
                if (s == null || s.body != e) {
                    e.remove();
                }
            }
            case Keys.PART_ARROW, Keys.PART_SIEGE_MODEL, Keys.PART_SIEGE_SHOT -> e.remove();
            default -> {
                // rulers are handled by RulerService
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        this.controller.onChunkLoaded(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(final EntitiesUnloadEvent event) {
        for (final Entity e : event.getEntities()) {
            if (Keys.PART_BRAIN.equals(this.keys.part(e))) {
                final Soldier s = this.registry.byBrain(e.getUniqueId());
                if (s != null && s.brain == e) {
                    this.controller.detach(s, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final String part = this.keys.part(dead);
        if (part == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (Keys.PART_BRAIN.equals(part)) {
            final Soldier s = this.registry.byBrain(dead.getUniqueId());
            if (s != null) {
                final EntityDamageEvent last = dead.getLastDamageCause();
                final Entity killer = last instanceof EntityDamageByEntityEvent by ? by.getDamager() : dead.getKiller();
                this.army.handleDeath(s, killer);
            }
        }
    }

    // ------------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        final Entity victim = event.getEntity();
        final String part = this.keys.part(victim);
        if (event instanceof EntityDamageByEntityEvent by && Keys.PART_ARROW.equals(this.keys.part(by.getDamager()))) {
            if (this.friendlyArrow(by.getDamager(), victim)) {
                event.setCancelled(true);
                by.getDamager().remove();
                return;
            }
        }
        if (part == null || Keys.PART_RULER.equals(part) || Keys.PART_ARROW.equals(part)) {
            return;
        }
        final Soldier s = this.registry.get(this.keys.soldierId(victim));
        if (s == null) {
            return;
        }
        final Entity attacker = event instanceof EntityDamageByEntityEvent by ? source(by.getDamager()) : null;
        if (attacker != null && !this.mayHurt(attacker, s)) {
            event.setCancelled(true);
            if (attacker instanceof Player p) {
                this.warn(p, s);
            }
            return;
        }
        if (Keys.PART_BODY.equals(part)) {
            // The body never takes damage itself: hits are passed to the brain (which owns the hit points).
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent by && s.attached()) {
                if (attacker != null && attacker.equals(s.brain)) {
                    return;
                }
                if (this.controller.siegeEngines().isRam(s) && burning(by.getDamager())) {
                    s.brain.setFireTicks(Math.max(s.brain.getFireTicks(), 100)); // flaming arrow / fire aspect sets the ram alight
                }
                s.brain.damage(event.getDamage(), event.getDamageSource());
            }
            return;
        }
        // Brain.
        if (this.controller.siegeEngines().isRam(s)) {
            // Covered ram: hide roof and timber shrug off arrows, but the dry wood burns (units.taran.ram)
            final var type = event.getDamageSource().getDamageType();
            final boolean fire = org.bukkit.tag.DamageTypeTags.IS_FIRE.isTagged(type)
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA;
            final Entity direct = event instanceof EntityDamageByEntityEvent by ? by.getDamager() : event.getDamageSource().getDirectEntity();
            final boolean siegeShot = direct != null && Keys.PART_SIEGE_SHOT.equals(this.keys.part(direct));
            final boolean projectile = !siegeShot && (org.bukkit.tag.DamageTypeTags.IS_PROJECTILE.isTagged(type)
                || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE || direct instanceof Projectile);
            event.setDamage(pl.medivalrpg.armia.logic.SiegeRules.ramIncoming(event.getDamage(), projectile, fire, this.config.get().ram));
        }
        if (attacker != null) {
            this.controller.noteAttacked(s, attacker);
        }
        if (s.body != null && s.body.isValid() && this.config.get().visual == ArmiaConfig.Visual.MANNEQUIN) {
            s.body.playHurtAnimation(0f);
            s.body.getWorld().playSound(s.body.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        }
    }

    private static boolean burning(final Entity damager) {
        if (damager == null) {
            return false;
        }
        if (damager instanceof Projectile) {
            return damager.getFireTicks() > 0;
        }
        if (damager instanceof org.bukkit.entity.LivingEntity living && living.getEquipment() != null) {
            return living.getEquipment().getItemInMainHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FIRE_ASPECT) > 0;
        }
        return false;
    }

    private static Entity source(final Entity damager) {
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** Players and soldiers may hurt a soldier only when their country is at war with it (configurable for neutrals). */
    private boolean mayHurt(final Entity attacker, final Soldier s) {
        final ArmiaConfig cfg = this.config.get();
        if (attacker instanceof Player p) {
            if (p.hasPermission("armia.bypass") || (p.getGameMode() == GameMode.CREATIVE && p.hasPermission("armia.admin"))) {
                return true;
            }
            final Optional<KrajInfo> c = this.services.kraje().getCountryOf(p.getUniqueId());
            if (c.isEmpty()) {
                return !cfg.protectFromNonEnemies || cfg.protectAllowCountryless;
            }
            if (c.get().id().equals(s.country())) {
                return false;
            }
            final Relation r = this.services.kraje().getRelation(c.get().id(), s.country());
            return r == Relation.WAR || (r == Relation.NEUTRAL && !cfg.protectFromNonEnemies);
        }
        final Optional<Soldier> other = this.controller.soldierOf(attacker);
        if (other.isPresent()) {
            return this.services.kraje().isAtWar(other.get().country(), s.country());
        }
        return true;
    }

    private boolean friendlyArrow(final Entity arrow, final Entity victim) {
        final UUID country = this.keys.countryId(arrow);
        if (country == null) {
            return false;
        }
        UUID victimCountry = null;
        if (victim instanceof Player p) {
            victimCountry = this.services.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null);
            if (victimCountry == null) {
                return !this.config.get().targetRules.attackCountryless();
            }
        } else {
            final Optional<Soldier> s = this.controller.soldierOf(victim);
            if (s.isPresent()) {
                victimCountry = s.get().country();
            } else {
                return Keys.PART_RULER.equals(this.keys.part(victim)) || this.keys.part(victim) == null && !(victim instanceof org.bukkit.entity.Enemy);
            }
        }
        return !this.services.kraje().isAtWar(country, victimCountry);
    }

    private void warn(final Player p, final Soldier s) {
        final long now = System.currentTimeMillis();
        final Long last = this.warned.get(p.getUniqueId());
        if (last != null && now - last < 3000) {
            return;
        }
        this.warned.put(p.getUniqueId(), now);
        final String country = this.services.kraje().getCountry(s.country()).map(KrajInfo::name).orElse("?");
        this.messages.get().send(p, "protected", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("country", country));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(final ProjectileHitEvent event) {
        if (Keys.PART_ARROW.equals(this.keys.part(event.getEntity()))) {
            final Entity arrow = event.getEntity();
            Bukkit.getScheduler().runTaskLater(this.plugin, arrow::remove, event.getHitEntity() != null ? 1L : 60L);
        }
    }

    // ------------------------------------------------------------------ vanilla behaviour off

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        final String part = this.keys.part(event.getEntity());
        if (part == null) {
            return;
        }
        // Plain EntityCombustEvent = sunlight. Bodies never burn at all.
        if (Keys.PART_BODY.equals(part) || Keys.PART_RULER.equals(part)
            || !(event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        if (this.keys.part(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(final EntityTargetEvent event) {
        final String part = this.keys.part(event.getEntity());
        if (part != null && event.getReason() != EntityTargetEvent.TargetReason.CUSTOM) {
            event.setCancelled(true); // soldiers pick targets themselves
            return;
        }
        if (event.getTarget() != null && event.getEntity() instanceof Golem && this.keys.part(event.getTarget()) != null) {
            event.setCancelled(true); // village golems leave soldiers alone
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        final String part = this.keys.part(event.getRightClicked());
        if (part != null && !Keys.PART_RULER.equals(part)) {
            event.setCancelled(true); // no trading, mounting, leashing, name tags
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAt(final PlayerInteractAtEntityEvent event) {
        final String part = this.keys.part(event.getRightClicked());
        if (part != null && !Keys.PART_RULER.equals(part)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(final VehicleEnterEvent event) {
        if (this.keys.part(event.getEntered()) != null && !this.ownMount(event.getEntered(), event.getVehicle())) {
            event.setCancelled(true); // no boats / minecarts
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(final EntityMountEvent event) {
        final String part = this.keys.part(event.getEntity());
        if (part != null && !this.ownMount(event.getEntity(), event.getMount())) {
            event.setCancelled(true);
            return;
        }
        // Players may not ride a knight's horse.
        if (event.getEntity() instanceof Player && Keys.PART_BRAIN.equals(this.keys.part(event.getMount()))) {
            event.setCancelled(true);
        }
    }

    private boolean ownMount(final Entity rider, final Entity mount) {
        if (!(mount instanceof Vehicle) && !(mount instanceof Mob)) {
            return false;
        }
        final UUID a = this.keys.soldierId(rider);
        return a != null && a.equals(this.keys.soldierId(mount));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (this.keys.part(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(final EntityPortalEvent event) {
        if (this.keys.part(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /** Copies the armour of a freshly spawned body onto its hidden brain (the brain takes the hits). */
    static void copyArmor(final LivingEntity body, final LivingEntity brain) {
        for (final Attribute a : new Attribute[] {Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS}) {
            final var from = body.getAttribute(a);
            final var to = brain.getAttribute(a);
            if (from != null && to != null) {
                to.setBaseValue(from.getValue());
            }
        }
    }
}
