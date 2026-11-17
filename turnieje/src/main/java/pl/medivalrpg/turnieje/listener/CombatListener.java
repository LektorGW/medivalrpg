// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.listener;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.turnieje.game.Horses;
import pl.medivalrpg.turnieje.game.Keys;
import pl.medivalrpg.turnieje.game.Phase;
import pl.medivalrpg.turnieje.game.Session;
import pl.medivalrpg.turnieje.game.SessionManager;

/**
 * Damage rules. Runs at HIGHEST so it decides after protection plugins:
 * <ul>
 *   <li>fighters of the same live fight may hit each other (also where PvP is otherwise off, {@code general.force-pvp});</li>
 *   <li>nobody else can hurt a fighter, and fighters cannot hurt outsiders;</li>
 *   <li>no damage during the 3…2…1 countdown or to waiting participants from outsiders;</li>
 *   <li>a lethal hit is cancelled and turned into a defeat — no death, no drops, no totem;</li>
 *   <li>our fireworks and horses never hurt or get hurt.</li>
 * </ul>
 */
public final class CombatListener implements Listener {

    private final Plugin plugin;
    private final SessionManager sessions;
    private final Horses horses;
    private final Keys keys;

    public CombatListener(final Plugin plugin, final SessionManager sessions, final Horses horses, final Keys keys) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.horses = horses;
        this.keys = keys;
    }

    private static Player attackerOf(final EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        final Entity damager = byEntity.getDamager();
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Firework fw
            && fw.getPersistentDataContainer().has(this.keys.firework, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }
        if (this.horses.isOurs(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        final Player attacker = attackerOf(event);
        if (!(event.getEntity() instanceof Player victim)) {
            if (attacker != null && this.sessions.liveFightSession(attacker.getUniqueId()) != null) {
                event.setCancelled(true); // fighters only fight each other
            }
            return;
        }
        final UUID vid = victim.getUniqueId();
        final Session fight = this.sessions.liveFightSession(vid);
        if (fight == null) {
            if (attacker != null && !attacker.equals(victim)) {
                final boolean attackerFighting = this.sessions.liveFightSession(attacker.getUniqueId()) != null;
                final Session waiting = this.sessions.sessionInside(vid);
                if (attackerFighting || waiting != null) {
                    event.setCancelled(true); // fighters vs outsiders, and nobody touches waiting participants
                }
            }
            return;
        }
        if (fight.phase() != Phase.FIGHTING) {
            event.setCancelled(true); // countdown
            return;
        }
        if (attacker != null && !attacker.equals(victim)) {
            if (!fight.isLiveFighter(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (event.isCancelled() && this.sessions.config().forcePvp) {
                event.setCancelled(false);
            }
        }
        if (event.isCancelled()) {
            return;
        }
        final double dmg = event.getFinalDamage();
        if (attacker != null && !attacker.equals(victim) && dmg > 0) {
            this.sessions.addDamage(attacker.getUniqueId(), Math.min(dmg, victim.getHealth()));
        }
        if (victim.getHealth() - dmg <= 0.0) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this.plugin, () -> this.sessions.onLethal(vid));
        }
    }
}
