// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;

/**
 * Player-vs-player rules ({@link PvpRules}): {@code pvp.mode: vanilla} (default) leaves combat alone except the
 * optional {@code pvp.friendly-fire: false}; {@code war-only} allows fights between countries only in war.
 * Covers melee, projectiles, fire from projectiles and harmful splash / lingering potions.
 */
public final class PvpListener implements Listener {

    private final KrajePlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public PvpListener(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inactive() {
        final PvpRules.Settings s = this.plugin.cfg().pvp;
        return s.mode() == PvpRules.Mode.VANILLA && s.friendlyFire();
    }

    /** Whether {@code attacker} may hurt {@code victim}; informs the attacker when not. */
    private boolean allowed(final Player attacker, final Player victim, final boolean notify) {
        if (attacker.equals(victim)) {
            return true;
        }
        final KrajeConfig cfg = this.plugin.cfg();
        final UUID a = this.plugin.manager().countryIdOf(attacker.getUniqueId());
        final UUID v = this.plugin.manager().countryIdOf(victim.getUniqueId());
        final boolean wild = this.plugin.manager().ownerAt(victim.getLocation()) == null;
        final boolean ok = PvpRules.allowed(cfg.pvp, a, v, this.plugin.manager().relation(a, v), wild);
        if (!ok && notify) {
            final long now = System.currentTimeMillis();
            final Long last = this.lastMessage.get(attacker.getUniqueId());
            if (last == null || now - last > 2000) {
                this.lastMessage.put(attacker.getUniqueId(), now);
                final boolean friendly = a != null && (a.equals(v) || this.plugin.manager().relation(a, v) == pl.medivalrpg.kraje.model.Relation.ALLIANCE);
                attacker.sendActionBar(this.plugin.messages().get(friendly ? "pvp-denied-friendly" : "pvp-denied"));
            }
        }
        return ok;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (this.inactive() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        final Player attacker = ProtectionService.responsiblePlayer(event.getDamager());
        if (attacker != null && !this.allowed(attacker, victim, true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(final EntityCombustByEntityEvent event) {
        if (this.inactive() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        final Player attacker = ProtectionService.responsiblePlayer(event.getCombuster());
        if (attacker != null && !this.allowed(attacker, victim, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(final PotionSplashEvent event) {
        if (this.inactive() || !(event.getPotion().getShooter() instanceof Player attacker)) {
            return;
        }
        boolean harmful = false;
        for (final PotionEffect effect : event.getPotion().getEffects()) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                harmful = true;
                break;
            }
        }
        if (!harmful) {
            return;
        }
        for (final LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && !this.allowed(attacker, victim, true)) {
                event.setIntensity(victim, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(final AreaEffectCloudApplyEvent event) {
        if (this.inactive()) {
            return;
        }
        final AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player attacker)) {
            return;
        }
        boolean harmful = cloud.getBasePotionType() != null && cloud.getBasePotionType().getPotionEffects().stream()
            .anyMatch(e -> e.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL);
        harmful = harmful || cloud.getCustomEffects().stream().anyMatch(e -> e.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL);
        if (!harmful) {
            return;
        }
        event.getAffectedEntities().removeIf(e -> e instanceof Player victim && !this.allowed(attacker, victim, false));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.lastMessage.remove(event.getPlayer().getUniqueId());
    }
}
