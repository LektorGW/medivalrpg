// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ruler;

import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.config.ArmiaConfig;

/**
 * NPC rulers never take damage: {@code setInvulnerable} does not stop players in creative mode, so every damage
 * event of a ruler is cancelled at the highest priority. Only with {@code ruler.admin-kill: true} a sneaking player
 * with {@code armia.admin} may hurt one. A ruler that disappears anyway (death, {@code /kill}) is placed again at its
 * spot shortly after (and by the periodic check / chunk load in {@link RulerService}).
 */
public final class RulerProtectionListener implements Listener {

    private final Plugin plugin;
    private final RulerService rulers;
    private final Supplier<ArmiaConfig> config;

    public RulerProtectionListener(final Plugin plugin, final RulerService rulers, final Supplier<ArmiaConfig> config) {
        this.plugin = plugin;
        this.rulers = rulers;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(final EntityDamageEvent event) {
        if (this.rulers.countryOf(event.getEntity()).isEmpty()) {
            return;
        }
        if (this.config.get().rulerAdminKill && event instanceof EntityDamageByEntityEvent byEntity && adminKiller(byEntity.getDamager())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean adminKiller(final Entity damager) {
        Entity source = damager;
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        return source instanceof Player p && p.isSneaking() && p.hasPermission("armia.admin");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(final EntityCombustEvent event) {
        if (this.rulers.countryOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        this.rulers.countryOf(event.getEntity()).ifPresent(country -> {
            event.getDrops().clear();
            event.setDroppedExp(0);
            this.plugin.getLogger().warning("Władca NPC kraju " + country + " zginął — zostanie odtworzony na swoim miejscu");
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.rulers.ensure(country), 20L);
        });
    }
}
